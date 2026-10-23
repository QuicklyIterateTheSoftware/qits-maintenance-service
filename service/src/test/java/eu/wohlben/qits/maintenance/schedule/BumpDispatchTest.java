package eu.wohlben.qits.maintenance.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.automation.DependencyBumpAutomation;
import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.bump.BumpOrder;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * THE GATE: a main-only release request for every READY repository owed a bump, in one tick, and
 * none when qits-ci's queue cannot even be read.
 *
 * <p>The subject here is the DISPATCH DECISION rather than the selection — which repository goes
 * first is {@code BumpOrderTest}'s, stated against a graph. What this pins is the part no unit test
 * can: that the CI queue snapshot (no longer a capacity, since epic qits-1481 — see {@link
 * BumpDispatcher}'s class doc), the automation rows still REQUESTED and the release requests
 * already open are actually wired to the thing that opens a request. Since qits-1133 R5 that thing
 * is a MAIN-ONLY {@code LOWEST} request, never a {@code maintenance/<group>} branch, and there is no
 * window to open first.
 *
 * <p><b>Every method drives {@link BumpDispatcher#tick()} by hand.</b> The suite's scheduler is off,
 * so a test that waited for the timer would be indistinguishable from a test that hung.
 */
@QuarkusTest
class BumpDispatchTest {

  @Inject BumpDispatcher dispatcher;

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.seedProducers(store);
    Fixture.scriptBranchAbsent(peers);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
    answersMainOnly(Fixture.CATALOG_ID, Fixture.REPOSITORY);
  }

  @AfterEach
  void drain() {
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  /** The request id qits-projects answers for one repository's main-only ask — stable per name. */
  private static String requestIdOf(String repository) {
    return UUID.nameUUIDFromBytes(repository.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String collection(String catalogId) {
    return ReleaseRequestClient.REQUESTS_PATH_PREFIX
        + catalogId
        + ReleaseRequestClient.REQUESTS_PATH_SUFFIX;
  }

  /**
   * qits-projects for one repository: no request open, and an ask answered with a fresh PENDING
   * one. One body serves both routes on the collection — the listing reads {@code requests}, the
   * ask reads {@code request}.
   */
  private void answersMainOnly(String catalogId, String repository) {
    String id = requestIdOf(repository);
    peers.answer(
        PeerTarget.PROJECTS,
        collection(catalogId),
        FakePeers.Scripted.ok(
            "{\"requests\":[],\"request\":{\"id\":\"" + id + "\",\"state\":\"PENDING\"}}"));
    requestIs(catalogId, id, "PENDING");
  }

  /** What qits-projects says one request is now. */
  private void requestIs(String catalogId, String requestId, String state) {
    peers.answer(
        PeerTarget.PROJECTS,
        collection(catalogId) + "/" + requestId,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + requestId + "\",\"state\":\"" + state + "\"}}"));
  }

  /**
   * The release asks POSTed to one repository's collection — the listing's GETs carry no body and
   * are not asks. (Read through a method: {@code peers} is a client proxy, and a field read on it
   * would see the proxy's own empty list.)
   */
  private long asks(String catalogId) {
    return peers.bodiesFor(collection(catalogId)).stream()
        .filter(java.util.Objects::nonNull)
        .count();
  }

  private List<String> repositoriesOf(List<UUID> opened) {
    return opened.stream()
        .map(id -> store.releaseRequest(id.toString()).orElseThrow().repository)
        .toList();
  }

  /** The one request a tick opened, failing when it opened none or several. */
  private static UUID only(List<UUID> opened) {
    assertEquals(1, opened.size(), "exactly one request was expected: " + opened);
    return opened.get(0);
  }

  // --- what is opened ---------------------------------------------------------------------------

  /**
   * <b>Debt and a free slot are the whole of the reason.</b> No cron, no window: the owed repository
   * gets a MAIN-ONLY LOWEST request, remembered with the pending set it was opened for — and no
   * {@code mt_bump} row and no group branch at all.
   */
  @Test
  void anOwedRepositoryAndAnIdleQueueOpenAMainOnlyRequest() {
    Fixture.scriptCiQueueEmpty(peers);

    UUID id = only(dispatcher.tick());

    assertEquals(requestIdOf(Fixture.REPOSITORY), id.toString());
    String ask =
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).stream()
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no release ask was posted"));
    assertTrue(ask.contains("\"branch\":\"main\""), ask);
    assertTrue(ask.contains("\"priority\":\"LOWEST\""), ask);
    MtReleaseRequest opened = store.releaseRequest(id.toString()).orElseThrow();
    assertEquals(MtReleaseRequest.MAIN_ONLY, opened.purpose);
    assertTrue(opened.opened);
    assertTrue(opened.changes.contains("qits-eventstream"), opened.changes);
    assertTrue(store.bumps(Fixture.REPOSITORY, 50).isEmpty(), "no bump row, no group branch");
    assertFalse(peers.called(PeerTarget.CI, CiClient.TRIGGER_PATH), "nothing went to qits-ci");
  }

  /** Nothing owed is nothing opened. */
  @Test
  void nothingIsOpenedWhenNothingIsOwed() {
    Fixture.scriptCiQueueEmpty(peers);
    inventory.clearLatest();

    assertEquals("NOTHING_OWED", dispatcher.explain(Instant.now()).outcome());
    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(0, asks(Fixture.CATALOG_ID));
  }

  // --- what holds -------------------------------------------------------------------------------

  /** The request just opened is on its way: the same pending set is held, not asked for again. */
  @Test
  void aRequestOnItsWayHoldsItsRepository() {
    Fixture.scriptCiQueueEmpty(peers);
    only(dispatcher.tick());

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("WAITING_ON_RELEASES", decision.outcome());
    assertEquals(1, decision.held());
    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, asks(Fixture.CATALOG_ID), "asked once");
  }

  /** A repository with ANY open request is held: that request's own pre-run carries the bump. */
  @Test
  void anOpenRequestOfAnyKindHoldsItsRepository() {
    Fixture.scriptCiQueueEmpty(peers);
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.ok(
            "{\"requests\":[{\"id\":\"a-persons\",\"state\":\"PENDING\",\"mergedSha\":null}]}"));

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(0, asks(Fixture.CATALOG_ID));
  }

  /**
   * One repository's listing, scripted with the given {@code requests} array verbatim, keeping a
   * fresh ask answerable in case the pick is not held (qits-1251).
   */
  private void scriptListing(String catalogId, String repository, String requestsJson) {
    String id = requestIdOf(repository);
    peers.answer(
        PeerTarget.PROJECTS,
        collection(catalogId),
        FakePeers.Scripted.ok(
            "{\"requests\":"
                + requestsJson
                + ",\"request\":{\"id\":\""
                + id
                + "\",\"state\":\"PENDING\"}}"));
  }

  /**
   * <b>Reproduction (qits-1251): a RELEASED request with no pipeline at all holds</b> — the tag is
   * cut and nothing says the publish has failed, so a main-only request must not overtake it.
   */
  @Test
  void aReleasedRequestWithNoPipelineHoldsItsRepository() {
    Fixture.scriptCiQueueEmpty(peers);
    scriptListing(
        Fixture.CATALOG_ID,
        Fixture.REPOSITORY,
        "[{\"id\":\"r-released\",\"state\":\"RELEASED\",\"mergedSha\":null}]");

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(0, asks(Fixture.CATALOG_ID));
  }

  /** A RELEASED request whose PUBLISH phase is still going (PASSED or PENDING) holds. */
  @ParameterizedTest
  @ValueSource(strings = {"PASSED", "PENDING"})
  void aReleasedRequestWithAPublishStillGoingHoldsItsRepository(String publishState) {
    Fixture.scriptCiQueueEmpty(peers);
    scriptListing(
        Fixture.CATALOG_ID,
        Fixture.REPOSITORY,
        "[{\"id\":\"r-released\",\"state\":\"RELEASED\",\"pipeline\":{\"phases\":[{\"phase\":"
            + "\"PUBLISH\",\"state\":\""
            + publishState
            + "\"}]}}]");

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(0, asks(Fixture.CATALOG_ID));
  }

  /**
   * A RELEASED request whose PUBLISH phase FAILED does not hold: nothing will retry it on its own,
   * so holding would starve the repository of every bump for good. The main-only request this tick
   * opens is the recovery.
   */
  @Test
  void aReleasedRequestWithAFailedPublishDoesNotHoldItsRepository() {
    Fixture.scriptCiQueueEmpty(peers);
    scriptListing(
        Fixture.CATALOG_ID,
        Fixture.REPOSITORY,
        "[{\"id\":\"r-released\",\"state\":\"RELEASED\",\"pipeline\":{\"phases\":[{\"phase\":"
            + "\"PUBLISH\",\"state\":\"FAILED\"}]}}]");

    assertEquals(1, dispatcher.tick().size());
    assertEquals(1, asks(Fixture.CATALOG_ID));
  }

  /** The same, for a FAILED DEPLOYMENT gate rather than a FAILED PUBLISH phase. */
  @Test
  void aReleasedRequestWithAFailedDeploymentGateDoesNotHoldItsRepository() {
    Fixture.scriptCiQueueEmpty(peers);
    scriptListing(
        Fixture.CATALOG_ID,
        Fixture.REPOSITORY,
        "[{\"id\":\"r-released\",\"state\":\"RELEASED\",\"gates\":[{\"kind\":\"DEPLOYMENT\","
            + "\"state\":\"FAILED\"}]}]");

    assertEquals(1, dispatcher.tick().size());
    assertEquals(1, asks(Fixture.CATALOG_ID));
  }

  /** A listing of only CLOSED requests (OBSOLETE, FINALIZED, WITHDRAWN) holds nothing. */
  @Test
  void onlyClosedRequestsDoNotHoldItsRepository() {
    Fixture.scriptCiQueueEmpty(peers);
    scriptListing(
        Fixture.CATALOG_ID,
        Fixture.REPOSITORY,
        "[{\"id\":\"r-obsolete\",\"state\":\"OBSOLETE\"},{\"id\":\"r-finalized\",\"state\":"
            + "\"FINALIZED\"},{\"id\":\"r-withdrawn\",\"state\":\"WITHDRAWN\"}]");

    assertEquals(1, dispatcher.tick().size());
    assertEquals(1, asks(Fixture.CATALOG_ID));
  }

  /** Every OPEN state holds its repository, whatever it is. */
  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "READY", "REJECTED", "FAILED", "CONFLICTED"})
  void everyOpenStateHoldsItsRepository(String state) {
    Fixture.scriptCiQueueEmpty(peers);
    scriptListing(
        Fixture.CATALOG_ID,
        Fixture.REPOSITORY,
        "[{\"id\":\"r-open\",\"state\":\"" + state + "\"}]");

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(0, asks(Fixture.CATALOG_ID));
  }

  /** A listing that cannot be read holds, as every unreadable answer here does. */
  @Test
  void anUnreadableListingHolds() {
    Fixture.scriptCiQueueEmpty(peers);
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.unreachable("connection refused"));

    assertTrue(dispatcher.tick().isEmpty());
  }

  /**
   * The main-only request was withdrawn by its own pre-run, which found nothing to write: the same
   * pending set would find nothing again, so it is held.
   */
  @Test
  void aRequestItsPreRunWithdrewHoldsTheSamePendingSet() {
    Fixture.scriptCiQueueEmpty(peers);
    UUID id = only(dispatcher.tick());
    store.requestWithdrawn(id.toString(), "nothing to bump", Instant.now());

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, asks(Fixture.CATALOG_ID));
  }

  /**
   * <b>A withdrawal that carried a bump does not hold the repository</b> (qits-1173). The
   * dependency-bump history here is exactly what the qits-1226 bug left behind: a SUCCEEDED row
   * from an earlier fold, then a NOTHING_TO_DO row the pre-run's last fold ended on, which is what
   * the withdrawal read as "nothing to bump". The row DID write one, so the withdrawal must not
   * hold — the repository is asked again, not held on the same pending set for ever.
   */
  @Test
  void aWithdrawalThatCarriedABumpDoesNotHoldTheRepository() {
    Fixture.scriptCiQueueEmpty(peers);
    UUID id = only(dispatcher.tick());
    String branch = "maintenance/automations/" + id;
    store.openAutomation(
        new MaintenanceStore.AutomationOpening(
            Fixture.REPOSITORY,
            DependencyBumpAutomation.KIND,
            id.toString(),
            null,
            null,
            null,
            branch,
            null,
            "dev",
            BumpTrigger.MANUAL,
            List.of(),
            Map.of(),
            BumpStatus.SUCCEEDED,
            "wrote a bump"),
        false,
        Instant.now());
    store.openAutomation(
        new MaintenanceStore.AutomationOpening(
            Fixture.REPOSITORY,
            DependencyBumpAutomation.KIND,
            id.toString(),
            null,
            null,
            null,
            branch,
            null,
            "dev",
            BumpTrigger.MANUAL,
            List.of(),
            Map.of(),
            BumpStatus.NOTHING_TO_DO,
            "nothing left to write"),
        false,
        Instant.now().plusSeconds(1));
    store.requestWithdrawn(id.toString(), "nothing to bump", Instant.now());

    only(dispatcher.tick());
    assertEquals(2, asks(Fixture.CATALOG_ID));
  }

  /** A PERSON withdrew it: a withdrawn request counts as none, and the repository is asked again. */
  @Test
  void aRequestAPersonWithdrewFreesTheRepository() {
    Fixture.scriptCiQueueEmpty(peers);
    UUID id = only(dispatcher.tick());
    requestIs(Fixture.CATALOG_ID, id.toString(), "WITHDRAWN");

    assertEquals(1, dispatcher.tick().size(), "a withdrawn request is no request");
    assertEquals(2, asks(Fixture.CATALOG_ID));
  }

  /** A newer upstream release is a different pending set, and a different request. */
  @Test
  void aPendingSetThatMovedIsAskedForAgain() {
    Fixture.scriptCiQueueEmpty(peers);
    only(dispatcher.tick());
    store.recordLatestIfNewer(
        Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2029.101.1", "test", Instant.now());

    assertEquals(1, dispatcher.tick().size(), "a new upstream release is a new bump");
    assertEquals(2, asks(Fixture.CATALOG_ID));
  }

  /**
   * A repository that cannot be asked for — no catalog id to address qits-projects with — is
   * refused once and set aside, still reported, rather than picked again every tick.
   */
  @Test
  void aRepositoryThatCannotBeAskedForIsRefusedAndSetAside() {
    inventory.clear();
    String repository = "qits-refused-" + UUID.randomUUID();
    scanned(repository, null, internalPin("eu.wohlben.qits:qits-refused"));
    store.recordLatestIfNewer(
        Ecosystem.MAVEN, "eu.wohlben.qits:qits-refused", "2026.913.1", "test", Instant.now());
    Fixture.scriptCiQueueEmpty(peers);

    assertTrue(dispatcher.tick().isEmpty());
    BumpDispatcher.Assessment after = dispatcher.assess();
    assertTrue(after.candidates().isEmpty());
    assertEquals(List.of(repository), after.refused());
  }

  // --- qits-ci no longer gates dispatch by capacity (epic qits-1481) -------------------------

  /** <b>UNREADABLE IS BUSY.</b> Nothing is opened blind. */
  @Test
  void aQueueThatCannotBeReadIsTreatedAsBusy() {
    peers.answer(
        PeerTarget.CI, CiClient.QUEUE_PATH, FakePeers.Scripted.unreachable("connection refused"));

    assertEquals("CI_UNREADABLE", dispatcher.explain(Instant.now()).outcome());
    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(0, asks(Fixture.CATALOG_ID));
  }

  /** A snapshot missing either half is unreadable, not empty. */
  @Test
  void aSnapshotMissingEitherHalfIsUnreadableNotEmpty() {
    peers.answer(PeerTarget.CI, CiClient.QUEUE_PATH, FakePeers.Scripted.ok("{\"running\":[]}"));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_UNREADABLE", decision.outcome());
    assertTrue(decision.summary().contains("queued"), decision.summary());
    assertTrue(dispatcher.tick().isEmpty());
  }

  /**
   * <b>A BUSY QUEUE NO LONGER HOLDS ANYTHING BACK</b> (epic qits-1481): {@code GET
   * /ci/api/runs/queue} carries no slot count any more, so a repository is dispatched whether
   * qits-ci's queue is idle or already busy with other runs — the lease the trigger produces just
   * queues behind them, as it always could.
   */
  @Test
  void aBusyQueueOpensExactlyAsMuchAsAnIdleOne() {
    Fixture.scriptCiQueue(peers, 2);

    assertEquals(1, dispatcher.tick().size(), "two active runs do not hold the dispatch back");
    assertEquals(1, asks(Fixture.CATALOG_ID));
  }

  /** QITS-882, carried forward without a slot count: every READY repository goes in one tick. */
  @Test
  void everyReadyRepositoryGoesInOneTickHoweverBusyTheQueueIs() {
    inventory.clear();
    owes("qits-aaa-service", "eu.wohlben.qits:qits-a");
    owes("qits-bbb-service", "eu.wohlben.qits:qits-b");
    owes("qits-ccc-service", "eu.wohlben.qits:qits-c");
    lastReached("qits-aaa-service", Instant.now().minus(Duration.ofHours(1)));
    lastReached("qits-bbb-service", Instant.now().minus(Duration.ofDays(2)));
    lastReached("qits-ccc-service", Instant.now().minus(Duration.ofDays(1)));
    Fixture.scriptCiQueue(peers, 5);

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("DISPATCH", decision.outcome());
    assertEquals(5, decision.ciActive());

    List<UUID> sent = dispatcher.tick();
    assertEquals(
        List.of("qits-bbb-service", "qits-ccc-service", "qits-aaa-service"),
        repositoriesOf(sent),
        "every READY one in one tick, the longest-waiting first, whatever qits-ci's queue holds");
  }

  // --- the order ----------------------------------------------------------------------------------

  /**
   * <b>THE ALPHABET IS NOT THE TIEBREAK.</b> Two equally ready repositories, named so that the
   * alphabet would answer wrongly: the one the dispatcher reached a fortnight ago goes before the one
   * it reached an hour ago (measured 2026-09-13, when the alphabet starved the same repositories
   * every night).
   */
  @Test
  void theRepositoryReachedLongestAgoGoesFirstEvenWhenItsNameSortsLast() {
    inventory.clear();
    owes("qits-aaa-daemon", "eu.wohlben.qits:qits-agents-early");
    owes("qits-zzz-daemon", "eu.wohlben.qits:qits-agents-late");
    lastReached("qits-aaa-daemon", Instant.now().minus(Duration.ofHours(1)));
    lastReached("qits-zzz-daemon", Instant.now().minus(Duration.ofDays(14)));

    List<String> order =
        dispatcher.assess().candidates().stream().map(BumpOrder.Candidate::repository).toList();
    assertEquals(List.of("qits-zzz-daemon", "qits-aaa-daemon"), order);
  }

  /**
   * <b>AND THE TOPOLOGY STILL OVERRULES IT.</b> An upstream that is itself owed goes before its
   * consumer whatever the clock last did to either, and an idle queue does not let the consumer go
   * beside it.
   */
  @Test
  void anOwedUpstreamGoesBeforeItsConsumerWhateverTheQueueAndTheWait() {
    inventory.clear();
    owes("qits-zzz-lib", "eu.wohlben.qits:qits-agents-lib");
    owesItsSubmodule("qits-aaa-consumer", "qits-zzz-lib");
    lastReached("qits-zzz-lib", Instant.now().minus(Duration.ofHours(1)));
    lastReached("qits-aaa-consumer", Instant.now().minus(Duration.ofDays(14)));
    Fixture.scriptCiQueueEmpty(peers);

    assertEquals(
        "qits-aaa-consumer",
        dispatcher.assess().candidates().get(0).repository(),
        "the starved one IS first in the order handed to BumpOrder");
    assertEquals(List.of("qits-zzz-lib"), repositoriesOf(dispatcher.tick()));
    assertEquals(0, asks("catalog-qits-aaa-consumer"), "it waits on the upstream's release");
  }

  /** A legacy group row does not count as the dispatcher reaching a repository. */
  @Test
  void onlyMainOnlyRequestsCountAsTheDispatcherReachingARepository() {
    lastReached(Fixture.REPOSITORY, Instant.now().minus(Duration.ofDays(1)));
    store.recordOpenedRequest(
        "a-group-ask", Fixture.REPOSITORY, "maintenance/dependencies",
        MtReleaseRequest.GROUP_BUMP, null, Instant.now());

    Instant reached = store.lastDispatchedAt().get(Fixture.REPOSITORY);
    assertTrue(reached.isBefore(Instant.now().minus(Duration.ofHours(1))), reached.toString());
    assertFalse(store.lastDispatchedAt().containsKey("never-" + UUID.randomUUID()));
    assertTrue(
        store.bumps(Fixture.REPOSITORY, 50).stream()
            .noneMatch(row -> BumpMode.GROUP.name().equals(row.mode)));
  }

  // --- fixtures -----------------------------------------------------------------------------------

  /** A repository with one INTERNAL maven pin a release has moved past, and qits-projects scripted. */
  private void owes(String repository, String dependency) {
    scanned(repository, "catalog-" + repository, internalPin(dependency));
    store.recordLatestIfNewer(Ecosystem.MAVEN, dependency, "2026.913.1", "test", Instant.now());
  }

  private static ParsedPin internalPin(String dependency) {
    return ParsedPin.of(
        Ecosystem.MAVEN, "pom.xml", dependency, "2026.901.1", null, "dependency:" + dependency);
  }

  /** …and one whose pending change is the SUBMODULE it carries — the edge BumpOrder reads by name. */
  private void owesItsSubmodule(String repository, String submodule) {
    scanned(
        repository,
        "catalog-" + repository,
        ParsedPin.of(
            Ecosystem.GITLINK,
            ".gitmodules",
            submodule,
            "1111111111111111111111111111111111111111",
            null,
            "gitlink:webui"));
    store.recordLatestIfNewer(
        Ecosystem.GITLINK,
        submodule,
        "2026.913.1",
        GitlinkSha.of("2222222222222222222222222222222222222222"),
        Instant.now());
  }

  private void scanned(String repository, String catalogId, ParsedPin pin) {
    store.replaceInventory(
        repository,
        Fixture.PROJECT,
        catalogId,
        null,
        "main",
        RepositoryStatus.OK,
        "sha-" + repository,
        null,
        List.of(pin),
        List.of(GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    if (catalogId != null) {
      answersMainOnly(catalogId, repository);
    }
  }

  /**
   * A main-only request the dispatcher opened for this repository at that time, already gone.
   *
   * <p><b>It carries no changes on purpose</b>: it is history — what the tiebreak reads — and a row
   * whose changes matched the pending set would HOLD the repository instead, a different rule.
   */
  private void lastReached(String repository, Instant at) {
    String id = "reached-" + repository + "-" + at.toEpochMilli();
    store.recordOpenedRequest(
        id, repository, "main", MtReleaseRequest.MAIN_ONLY, List.<Change>of(), at);
  }
}

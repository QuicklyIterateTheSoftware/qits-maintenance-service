package eu.wohlben.qits.maintenance.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.automation.EstatePinsAutomation;
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

/**
 * THE GATE: a main-only release request for every repository owed a bump and without one, as many
 * at a time as qits-ci has free slots, and none when it has none.
 *
 * <p>The subject here is the DISPATCH DECISION rather than the selection — which repository goes
 * first is {@code BumpOrderTest}'s, stated against a graph. What this pins is the part no unit test
 * can: that the CI queue snapshot, its runners' slots, the automation rows still REQUESTED and the
 * release requests already open are actually wired to the thing that opens a request. Since
 * qits-1133 R5 that thing is a MAIN-ONLY {@code LOWEST} request, never a {@code maintenance/<group>}
 * branch, and there is no window to open first.
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

  // --- the capacity gate ----------------------------------------------------------------------

  /** A busy qits-ci is not handed another build, and a free slot is what it was waiting for. */
  @Test
  void aFullQueueOpensNothingAndAFreeSlotOpensOne() {
    Fixture.scriptCiQueue(peers, 2);

    assertTrue(dispatcher.tick().isEmpty(), "two runs are active on two slots");
    assertEquals(0, asks(Fixture.CATALOG_ID));

    Fixture.scriptCiQueueEmpty(peers);
    assertEquals(1, dispatcher.tick().size());
  }

  /** <b>UNREADABLE IS BUSY.</b> Nothing is opened blind. */
  @Test
  void aQueueThatCannotBeReadIsTreatedAsBusy() {
    peers.answer(
        PeerTarget.CI, CiClient.QUEUE_PATH, FakePeers.Scripted.unreachable("connection refused"));

    assertEquals("CI_UNREADABLE", dispatcher.explain(Instant.now()).outcome());
    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(0, asks(Fixture.CATALOG_ID));
  }

  /** A snapshot missing its runners is unreadable, not empty. */
  @Test
  void aSnapshotMissingItsRunnersIsUnreadableNotEmpty() {
    peers.answer(
        PeerTarget.CI,
        CiClient.QUEUE_PATH,
        FakePeers.Scripted.ok("{\"running\":[],\"queued\":[]}"));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_UNREADABLE", decision.outcome());
    assertTrue(decision.summary().contains("runners"), decision.summary());
    assertTrue(dispatcher.tick().isEmpty());
  }

  /**
   * <b>AN AUTOMATION ROW OF OURS STILL REQUESTED CONSUMES A SLOT.</b> It is a build this service
   * asked for that qits-ci has not accepted yet, so it is in no listing qits-ci answers. Once qits-ci
   * accepts it, it is counted there and not again here.
   */
  @Test
  void aRequestedAutomationRowConsumesASlot() {
    UUID requested =
        store.openAutomation(
            new MaintenanceStore.AutomationOpening(
                "qits-other-service",
                EstatePinsAutomation.KIND,
                null,
                null,
                null,
                null,
                "workspace/ws-7",
                null,
                "dev",
                BumpTrigger.MANUAL,
                List.of(),
                Map.of(),
                BumpStatus.REQUESTED,
                null),
            true,
            Instant.now());
    Fixture.scriptCiQueueEmpty(peers);

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_BUSY", decision.outcome());
    assertEquals(1, decision.slots());
    assertEquals(0, decision.free());
    assertEquals(1, decision.inFlight());
    assertTrue(
        decision.summary().contains("1 slot(s), 0 active run(s) and 1 bump(s) waiting to reach it"),
        decision.summary());
    assertTrue(dispatcher.tick().isEmpty());

    store.bumpDispatched(requested, "e-other", List.of("run-other"));
    Fixture.scriptCiQueue(peers, 1, Fixture.runner("qits-ci", 2, true, false));
    assertEquals(1, dispatcher.explain(Instant.now()).free(), "a RUNNING row is not counted twice");
    assertEquals(1, dispatcher.tick().size());
  }

  /** QITS-882: every READY repository goes in one tick when qits-ci has the slots. */
  @Test
  void everyReadyRepositoryGoesInOneTickWhenQitsCiHasTheSlots() {
    inventory.clear();
    owes("qits-aaa-service", "eu.wohlben.qits:qits-a");
    owes("qits-bbb-service", "eu.wohlben.qits:qits-b");
    owes("qits-ccc-service", "eu.wohlben.qits:qits-c");
    lastReached("qits-aaa-service", Instant.now().minus(Duration.ofHours(1)));
    lastReached("qits-bbb-service", Instant.now().minus(Duration.ofDays(2)));
    lastReached("qits-ccc-service", Instant.now().minus(Duration.ofDays(1)));
    Fixture.scriptCiQueue(
        peers,
        1,
        Fixture.runner("qits-ci", 2, true, false),
        Fixture.runner("workstation", 6, true, false));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("DISPATCH", decision.outcome());
    assertEquals(8, decision.slots());
    assertEquals(1, decision.ciActive());
    assertEquals(7, decision.free());

    List<UUID> sent = dispatcher.tick();
    assertEquals(
        List.of("qits-bbb-service", "qits-ccc-service", "qits-aaa-service"),
        repositoriesOf(sent),
        "every READY one in one tick, the longest-waiting first");
  }

  /** Three free slots and five READY: exactly three go, and the other two wait. */
  @Test
  void threeFreeSlotsOpenExactlyThreeOfFive() {
    inventory.clear();
    for (String name : List.of("qits-r1", "qits-r2", "qits-r3", "qits-r4", "qits-r5")) {
      owes(name, "eu.wohlben.qits:" + name);
    }
    Fixture.scriptCiQueue(peers, 1, Fixture.runner("qits-ci", 4, true, false));

    assertEquals(3, dispatcher.explain(Instant.now()).free());
    assertEquals(List.of("qits-r1", "qits-r2", "qits-r3"), repositoriesOf(dispatcher.tick()));
    assertEquals(0, asks("catalog-qits-r4"));
    assertEquals(0, asks("catalog-qits-r5"));
  }

  /** Every slot taken: CI_BUSY, saying how it counted, and nothing opened. */
  @Test
  void everySlotTakenIsBusyAndOpensNothing() {
    Fixture.scriptCiQueue(
        peers,
        8,
        Fixture.runner("qits-ci", 2, true, false),
        Fixture.runner("workstation", 6, true, false));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_BUSY", decision.outcome());
    assertEquals(
        "qits-ci has 8 slot(s), 8 active run(s) and 0 bump(s) waiting to reach it; nothing is free",
        decision.summary());
    assertTrue(dispatcher.tick().isEmpty());
  }

  /** A runner that is not connected, or quarantined, offers no slot. */
  @Test
  void disconnectedAndQuarantinedRunnersOfferNoSlots() {
    Fixture.scriptCiQueue(
        peers,
        0,
        Fixture.runner("gone", 4, false, false),
        Fixture.runner("sick", 4, true, true),
        Fixture.runner("qits-ci", 1, true, false),
        Fixture.runner("misconfigured", -3, true, false));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals(1, decision.slots(), "only the connected, healthy runner counts");
    assertEquals(1, decision.free());
    assertEquals(1, dispatcher.tick().size());
  }

  /** No runner at all that could claim a run is NO_SLOTS — not busy — and nothing is opened. */
  @Test
  void noUsableRunnerIsNoSlots() {
    Fixture.scriptCiQueue(
        peers, 0, Fixture.runner("gone", 4, false, false), Fixture.runner("sick", 2, true, true));

    assertEquals("NO_SLOTS", dispatcher.explain(Instant.now()).outcome());
    assertTrue(dispatcher.tick().isEmpty());
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
   * consumer whatever the clock last did to either, and free slots do not let the consumer go
   * beside it.
   */
  @Test
  void anOwedUpstreamGoesBeforeItsConsumerWhateverTheSlotsAndTheWait() {
    inventory.clear();
    owes("qits-zzz-lib", "eu.wohlben.qits:qits-agents-lib");
    owesItsSubmodule("qits-aaa-consumer", "qits-zzz-lib");
    lastReached("qits-zzz-lib", Instant.now().minus(Duration.ofHours(1)));
    lastReached("qits-aaa-consumer", Instant.now().minus(Duration.ofDays(14)));
    Fixture.scriptCiQueue(peers, 0, Fixture.runner("workstation", 6, true, false));

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

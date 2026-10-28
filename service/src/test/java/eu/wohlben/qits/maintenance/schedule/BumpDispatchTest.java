package eu.wohlben.qits.maintenance.schedule;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.automation.DependencyBumpAutomation;
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
import io.restassured.response.ValidatableResponse;
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
 * THE GATE: a main-only release request for every repository owed a bump and without one, as many
 * at a time as the configured CI cap has room for, and none when it has none.
 *
 * <p>The subject here is the DISPATCH DECISION rather than the selection — which repository goes
 * first is {@code BumpOrderTest}'s, stated against a graph. What this pins is the part no unit test
 * can: that the CI queue snapshot, {@code qits.maintenance.bump.ci-active-cap} (a configured number
 * since epic qits-1481 — see {@link BumpDispatcher}'s class doc, qits-ci no longer reports one of
 * its own), the automation rows still REQUESTED and the release requests already open are actually
 * wired to the thing that opens a request. Since qits-1133 R5 that thing is a MAIN-ONLY {@code
 * LOWEST} request, never a {@code maintenance/<group>} branch, and there is no window to open
 * first.
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
   * The same, short of {@code qits.maintenance.bump.dispatch.nothing-found-hold}'s shipped 6h: the
   * withdrawal is still within the hold, so it holds exactly as above (qits-1696).
   */
  @Test
  void aWithdrawalYoungerThanTheNothingFoundHoldStillHolds() {
    Fixture.scriptCiQueueEmpty(peers);
    UUID id = only(dispatcher.tick());
    store.requestWithdrawn(id.toString(), "nothing to bump", Instant.now().minus(Duration.ofHours(5)));

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, asks(Fixture.CATALOG_ID));
  }

  /**
   * <b>Past the hold, the same pending set is offered once more</b> (qits-1696). Until qits-1696's
   * own CI fix, the dependency-bump step silently failed to commit gitlink moves, so a main-only
   * request it withdrew as "nothing to bump" was not proof the set was ever really empty — holding
   * on that report forever would strand the repository on it for good. Past {@code
   * nothing-found-hold} the withdrawal no longer holds, whatever the pending set turns out to hold
   * this time.
   */
  @Test
  void aWithdrawalOlderThanTheNothingFoundHoldNoLongerHolds() {
    Fixture.scriptCiQueueEmpty(peers);
    UUID id = only(dispatcher.tick());
    store.requestWithdrawn(id.toString(), "nothing to bump", Instant.now().minus(Duration.ofHours(7)));

    assertEquals(1, dispatcher.tick().size(), "an aged-out withdrawal is asked for again");
    assertEquals(2, asks(Fixture.CATALOG_ID));
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

  /**
   * <b>An UNDECLARED repository is never dispatched</b> (qits-1695), even with pins a release has
   * moved past and a free queue. A full scan drops such a row's pins anyway; this pins the second
   * guard, the dispatcher's own status gate, against a row that still carries some.
   */
  @Test
  void anUndeclaredRepositoryIsNeverDispatched() {
    inventory.clear();
    String repository = "qits-retired-daemon";
    scanned(
        repository,
        "catalog-" + repository,
        internalPin("eu.wohlben.qits:qits-retired"),
        RepositoryStatus.UNDECLARED);
    store.recordLatestIfNewer(
        Ecosystem.MAVEN, "eu.wohlben.qits:qits-retired", "2026.913.1", "test", Instant.now());
    Fixture.scriptCiQueueEmpty(peers);

    assertTrue(dispatcher.assess().candidates().isEmpty(), "not a candidate");
    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(0, asks("catalog-" + repository), "no release ask was posted");
  }

  // --- the capacity gate (epic qits-1481: a CONFIGURED cap, qits-ci reports no slots) ----------

  /**
   * A busy qits-ci is not handed another build, and room under the cap is what it was waiting for.
   * The shipped cap is 4 ({@code qits.maintenance.bump.ci-active-cap}, unoverridden in this suite).
   */
  @Test
  void aQueueAtTheCapOpensNothingAndRoomUnderItOpensOne() {
    Fixture.scriptCiQueue(peers, 4);

    assertTrue(dispatcher.tick().isEmpty(), "four active runs fill the cap of four");
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
   * <b>AN AUTOMATION ROW OF OURS STILL REQUESTED COUNTS AGAINST THE CAP.</b> It is a build this
   * service asked for that qits-ci has not accepted yet, so it is in no listing qits-ci answers.
   * Once qits-ci accepts it, it is counted there and not again here.
   */
  @Test
  void aRequestedAutomationRowCountsAgainstTheCap() {
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
    Fixture.scriptCiQueue(peers, 3);

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_BUSY", decision.outcome());
    assertEquals(4, decision.cap());
    assertEquals(0, decision.free());
    assertEquals(1, decision.inFlight());
    assertTrue(
        decision.summary().contains("cap of 4 (3 active run(s) and 1 bump(s) waiting to reach it)"),
        decision.summary());
    assertTrue(dispatcher.tick().isEmpty());

    // The queue snapshot is unchanged: only the row's own status moved, REQUESTED to dispatched,
    // so it stops being counted here — it is qits-ci's queue listing's to count from now on, and
    // this fixture does not need to grow `active` to prove that: it is enough that `requested`
    // dropping to zero is what frees the room, not a coincidence of a larger snapshot.
    store.bumpDispatched(requested, "e-other", List.of("run-other"));
    assertEquals(1, dispatcher.explain(Instant.now()).free(), "a dispatched row is not counted twice");
    assertEquals(1, dispatcher.tick().size());
  }

  /** QITS-882: every READY repository goes in one tick while there is room under the cap. */
  @Test
  void everyReadyRepositoryGoesInOneTickWhileThereIsRoomUnderTheCap() {
    inventory.clear();
    owes("qits-aaa-service", "eu.wohlben.qits:qits-a");
    owes("qits-bbb-service", "eu.wohlben.qits:qits-b");
    owes("qits-ccc-service", "eu.wohlben.qits:qits-c");
    lastReached("qits-aaa-service", Instant.now().minus(Duration.ofHours(1)));
    lastReached("qits-bbb-service", Instant.now().minus(Duration.ofDays(2)));
    lastReached("qits-ccc-service", Instant.now().minus(Duration.ofDays(1)));
    Fixture.scriptCiQueue(peers, 1);

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("DISPATCH", decision.outcome());
    assertEquals(4, decision.cap());
    assertEquals(1, decision.ciActive());
    assertEquals(3, decision.free());

    List<UUID> sent = dispatcher.tick();
    assertEquals(
        List.of("qits-bbb-service", "qits-ccc-service", "qits-aaa-service"),
        repositoriesOf(sent),
        "every READY one in one tick, the longest-waiting first");
  }

  /** Two free under the cap and five READY: exactly two go, and the other three wait. */
  @Test
  void twoFreeUnderTheCapOpenExactlyTwoOfFive() {
    inventory.clear();
    for (String name : List.of("qits-r1", "qits-r2", "qits-r3", "qits-r4", "qits-r5")) {
      owes(name, "eu.wohlben.qits:" + name);
    }
    Fixture.scriptCiQueue(peers, 2);

    assertEquals(2, dispatcher.explain(Instant.now()).free());
    assertEquals(List.of("qits-r1", "qits-r2"), repositoriesOf(dispatcher.tick()));
    assertEquals(0, asks("catalog-qits-r3"));
    assertEquals(0, asks("catalog-qits-r4"));
    assertEquals(0, asks("catalog-qits-r5"));
  }

  /** The cap fully used: CI_BUSY, saying how it counted, and nothing opened. */
  @Test
  void theCapFullyUsedIsBusyAndOpensNothing() {
    Fixture.scriptCiQueue(peers, 4);

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_BUSY", decision.outcome());
    assertEquals(
        "qits-ci's queue is at its cap of 4 (4 active run(s) and 0 bump(s) waiting to reach it);"
            + " nothing is free",
        decision.summary());
    assertTrue(dispatcher.tick().isEmpty());
  }

  // A cap of zero, even an idle queue, is NO_SLOTS — see BumpDispatchCapZeroTest, a separate
  // @QuarkusTest because the cap is read once at config time and this suite runs with the default.

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

  // --- qits-1696: a knot beside READY work, and the door that shows it ---------------------------

  /**
   * Two repositories that each carry the other as a submodule — a knot — and one READY repository
   * beside them: the shape of 2026-10-28, where thirty READY bumps kept a cycle from ever being
   * broken.
   */
  private void aKnotBesideReadyWork() {
    inventory.clear();
    owesItsSubmodule("qits-knot-a", "qits-knot-b");
    owesItsSubmodule("qits-knot-b", "qits-knot-a");
    owes("qits-ready-c", "eu.wohlben.qits:qits-c");
    Fixture.scriptCiQueueEmpty(peers);
  }

  /**
   * <b>THE KNOT IS BROKEN IN THE SAME TICK AS THE READY WORK (qits-1696)</b> — after it, once, and
   * only because the cap left room.
   */
  @Test
  void aKnotIsBrokenInTheSameTickAsReadyWork() {
    aKnotBesideReadyWork();

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("DISPATCH", decision.outcome());
    assertEquals(
        "the next 2 bumps are qits-ready-c, qits-knot-a (breaking a cycle, it waits on"
            + " qits-knot-b) (4 of 4 free)",
        decision.summary());

    assertEquals(List.of("qits-ready-c", "qits-knot-a"), repositoriesOf(dispatcher.tick()));
    assertEquals(0, asks("catalog-qits-knot-b"), "one break per tick");
  }

  /**
   * <b>{@code GET /bumps/standing} SHOWS THE DECISION AND THE WHOLE OWED SET, AND SENDS NOTHING</b>
   * (qits-1696). Read as {@code qits:agent}, the role an agent investigating a stuck bump holds; the
   * tick after the read still opens exactly what the read said it would.
   */
  @Test
  void theStandingDoorShowsTheDecisionAndTheOwedSetAndSendsNothing() {
    aKnotBesideReadyWork();

    ValidatableResponse standing =
        given()
            .header("X-Qits-User", "dyn-workspace-agent")
            .header("X-Qits-Roles", "qits:agent")
            .get("/maintenance/api/bumps/standing")
            .then()
            .statusCode(200);

    standing
        .body("outcome", is("DISPATCH"))
        .body("cap", is(4))
        .body("free", is(4))
        .body("ciActive", is(0))
        .body("owed", is(3))
        .body("held", is(0))
        .body("picks.repository", contains("qits-ready-c", "qits-knot-a"))
        .body("picks.cycleBroken", contains(false, true))
        .body("picks[1].blockedBy", contains("qits-knot-b"))
        .body(
            "standing.repository",
            contains("qits-ready-c", "qits-knot-a", "qits-knot-b"))
        .body("standing.reason", contains("READY", "BLOCKED", "BLOCKED"))
        .body("standing[1].blockedBy", contains("qits-knot-b"))
        .body("standing[0].changes[0].ecosystem", is("maven"))
        .body("standing[0].changes[0].name", is("eu.wohlben.qits:qits-c"))
        .body("standing[0].changes[0].from", is("2026.901.1"))
        .body("standing[0].changes[0].to", is("2026.913.1"))
        .body("standing[1].changes[0].ecosystem", is("gitlink"))
        .body("standing[1].changes[0].name", is("qits-knot-b"))
        .body("refused", empty());

    assertEquals(0, asks("catalog-qits-ready-c"), "a read opens nothing");
    assertEquals(0, asks("catalog-qits-knot-a"));
    assertEquals(0, asks("catalog-qits-knot-b"));
    assertEquals(List.of("qits-ready-c", "qits-knot-a"), repositoriesOf(dispatcher.tick()));
  }

  /**
   * <b>A REPOSITORY OWED A RE-OFFER GOES FIRST</b> (qits-1578). Its dependency bump was stranded on
   * a request that closed, so the dispatcher reached it recently — least-recently-dispatched alone
   * would put it behind one reached a fortnight ago, and under a cap of one it would wait a whole
   * tick or more. Marked, it goes first; the main-only request it is opened clears the mark, and
   * the order falls back to the clock.
   */
  @Test
  void aRepositoryOwedAReofferGoesBeforeAnOlderDispatchedOneAndItsMarkIsCleared() {
    inventory.clear();
    owes("qits-aaa-service", "eu.wohlben.qits:qits-agents-early");
    owes("qits-zzz-service", "eu.wohlben.qits:qits-agents-late");
    lastReached("qits-aaa-service", Instant.now().minus(Duration.ofDays(14)));
    lastReached("qits-zzz-service", Instant.now().minus(Duration.ofMinutes(5)));
    store.markReofferOwed("qits-zzz-service", Instant.now());
    // A cap of four with three active: one free.
    Fixture.scriptCiQueue(peers, 3);

    assertEquals(1, dispatcher.explain(Instant.now()).free());
    assertEquals(
        List.of("qits-zzz-service", "qits-aaa-service"),
        dispatcher.assess().candidates().stream().map(BumpOrder.Candidate::repository).toList(),
        "the marked one ahead of the one reached longest ago");
    assertEquals(List.of("qits-zzz-service"), repositoriesOf(dispatcher.tick()));
    assertEquals(0, asks("catalog-qits-aaa-service"), "it waits for the next tick");
    assertNull(
        store.repository("qits-zzz-service").orElseThrow().reofferOwedAt,
        "the main-only request is the re-offer; the mark is cleared");
  }

  /** The mark only reorders: a marked repository with an open request is still held. */
  @Test
  void aRepositoryOwedAReofferIsStillHeldByAnOpenRequest() {
    Fixture.scriptCiQueueEmpty(peers);
    scriptListing(
        Fixture.CATALOG_ID,
        Fixture.REPOSITORY,
        "[{\"id\":\"open-one\",\"state\":\"PENDING\",\"mergedSha\":null}]");
    store.markReofferOwed(Fixture.REPOSITORY, Instant.now());

    assertTrue(dispatcher.assess().candidates().getFirst().held());
    assertTrue(dispatcher.tick().isEmpty());
    assertNotNull(
        store.repository(Fixture.REPOSITORY).orElseThrow().reofferOwedAt,
        "nothing was opened, so the mark stays");
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
    scanned(repository, catalogId, pin, RepositoryStatus.OK);
  }

  /** The same row under a status other than OK — what the dispatcher's status gate reads. */
  private void scanned(
      String repository, String catalogId, ParsedPin pin, RepositoryStatus status) {
    store.replaceInventory(
        repository,
        Fixture.PROJECT,
        catalogId,
        null,
        "main",
        status,
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

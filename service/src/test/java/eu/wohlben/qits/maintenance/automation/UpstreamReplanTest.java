package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.CURRENT_POM;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.EVENTSTREAM;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_B;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.PUSHED;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.STALE_POM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
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
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Upstream publication</b> (qits-1133): the "latest moved" hook re-planning an open request's
 * dependency bump, the starvation guard, the main-only LOWEST request the dispatcher opens where no
 * request is open, and its withdrawal when its pre-run finds nothing. Always on since qits-1133 R5
 * removed {@code qits.maintenance.pre-run.upstream.enabled}; the one switch left is the {@code
 * dependency-bump} kind's own, whose off arm {@code DependencyBumpSwitchedOffTest} pins.
 */
@QuarkusTest
@TestProfile(DependencyBumpOn.class)
class UpstreamReplanTest {

  private static final String RUN = "run-upstream";

  /** The main-only request the dispatcher opens. */
  private static final String MAIN_REQUEST = "8e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b";

  @Inject UpstreamReplan upstream;

  @Inject AutomationService automations;

  @Inject BumpDispatcher dispatcher;

  @Inject BumpService bumps;

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
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, RUN);
    // Who publishes each internal pin: a bump's changelog ranges need a source repository (qits-893).
    Fixture.seedProducers(store);
    AutomationFixture.scriptFold(peers, FOLD_A, true);
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    Fixture.scriptForeignBranchAt(
        peers,
        AutomationFixture.branch(DependencyBumpAutomation.KIND, REQUEST),
        AutomationFixture.BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  @AfterEach
  void drain() {
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  /** The repository's open-request listing: one request in this state with this CI gate. */
  private void listing(String state, String ciGate) {
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.ok(
            "{\"requests\":[{\"id\":\"" + REQUEST + "\",\"state\":\"" + state + "\",\"mergedSha\":\""
                + FOLD_A + "\",\"gates\":[{\"kind\":\"CI\",\"state\":\"" + ciGate
                + "\",\"detail\":null}]}]}"));
  }

  /** What qits-projects answers for the main-only request: PENDING at FOLD_A with these sources. */
  private void scriptMainRequestSources(String sources) {
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + MAIN_REQUEST,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"PENDING\",\"mergedSha\":\""
                + FOLD_A + "\",\"sources\":[" + sources + "]}}"));
  }

  private List<MtBump> bumpRows(String requestId) {
    return store.automationHistory(requestId, DependencyBumpAutomation.KIND);
  }

  private void latestMoved() {
    upstream.latestMoved(Ecosystem.MAVEN, EVENTSTREAM);
    // Twice: the re-plan runs on the worker and queues its own dispatch behind the first barrier.
    queue.awaitIdle(Duration.ofSeconds(30));
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  // --- an open request ----------------------------------------------------------------------

  /**
   * An open request that is not READY has its bump re-planned on its current fold, and the restart
   * is counted against the starvation guard.
   */
  @Test
  void anOpenRequestHasItsBumpReplannedOnItsFold() {
    listing("PENDING", "PENDING");

    latestMoved();

    List<MtBump> rows = bumpRows(REQUEST);
    assertEquals(1, rows.size(), rows.toString());
    MtBump row = rows.getFirst();
    assertEquals(BumpTrigger.UPSTREAM.name(), row.trigger);
    assertEquals(FOLD_A, row.foldSha);
    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals(1, store.upstreamRestarts(REQUEST));
  }

  /** A READY request has its verdict; an upstream release does not restart it (Q2). */
  @Test
  void aReadyRequestIsLeftAlone() {
    listing("READY", "PASSED");

    latestMoved();

    assertTrue(bumpRows(REQUEST).isEmpty());
  }

  /** A fold that already carries the upstream release opens nothing and counts nothing. */
  @Test
  void aFreshPlanOpensNothing() {
    AutomationFixture.scriptManifests(peers, FOLD_A, CURRENT_POM, null);
    listing("PENDING", "PENDING");

    latestMoved();

    assertTrue(bumpRows(REQUEST).isEmpty());
    assertEquals(0, store.upstreamRestarts(REQUEST));
  }

  /**
   * <b>The starvation guard.</b> Three upstream restarts without a QA verdict and the request is left
   * alone; its CI gate reaching a verdict resets the count and the next release re-plans it again.
   */
  @Test
  void threeRestartsWithoutAVerdictStopTheReplanning() {
    for (int restart = 0; restart < UpstreamReplan.STARVATION_RESTARTS; restart++) {
      store.recordUpstreamRestart(REQUEST, Fixture.REPOSITORY, Instant.now());
    }
    listing("PENDING", "PENDING");

    latestMoved();
    assertTrue(bumpRows(REQUEST).isEmpty(), "starved: left alone until it has a verdict");

    listing("PENDING", "FAILED");
    latestMoved();
    assertEquals(1, bumpRows(REQUEST).size(), "a verdict re-arms it");
    assertEquals(1, store.upstreamRestarts(REQUEST), "counted from zero again");
  }

  // --- no open request: the dispatcher's main-only request -------------------------------------

  /**
   * With no open request, the dispatcher opens a MAIN-ONLY LOWEST request where it would have cut a
   * group branch — no {@code mt_bump} row, no branch — remembers it as its own, and holds the
   * repository while it is on its way.
   */
  @Test
  void theDispatcherOpensAMainOnlyLowestRequestInsteadOfAGroupBranch() {
    Fixture.scriptCiQueueEmpty(peers);
    // One body for both routes on the collection: the listing reads `requests` (none open), the ask
    // reads `request`.
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.ok(
            "{\"requests\":[],\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"PENDING\"}}"));
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + MAIN_REQUEST,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"PENDING\"}}"));

    List<UUID> sent = dispatcher.tick();
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(List.of(UUID.fromString(MAIN_REQUEST)), sent);
    String ask =
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).stream()
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no release ask was posted"));
    assertTrue(ask.contains("\"branch\":\"main\""), ask);
    assertTrue(ask.contains("\"priority\":\"LOWEST\""), ask);
    MtReleaseRequest opened =
        store.newestOpenedRequest(Fixture.REPOSITORY, MtReleaseRequest.MAIN_ONLY).orElseThrow();
    assertEquals(MAIN_REQUEST, opened.requestId);
    assertEquals("main", opened.branch);
    assertTrue(store.openedByMaintenance(MAIN_REQUEST));
    assertTrue(
        store.bumps(Fixture.REPOSITORY, 50).stream()
            .noneMatch(row -> BumpMode.GROUP.name().equals(row.mode)),
        "no maintenance/<group> branch was asked for");

    assertTrue(dispatcher.tick().isEmpty(), "held while its request is on its way");
  }

  /**
   * The main-only request's pre-run finds the fold already current: there is nothing to release, so
   * this service withdraws it — and the DERIVED kinds are not run on a request that is gone.
   */
  @Test
  void aMainOnlyRequestWhosePreRunFindsNothingIsWithdrawn() {
    store.recordOpenedRequest(
        MAIN_REQUEST, Fixture.REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, List.of(),
        Instant.now());
    AutomationFixture.scriptManifests(peers, FOLD_A, CURRENT_POM, null);
    scriptMainRequestSources("{\"kind\":\"BRANCH\",\"name\":\"main\"}");
    String withdraw = Fixture.RELEASE_REQUESTS_PATH + "/" + MAIN_REQUEST + "/withdraw";
    peers.answer(
        PeerTarget.PROJECTS,
        withdraw,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"WITHDRAWN\"}}"));

    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            MAIN_REQUEST,
            new AutomationService.Fold(
                Fixture.REPOSITORY, FOLD_A, null, null, List.of("main"), null,
                List.of("WAITING", "NOT_APPLICABLE")));
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(
        AutomationState.FRESH.name(),
        AutomationFixture.entry(answer, DependencyBumpAutomation.KIND).state());
    assertEquals(1, peers.bodiesFor(withdraw).size());
    assertTrue(peers.bodiesFor(withdraw).getFirst().contains("nothing to bump"));
    assertNotNull(store.releaseRequest(MAIN_REQUEST).orElseThrow().withdrawnAt);
    AutomationDto screenshots =
        AutomationFixture.entry(answer, ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.WAITING.name(), screenshots.state());
    assertTrue(screenshots.reason().contains("withdrawn"), screenshots.reason());
  }

  /**
   * <b>A request that carries more than main is never withdrawn</b> (qits-1166): qits-projects
   * folded a release tag into the request this service opened for main, so its pre-run finding
   * nothing to bump leaves the tag still to reach main — the request is kept.
   */
  @Test
  void aMainOnlyRequestThatAlsoCarriesATagIsKept() {
    store.recordOpenedRequest(
        MAIN_REQUEST, Fixture.REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, List.of(),
        Instant.now());
    AutomationFixture.scriptManifests(peers, FOLD_A, CURRENT_POM, null);
    scriptMainRequestSources(
        "{\"kind\":\"BRANCH\",\"name\":\"main\"},"
            + "{\"kind\":\"TAG\",\"name\":\"2026.1010.172542\"}");
    String withdraw = Fixture.RELEASE_REQUESTS_PATH + "/" + MAIN_REQUEST + "/withdraw";
    peers.answer(
        PeerTarget.PROJECTS,
        withdraw,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"WITHDRAWN\"}}"));

    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            MAIN_REQUEST,
            new AutomationService.Fold(
                Fixture.REPOSITORY, FOLD_A, null, null, List.of("main"), null,
                List.of("WAITING", "NOT_APPLICABLE")));
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(
        AutomationState.FRESH.name(),
        AutomationFixture.entry(answer, DependencyBumpAutomation.KIND).state());
    assertTrue(peers.bodiesFor(withdraw).isEmpty(), "never asked to withdraw");
    assertTrue(
        store.releaseRequest(MAIN_REQUEST).orElseThrow().withdrawnAt == null, "and is kept");
    AutomationDto screenshots =
        AutomationFixture.entry(answer, ScreenshotBaselinesAutomation.KIND);
    assertFalse(
        screenshots.reason() != null && screenshots.reason().contains("withdrawn"),
        String.valueOf(screenshots.reason()));
  }

  /**
   * A main-only request whose fold is behind only on an EXTERNAL pin has nothing to release: the
   * bump never moves one (qits-1164), so its pre-run withdraws it like any current fold.
   */
  @Test
  void aMainOnlyRequestBehindOnlyExternallyIsWithdrawn() {
    store.recordOpenedRequest(
        MAIN_REQUEST, Fixture.REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, List.of(),
        Instant.now());
    AutomationFixture.scriptManifests(
        peers, FOLD_A, STALE_POM.replace("2026.811.1", "2026.821.3"), null);
    scriptMainRequestSources("{\"kind\":\"BRANCH\",\"name\":\"main\"}");
    String withdraw = Fixture.RELEASE_REQUESTS_PATH + "/" + MAIN_REQUEST + "/withdraw";
    peers.answer(
        PeerTarget.PROJECTS,
        withdraw,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"WITHDRAWN\"}}"));

    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            MAIN_REQUEST,
            new AutomationService.Fold(
                Fixture.REPOSITORY, FOLD_A, null, null, List.of("main"), null,
                List.of("WAITING", "NOT_APPLICABLE")));
    queue.awaitIdle(Duration.ofSeconds(30));

    AutomationDto bump = AutomationFixture.entry(answer, DependencyBumpAutomation.KIND);
    assertEquals(AutomationState.FRESH.name(), bump.state(), bump.detail());
    assertEquals(1, peers.bodiesFor(withdraw).size());
    assertNotNull(store.releaseRequest(MAIN_REQUEST).orElseThrow().withdrawnAt);
  }

  /**
   * A main-only request whose pre-run DOES find a bump is the request that carries it — the
   * internal upgrade only, as every request (qits-1164).
   */
  @Test
  void aMainOnlyRequestWithABumpToWriteIsKept() {
    store.recordOpenedRequest(
        MAIN_REQUEST, Fixture.REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, List.of(),
        Instant.now());
    Fixture.scriptForeignBranchAt(
        peers,
        AutomationFixture.branch(DependencyBumpAutomation.KIND, MAIN_REQUEST),
        AutomationFixture.BEFORE);

    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            MAIN_REQUEST,
            new AutomationService.Fold(
                Fixture.REPOSITORY, FOLD_A, null, null, List.of("main"), null, List.of()));
    queue.awaitIdle(Duration.ofSeconds(30));

    AutomationDto bump = AutomationFixture.entry(answer, DependencyBumpAutomation.KIND);
    assertEquals(AutomationState.REQUESTED.name(), bump.state(), bump.detail());
    assertEquals(
        List.of(EVENTSTREAM),
        eu.wohlben.qits.maintenance.bump.BumpService.changes(
                store.bump(UUID.fromString(bump.bumpId())).orElseThrow())
            .stream()
            .map(eu.wohlben.qits.maintenance.pending.Change::name)
            .toList(),
        "a main-only request carries no external upgrade");
    assertTrue(
        store.releaseRequest(MAIN_REQUEST).orElseThrow().withdrawnAt == null, "and is kept");
  }

  /**
   * <b>A request whose bump COMMITTED is never withdrawn as nothing to bump</b> (qits-1226). The
   * join is what makes qits-projects re-fold onto the bump's commit, and it asks for that fold's
   * pre-run before the join returns: the fold already carries the bump, so its pre-run is FRESH.
   * The committing row must already read SUCCEEDED by then — a row still RUNNING counted for
   * nothing, and qits-workspace-oci-rr-59..66 were withdrawn carrying the pin they were opened for.
   */
  @Test
  void aMainOnlyRequestWhoseBumpCommittedIsKeptThroughItsOwnRefold() {
    store.recordOpenedRequest(
        MAIN_REQUEST, Fixture.REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, List.of(),
        Instant.now());
    String branch = AutomationFixture.branch(DependencyBumpAutomation.KIND, MAIN_REQUEST);
    Fixture.scriptForeignBranchAt(peers, branch, AutomationFixture.BEFORE);
    scriptMainRequestSources(
        "{\"kind\":\"BRANCH\",\"name\":\"main\"},"
            + "{\"kind\":\"BRANCH\",\"name\":\"" + branch + "\"}");
    String withdraw = Fixture.RELEASE_REQUESTS_PATH + "/" + MAIN_REQUEST + "/withdraw";
    peers.answer(
        PeerTarget.PROJECTS,
        withdraw,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"WITHDRAWN\"}}"));
    AutomationDto planned =
        AutomationFixture.entry(
            automations.trigger(
                MAIN_REQUEST,
                new AutomationService.Fold(
                    Fixture.REPOSITORY, FOLD_A, null, null, List.of("main"), null, List.of())),
            DependencyBumpAutomation.KIND);
    queue.awaitIdle(Duration.ofSeconds(30));
    UUID id = UUID.fromString(planned.bumpId());
    assertEquals(BumpStatus.RUNNING.name(), store.bump(id).orElseThrow().status);

    // The run pushes its commit; the fold that commit makes is current, and qits-projects asks for
    // its pre-run INSIDE the join.
    Fixture.scriptForeignBranchAt(peers, branch, PUSHED);
    AutomationFixture.scriptManifests(peers, FOLD_B, CURRENT_POM, null);
    ReleaseRequestAutomationsDto[] refold = new ReleaseRequestAutomationsDto[1];
    AutomationFixture.scriptJoin(peers, MAIN_REQUEST, AutomationFixture.joined(MAIN_REQUEST));
    peers.onCall(
        PeerTarget.PROJECTS,
        AutomationFixture.joinPath(MAIN_REQUEST),
        () ->
            refold[0] =
                automations.trigger(
                    MAIN_REQUEST,
                    new AutomationService.Fold(
                        Fixture.REPOSITORY, FOLD_B, FOLD_A, List.of("pom.xml"),
                        List.of("main", branch), null,
                        List.of("WAITING", "NOT_APPLICABLE"))));
    Fixture.scriptRun(peers, RUN, "SUCCESS");

    bumps.poll(id);
    queue.awaitIdle(Duration.ofSeconds(30));

    assertNotNull(refold[0], "the join asked for the re-fold's pre-run");
    AutomationDto fresh = AutomationFixture.entry(refold[0], DependencyBumpAutomation.KIND);
    assertEquals(AutomationState.FRESH.name(), fresh.state(), fresh.detail());
    MtBump done = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.SUCCEEDED.name(), done.status, done.message);
    assertEquals(PUSHED, done.resultSha);
    assertTrue(done.message.contains("joined to release request"), done.message);
    assertTrue(peers.bodiesFor(withdraw).isEmpty(), "never asked to withdraw");
    assertTrue(
        store.releaseRequest(MAIN_REQUEST).orElseThrow().withdrawnAt == null, "and is kept");
  }

  /**
   * <b>A head that could not be read is not "nothing to bump"</b> (qits-206). It used to end the
   * green run FRESH, and a main-only request whose bump had in fact been pushed was withdrawn as
   * carrying nothing. The row stays RUNNING and nothing is withdrawn; the next poll, with the head
   * readable, finds the push and joins it.
   */
  @Test
  void aMainOnlyRequestIsNotWithdrawnOnAnUnreadableHead() {
    store.recordOpenedRequest(
        MAIN_REQUEST, Fixture.REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, List.of(),
        Instant.now());
    String branch = AutomationFixture.branch(DependencyBumpAutomation.KIND, MAIN_REQUEST);
    Fixture.scriptForeignBranchAt(peers, branch, AutomationFixture.BEFORE);
    scriptMainRequestSources("{\"kind\":\"BRANCH\",\"name\":\"main\"}");
    String withdraw = Fixture.RELEASE_REQUESTS_PATH + "/" + MAIN_REQUEST + "/withdraw";
    peers.answer(
        PeerTarget.PROJECTS,
        withdraw,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"WITHDRAWN\"}}"));
    AutomationDto planned =
        AutomationFixture.entry(
            automations.trigger(
                MAIN_REQUEST,
                new AutomationService.Fold(
                    Fixture.REPOSITORY, FOLD_A, null, null, List.of("main"), null, List.of())),
            DependencyBumpAutomation.KIND);
    queue.awaitIdle(Duration.ofSeconds(30));
    UUID id = UUID.fromString(planned.bumpId());
    assertEquals(BumpStatus.RUNNING.name(), store.bump(id).orElseThrow().status);

    // The run pushes, and the git host fails the one read that would have seen it.
    Fixture.scriptForeignBranchUnreachable(peers, branch);
    Fixture.scriptRun(peers, RUN, "SUCCESS");
    bumps.poll(id);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump waiting = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), waiting.status, waiting.message);
    assertTrue(peers.bodiesFor(withdraw).isEmpty(), "never asked to withdraw");
    assertTrue(
        store.releaseRequest(MAIN_REQUEST).orElseThrow().withdrawnAt == null, "and is kept");
    assertTrue(peers.bodiesFor(AutomationFixture.joinPath(MAIN_REQUEST)).isEmpty());

    Fixture.scriptForeignBranchAt(peers, branch, PUSHED);
    AutomationFixture.scriptJoin(peers, MAIN_REQUEST, AutomationFixture.joined(MAIN_REQUEST));
    bumps.poll(id);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump done = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.SUCCEEDED.name(), done.status, done.message);
    assertEquals(PUSHED, done.resultSha);
    assertEquals(1, peers.bodiesFor(AutomationFixture.joinPath(MAIN_REQUEST)).size());
    assertTrue(peers.bodiesFor(withdraw).isEmpty(), "still never asked to withdraw");
  }

  // --- the race: a request that releases while its re-planned bump waits (qits-1578) -----------

  /**
   * <b>Reproduction (qits-1578, qits-system-service-rr-67, run 934e4822).</b> An upstream release
   * re-plans the bump onto the consumer's open request while the automation slots are full; the
   * request releases before a slot frees, so the row that finally reaches the front would run onto
   * a fold branch that is gone. It ends SUPERSEDED with no CI run, and once the request finalized
   * with main still on the old pin, the dispatcher's next tick re-offers the bump against main —
   * FIRST, ahead of a repository it reached a fortnight ago, under a cap of one.
   */
  @Test
  void aBumpStrandedOnARequestThatReleasedWhileItWaitedIsReofferedAgainstMainFirst() {
    // 1. The consumer, pinned at 2026.811.1 on main, has an open main-only request at FOLD_A.
    store.recordOpenedRequest(
        REQUEST, Fixture.REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, List.of(),
        Instant.now());
    listing("PENDING", "PENDING");
    List<UUID> busy = new java.util.ArrayList<>();
    for (String other :
        List.of(
            "6e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b", "7e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b")) {
      busy.add(
          store.openAutomation(
              new MaintenanceStore.AutomationOpening(
                  Fixture.REPOSITORY,
                  ScreenshotBaselinesAutomation.KIND,
                  other,
                  FOLD_B,
                  null,
                  false,
                  AutomationFixture.branch(other),
                  null,
                  "test",
                  BumpTrigger.MANUAL,
                  List.of(),
                  Map.of(),
                  BumpStatus.RUNNING,
                  null),
              false,
              Instant.now()));
    }
    assertEquals(AutomationService.MAX_RUNNING, store.runningAutomationCount(), "the slots are full");

    // 2. The upstream is at 2026.821.3: its consumers are re-planned, and the row waits.
    upstream.replanConsumers(Ecosystem.MAVEN, EVENTSTREAM);
    queue.awaitIdle(Duration.ofSeconds(30));
    queue.awaitIdle(Duration.ofSeconds(30));
    List<MtBump> rows = bumpRows(REQUEST);
    assertEquals(1, rows.size(), rows.toString());
    UUID id = rows.getFirst().id;
    assertEquals(BumpStatus.REQUESTED.name(), rows.getFirst().status, rows.getFirst().message);
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty(), "waiting behind the cap");

    // 3. The request releases; then a slot frees and the waiting rows are dispatched.
    AutomationFixture.scriptRequest(peers, REQUEST, "RELEASED", FOLD_A);
    for (UUID running : busy) {
      store.bumpFinished(running, BumpStatus.NOTHING_TO_DO, "SUCCESS", "unchanged", Instant.now());
    }
    automations.dispatchWaiting();
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump stranded = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.SUPERSEDED.name(), stranded.status, stranded.message);
    assertTrue(stranded.message.contains("is RELEASED and takes no commit"), stranded.message);
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty(), "never sent onto a gone fold");
    assertNotNull(store.repository(Fixture.REPOSITORY).orElseThrow().reofferOwedAt);

    // 4. The request finalizes with main still on 2026.811.1. Another repository is owed too, and
    // was reached a fortnight ago — the clock alone would send it first.
    AutomationFixture.scriptRequest(peers, REQUEST, "FINALIZED", FOLD_A);
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.ok(
            "{\"requests\":[{\"id\":\"" + REQUEST + "\",\"state\":\"FINALIZED\","
                + "\"mergedSha\":\"" + FOLD_A + "\"}],"
                + "\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"PENDING\"}}"));
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + MAIN_REQUEST,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + MAIN_REQUEST + "\",\"state\":\"PENDING\"}}"));
    String older = "qits-older-service";
    String olderCollection =
        ReleaseRequestClient.REQUESTS_PATH_PREFIX + "catalog-" + older
            + ReleaseRequestClient.REQUESTS_PATH_SUFFIX;
    store.replaceInventory(
        older,
        Fixture.PROJECT,
        "catalog-" + older,
        null,
        "main",
        RepositoryStatus.OK,
        "sha-" + older,
        null,
        List.of(
            ParsedPin.of(
                Ecosystem.MAVEN, "pom.xml", EVENTSTREAM, "2026.811.1", null,
                "dependency:" + EVENTSTREAM)),
        List.of(GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    peers.answer(
        PeerTarget.PROJECTS,
        olderCollection,
        FakePeers.Scripted.ok(
            "{\"requests\":[],\"request\":{\"id\":\"9e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b\","
                + "\"state\":\"PENDING\"}}"));
    store.recordOpenedRequest(
        "reached-" + older, older, "main", MtReleaseRequest.MAIN_ONLY, List.of(),
        Instant.now().minus(Duration.ofDays(14)));
    // A cap of four with three active: one free.
    Fixture.scriptCiQueue(peers, 3);

    List<UUID> sent = dispatcher.tick();
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(List.of(UUID.fromString(MAIN_REQUEST)), sent, "the stranded consumer goes first");
    MtReleaseRequest reoffer = store.releaseRequest(MAIN_REQUEST).orElseThrow();
    assertEquals(Fixture.REPOSITORY, reoffer.repository);
    assertEquals(MtReleaseRequest.MAIN_ONLY, reoffer.purpose);
    assertEquals("main", reoffer.branch);
    assertTrue(reoffer.changes.contains(EVENTSTREAM), reoffer.changes);
    assertTrue(reoffer.changes.contains("2026.821.3"), "carrying the newer upstream: " + reoffer.changes);
    assertTrue(
        peers.bodiesFor(olderCollection).stream().noneMatch(java.util.Objects::nonNull),
        "the older one waits for the next tick");
    assertNull(store.repository(Fixture.REPOSITORY).orElseThrow().reofferOwedAt, "mark cleared");
    assertEquals(
        List.of(id), bumpRows(REQUEST).stream().map(row -> row.id).toList(),
        "nothing more was ever opened onto the closed request");
  }
}

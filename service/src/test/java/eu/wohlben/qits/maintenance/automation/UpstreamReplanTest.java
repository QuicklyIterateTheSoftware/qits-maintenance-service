package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.CURRENT_POM;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.EVENTSTREAM;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.STALE_POM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.config.UpstreamSwitch;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Upstream publication</b> (qits-1133): the "latest moved" hook re-planning an open request's
 * dependency bump, the starvation guard, the main-only LOWEST request the dispatcher opens where no
 * request is open, and its withdrawal when its pre-run finds nothing — all behind {@code
 * qits.maintenance.pre-run.upstream.enabled}, which ships on since the cutover (R2); every method
 * still switches it explicitly, so the off arm is pinned too.
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

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject MaintenanceConfig config;

  private MaintenanceConfig realConfig;

  @BeforeEach
  void scriptThePeers() {
    realConfig = ClientProxy.unwrap(config);
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
    dispatcher.close("a fresh test");
  }

  /** The real config back, whatever the method switched: a mock lives for the whole run. */
  @AfterEach
  void restoreTheConfig() {
    queue.awaitIdle(Duration.ofSeconds(30));
    QuarkusMock.installMockForType(realConfig, MaintenanceConfig.class);
  }

  private void switchOn() {
    QuarkusMock.installMockForType(new UpstreamSwitch(realConfig), MaintenanceConfig.class);
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

  private List<MtBump> bumpRows(String requestId) {
    return store.automationHistory(requestId, DependencyBumpAutomation.KIND);
  }

  private void latestMoved() {
    upstream.latestMoved(Ecosystem.MAVEN, EVENTSTREAM);
    // Twice: the re-plan runs on the worker and queues its own dispatch behind the first barrier.
    queue.awaitIdle(Duration.ofSeconds(30));
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  // --- the switch -------------------------------------------------------------------------------

  /** Off — the shipped default — the hook asks nobody anything and opens nothing. */
  @Test
  void withTheSwitchOffNothingIsReplanned() {
    // Shipped ON since the cutover (qits-1133 R2); off is the emergency position.
    UpstreamSwitch.install(config, false);
    listing("PENDING", "PENDING");

    latestMoved();
    upstream.replanConsumers(Ecosystem.MAVEN, EVENTSTREAM);

    assertFalse(
        peers.called(PeerTarget.PROJECTS, Fixture.RELEASE_REQUESTS_PATH), "no listing was read");
    assertTrue(bumpRows(REQUEST).isEmpty());
  }

  // --- an open request ----------------------------------------------------------------------

  /**
   * An open request that is not READY has its bump re-planned on its current fold, and the restart
   * is counted against the starvation guard.
   */
  @Test
  void anOpenRequestHasItsBumpReplannedOnItsFold() {
    switchOn();
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
    switchOn();
    listing("READY", "PASSED");

    latestMoved();

    assertTrue(bumpRows(REQUEST).isEmpty());
  }

  /** A fold that already carries the upstream release opens nothing and counts nothing. */
  @Test
  void aFreshPlanOpensNothing() {
    switchOn();
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
    switchOn();
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
    switchOn();
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
   * A main-only request whose pre-run DOES find a bump is the request that carries it — the one
   * origin, with the switch on, that plans external upgrades too.
   */
  @Test
  void aMainOnlyRequestWithABumpToWriteIsKept() {
    switchOn();
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
        List.of(EVENTSTREAM, AutomationFixture.QUARKUS_BOM),
        eu.wohlben.qits.maintenance.bump.BumpService.changes(
                store.bump(UUID.fromString(bump.bumpId())).orElseThrow())
            .stream()
            .map(eu.wohlben.qits.maintenance.pending.Change::name)
            .toList(),
        "a main-only request with the switch on carries external upgrades too");
    assertTrue(
        store.releaseRequest(MAIN_REQUEST).orElseThrow().withdrawnAt == null, "and is kept");
  }
}

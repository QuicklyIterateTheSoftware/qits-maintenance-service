package eu.wohlben.qits.maintenance.bump;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.config.UpstreamSwitch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.error.GroupBumpsRetiredException;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The cutover (qits-1133 R2)</b>: with {@code qits.maintenance.pre-run.upstream.enabled} on —
 * the shipped default — nothing writes a {@code maintenance/<group>} branch. No path sends a group
 * bump to qits-ci (the door answers 410, the service refuses, a row left REQUESTED is closed
 * unsent, the dispatcher opens no group bump), a run already going ends without a release ask, and
 * the legacy sweep withdraws the bump-only requests standing on group branches, leaves a person's
 * open, deletes the branches, retires their rows — and does nothing at all the second time.
 */
@QuarkusTest
class GroupCutoverTest {

  private static final String BASE = "/maintenance/api";

  private static final String RUN = "run-cutover";

  /** A request carrying only main, a group branch and a released tag: bump-only. */
  private static final String BUMP_ONLY = "0b6c3f2e-5d1a-4c8e-9f70-2a4b6c8d0e1f";

  /** A person's request a group branch was joined to. */
  private static final String PERSONS = "7d2e9a14-3b5c-4f6d-8e7a-9b0c1d2e3f4a";

  /** A request that already ended, naming a group branch: never asked about. */
  private static final String FINALIZED = "c3d4e5f6-0718-4293-a4b5-c6d7e8f90a1b";

  private static final String DEPENDENCIES = "maintenance/dependencies";

  private static final String EXTERNAL = "maintenance/external";

  private static final String AUTOMATION_BRANCH =
      "maintenance/automations/dependency-bump/" + BUMP_ONLY;

  private static final String DESCRIBE = "/githost/api/repositories/" + Fixture.CATALOG_ID;

  @Inject BumpService bumps;

  @Inject BumpDispatcher dispatcher;

  @Inject LegacyGroupBranchSweep sweep;

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject MaintenanceConfig config;

  private MaintenanceConfig realConfig;

  @BeforeEach
  void scriptThePeers() {
    // Explicitly ON, so this class says what it pins whatever ran before it; the shipped default is
    // asserted separately below.
    realConfig = UpstreamSwitch.install(config, true);
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, RUN);
    Fixture.scriptCiQueueEmpty(peers);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
    dispatcher.close("a fresh test");
  }

  @AfterEach
  void restoreTheConfig() {
    queue.awaitIdle(Duration.ofSeconds(30));
    UpstreamSwitch.restore(realConfig);
  }

  // --- the switch -------------------------------------------------------------------------------

  @Test
  void theSwitchShipsOn() {
    assertTrue(realConfig.preRunUpstreamEnabled(), "R2 is the release that turns it on");
  }

  // --- no group trigger from any path -------------------------------------------------------------

  /** The manual door: 410 Gone, pointing at the dependency-bump automation, and nothing sent. */
  @Test
  void theGroupDoorIsGone() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
        .then()
        .statusCode(410)
        .body("message", containsString("dependency-bump"))
        .body("message", containsString("qits-1133"));

    queue.awaitIdle(Duration.ofSeconds(30));
    assertTrue(store.bumps(Fixture.REPOSITORY, 10).isEmpty(), "no bump row was opened");
    assertFalse(triggered(), "nothing was sent to qits-ci");
  }

  /** Every caller of the group path — the door, the ungated night, the dispatcher — meets this. */
  @Test
  void theServiceRefusesAGroupBump() {
    GroupBumpsRetiredException refused =
        assertThrows(
            GroupBumpsRetiredException.class,
            () -> bumps.request(Fixture.REPOSITORY, "dependencies", BumpTrigger.SCHEDULED));
    assertEquals(410, refused.statusCode());
  }

  /** The dispatcher, owed a bump, opens a request instead and never a group bump. */
  @Test
  void theDispatcherCutsNoGroupBranch() {
    peers.answer(
        PeerTarget.PROJECTS, Fixture.RELEASE_REQUESTS_PATH, FakePeers.Scripted.ok("{\"requests\":[]}"));

    for (int tick = 0; tick < 3; tick++) {
      dispatcher.tick();
      queue.awaitIdle(Duration.ofSeconds(30));
    }

    assertTrue(
        store.bumps(Fixture.REPOSITORY, 10).stream()
            .noneMatch(row -> BumpMode.of(row.mode) == BumpMode.GROUP),
        "no group bump row");
    assertFalse(triggered(), "nothing was sent to qits-ci");
  }

  /** A group row the release before left REQUESTED is closed unsent — and for good. */
  @Test
  void aGroupRowLeftRequestedIsClosedWithoutATrigger() {
    UUID id = openGroupBump();

    bumps.dispatch(id);
    bumps.sweep();
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump row = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.NOTHING_TO_DO.name(), row.status, row.message);
    assertEquals(ReleaseRequestClient.CONVERGED, row.releaseRequestId);
    assertTrue(row.message.contains("qits-1133"), row.message);
    assertFalse(triggered(), "nothing was sent to qits-ci");
    assertFalse(releaseAsked(), "nothing asked for a release");
  }

  /**
   * A group run already going at the cutover is followed to its end — its run left before the
   * switch flipped — but what it pushed is never asked to be released: the legacy sweep withdraws
   * and deletes instead.
   */
  @Test
  void aRunningGroupBumpEndsButAsksForNoRelease() {
    UUID id = openGroupBump();
    store.bumpDispatched(id, "e1", List.of(RUN));
    Fixture.scriptRun(peers, RUN, "SUCCESS");
    Fixture.scriptBranchAt(peers, Fixture.BUMPED_SHA);

    bumps.poll(id);
    bumps.sweep();
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump row = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.SUCCEEDED.name(), row.status, row.message);
    assertEquals(ReleaseRequestClient.CONVERGED, row.releaseRequestId);
    assertFalse(releaseAsked(), "nothing asked for a release");
    assertFalse(triggered(), "no trigger was sent by the ending either");
  }

  // --- the legacy sweep ---------------------------------------------------------------------------

  @Test
  void theSweepWithdrawsBumpOnlyRequestsDeletesTheBranchesAndLeavesAPersonsRequestOpen() {
    scriptTheEstate();
    store.recordBranch(
        Fixture.REPOSITORY, "dependencies", DEPENDENCIES, BranchState.PUSHED, Fixture.BUMPED_SHA,
        Instant.now());
    // A row whose branch the release already took: retired without a word to anybody.
    store.recordBranch(
        Fixture.REPOSITORY, "legacy", "maintenance/legacy", BranchState.PUSHED, Fixture.BUMPED_SHA,
        Instant.now());
    UUID owed = openGroupBump();
    store.bumpFinished(owed, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.recordOpenedRequest(
        BUMP_ONLY, Fixture.REPOSITORY, DEPENDENCIES, MtReleaseRequest.GROUP_BUMP, null,
        Instant.now());

    LegacyGroupBranchSweep.Result first = sweep.sweep();

    assertEquals(List.of(Fixture.REPOSITORY + " " + BUMP_ONLY), first.withdrawn());
    List<String> withdrawals = peers.bodiesFor(withdrawPath(BUMP_ONLY));
    assertEquals(1, withdrawals.size());
    assertTrue(withdrawals.getFirst().contains("qits-1133"), withdrawals.getFirst());
    assertFalse(peers.called(PeerTarget.PROJECTS, withdrawPath(PERSONS)), "a person's is left");
    assertFalse(
        peers.called(PeerTarget.PROJECTS, Fixture.RELEASE_REQUESTS_PATH + "/" + FINALIZED),
        "an ended request is not asked about");
    assertEquals(List.of(Fixture.REPOSITORY + " " + PERSONS), first.keptOpen());

    assertTrue(peers.deleted(PeerTarget.GITHOST, deletePath(DEPENDENCIES)));
    assertTrue(peers.deleted(PeerTarget.GITHOST, deletePath(EXTERNAL)));
    assertEquals(
        2, peers.deleteCount(), "only the group branches — never main, a ticket or an automation");
    assertEquals(
        List.of(Fixture.REPOSITORY + " " + DEPENDENCIES, Fixture.REPOSITORY + " " + EXTERNAL),
        first.deleted());

    assertEquals(
        BranchState.RETIRED.name(), store.branch(Fixture.REPOSITORY, "dependencies").orElseThrow().state);
    assertEquals(
        BranchState.RETIRED.name(), store.branch(Fixture.REPOSITORY, "external").orElseThrow().state);
    assertEquals(
        BranchState.RETIRED.name(), store.branch(Fixture.REPOSITORY, "legacy").orElseThrow().state);
    assertEquals(
        ReleaseRequestClient.CONVERGED,
        store.bump(owed).orElseThrow().releaseRequestId,
        "a group bump owed a release ask is closed with its branch");
    assertTrue(
        store.releaseRequest(BUMP_ONLY).orElseThrow().withdrawnAt != null,
        "the withdrawal is remembered on the request this service opened");

    // NOTHING RESURRECTS A RETIRED ROW: a late run ending, or the SCMDeleteBranch the delete caused.
    store.recordBranch(
        Fixture.REPOSITORY, "dependencies", DEPENDENCIES, BranchState.NONE, null, Instant.now());
    store.recordBranch(
        Fixture.REPOSITORY, "dependencies", DEPENDENCIES, BranchState.PUSHED, Fixture.HEAD_SHA,
        Instant.now());
    assertEquals(
        BranchState.RETIRED.name(), store.branch(Fixture.REPOSITORY, "dependencies").orElseThrow().state);

    // THE SECOND PASS: the git host no longer lists them, and nothing at all happens.
    listBranches("\"main\",\"ticket/foo\",\"" + AUTOMATION_BRANCH + "\"");
    LegacyGroupBranchSweep.Result second = sweep.sweep();

    assertTrue(second.idle(), second.toString());
    assertTrue(second.waiting().isEmpty(), second.toString());
    assertEquals(1, peers.bodiesFor(withdrawPath(BUMP_ONLY)).size(), "withdrawn once");
    assertEquals(2, peers.deleteCount(), "deleted once");
  }

  /** A branch a RELEASED request names is mid-pipeline: its landing deletes it. */
  @Test
  void aBranchAReleasedRequestNamesIsLeftToItsLanding() {
    scriptTheEstate();
    scriptRequest(BUMP_ONLY, "RELEASED", "main", DEPENDENCIES);

    LegacyGroupBranchSweep.Result result = sweep.sweep();

    assertFalse(peers.deleted(PeerTarget.GITHOST, deletePath(DEPENDENCIES)));
    assertFalse(peers.called(PeerTarget.PROJECTS, withdrawPath(BUMP_ONLY)));
    assertTrue(result.waiting().stream().anyMatch(line -> line.contains(DEPENDENCIES)), result.toString());
  }

  /** A withdrawal qits-projects did not take keeps the branch: deleting first would re-fold it. */
  @Test
  void aWithdrawalThatWasNotTakenKeepsTheBranchForTheNextPass() {
    scriptTheEstate();
    peers.answer(
        PeerTarget.PROJECTS, withdrawPath(BUMP_ONLY), FakePeers.Scripted.status(503, "busy"));

    LegacyGroupBranchSweep.Result first = sweep.sweep();

    assertFalse(peers.deleted(PeerTarget.GITHOST, deletePath(DEPENDENCIES)));
    assertTrue(first.withdrawn().isEmpty());

    peers.answer(PeerTarget.PROJECTS, withdrawPath(BUMP_ONLY), FakePeers.Scripted.ok("{}"));
    LegacyGroupBranchSweep.Result second = sweep.sweep();

    assertEquals(List.of(Fixture.REPOSITORY + " " + BUMP_ONLY), second.withdrawn());
    assertTrue(peers.deleted(PeerTarget.GITHOST, deletePath(DEPENDENCIES)));
  }

  /** A group branch a run is still writing is left for the pass after the run ends. */
  @Test
  void aBranchWithARunningGroupBumpIsLeftAlone() {
    scriptTheEstate();
    UUID id = openGroupBump();
    store.bumpDispatched(id, "e1", List.of(RUN));

    LegacyGroupBranchSweep.Result result = sweep.sweep();

    assertFalse(peers.deleted(PeerTarget.GITHOST, deletePath(DEPENDENCIES)));
    assertFalse(peers.called(PeerTarget.PROJECTS, withdrawPath(BUMP_ONLY)));
    assertTrue(peers.deleted(PeerTarget.GITHOST, deletePath(EXTERNAL)), "the other one goes");
    assertTrue(result.waiting().stream().anyMatch(line -> line.contains(DEPENDENCIES)));
  }

  /** The emergency position: the sweep does nothing at all. */
  @Test
  void withTheSwitchOffTheSweepDoesNothing() {
    scriptTheEstate();
    UpstreamSwitch.install(config, false);

    LegacyGroupBranchSweep.Result result = sweep.sweep();

    assertTrue(result.idle());
    assertFalse(peers.called(PeerTarget.GITHOST, DESCRIBE));
    assertEquals(0, peers.deleteCount());
  }

  @Test
  void onlyAOneSegmentMaintenanceBranchIsAGroupBranch() {
    assertEquals("dependencies", LegacyGroupBranchSweep.groupOf(DEPENDENCIES));
    assertEquals(null, LegacyGroupBranchSweep.groupOf(AUTOMATION_BRANCH));
    assertEquals(null, LegacyGroupBranchSweep.groupOf("maintenance/baselines/" + BUMP_ONLY));
    assertEquals(null, LegacyGroupBranchSweep.groupOf("maintenance/"));
    assertEquals(null, LegacyGroupBranchSweep.groupOf("main"));
    assertTrue(LegacyGroupBranchSweep.bumpOnly(List.of("main", DEPENDENCIES, EXTERNAL), "main"));
    assertFalse(LegacyGroupBranchSweep.bumpOnly(List.of("main", "ticket/foo", DEPENDENCIES), "main"));
  }

  // --- fixtures -----------------------------------------------------------------------------------

  /**
   * One repository with two group branches, a ticket branch and an automation branch; a bump-only
   * request on {@code maintenance/dependencies}, a person's request carrying {@code
   * maintenance/external}, and an ended one naming both. Withdrawals and deletes are answered.
   */
  private void scriptTheEstate() {
    listBranches(
        "\"main\",\"" + DEPENDENCIES + "\",\"" + EXTERNAL + "\",\"ticket/foo\",\""
            + AUTOMATION_BRANCH + "\"");
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.ok(
            "{\"requests\":["
                + listed(BUMP_ONLY, "PENDING") + "," + listed(PERSONS, "READY") + ","
                + listed(FINALIZED, "FINALIZED") + "]}"));
    scriptRequest(BUMP_ONLY, "PENDING", "main", DEPENDENCIES);
    scriptRequest(PERSONS, "READY", "main", "ticket/foo", EXTERNAL);
    peers.answer(PeerTarget.PROJECTS, withdrawPath(BUMP_ONLY), FakePeers.Scripted.ok("{}"));
    peers.answer(PeerTarget.PROJECTS, withdrawPath(PERSONS), FakePeers.Scripted.ok("{}"));
    peers.answerDelete(
        PeerTarget.GITHOST, deletePath(DEPENDENCIES), FakePeers.Scripted.status(204, ""));
    peers.answerDelete(PeerTarget.GITHOST, deletePath(EXTERNAL), FakePeers.Scripted.status(204, ""));
  }

  private void listBranches(String quoted) {
    peers.answer(
        PeerTarget.GITHOST,
        DESCRIBE,
        FakePeers.Scripted.ok(
            "{\"id\":\"" + Fixture.CATALOG_ID + "\",\"defaultBranch\":\"main\",\"branches\":["
                + quoted + "]}"));
  }

  private static String listed(String id, String state) {
    return "{\"id\":\"" + id + "\",\"state\":\"" + state + "\",\"mergedSha\":null}";
  }

  /** One request's answer: its BRANCH sources, and a released tag beside them. */
  private void scriptRequest(String id, String state, String... branches) {
    StringBuilder sources = new StringBuilder();
    for (String branch : branches) {
      sources.append("{\"kind\":\"BRANCH\",\"name\":\"").append(branch).append("\"},");
    }
    sources.append("{\"kind\":\"TAG\",\"name\":\"").append(Fixture.TAG_VERSION).append("\"}");
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + id,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + id + "\",\"repoId\":\"" + Fixture.CATALOG_ID
                + "\",\"state\":\"" + state + "\",\"mergedSha\":null,\"sources\":[" + sources
                + "]}}"));
  }

  private static String withdrawPath(String id) {
    return Fixture.RELEASE_REQUESTS_PATH + "/" + id + "/withdraw";
  }

  private static String deletePath(String branch) {
    return DESCRIBE + "/branches/" + branch + "?projectId=" + Fixture.PROJECT + "&repoName="
        + Fixture.REPOSITORY;
  }

  /** A group row opened straight in the store, the way the release before the cutover left it. */
  private UUID openGroupBump() {
    return store.openBump(
        Fixture.REPOSITORY,
        "dependencies",
        DEPENDENCIES,
        "dev",
        BumpTrigger.SCHEDULED,
        List.of(
            new Change(
                "maven", "pom.xml", "eu.wohlben.qits:qits-eventstream", "1.0.0", "1.1.0",
                "property:qits.eventstream.version")),
        Instant.now());
  }

  private boolean triggered() {
    return peers.called(PeerTarget.CI, CiClient.TRIGGER_PATH);
  }

  /** Whether a release ask was POSTed — the collection's GET (the listing) is not one. */
  private boolean releaseAsked() {
    return peers.calls.stream()
        .anyMatch(
            call -> "POST".equals(call.method())
                && call.url().endsWith(Fixture.RELEASE_REQUESTS_PATH));
  }
}

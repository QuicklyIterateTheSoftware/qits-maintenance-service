package eu.wohlben.qits.maintenance.bump;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
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
import io.quarkus.narayana.jta.QuarkusTransaction;
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
 * <b>Group bumps are retired (qits-1133 R2 switched them off, R5 removed them)</b>: nothing writes
 * a {@code maintenance/<group>} branch. The group door is gone (404), the dispatcher opens no group
 * bump, a GROUP row found active is closed unsent and unreleased, and the legacy sweep withdraws the
 * bump-only requests standing on group branches, leaves a person's open, deletes the branches,
 * retires their rows — and does nothing at all the second time.
 */
@QuarkusTest
class GroupRetirementTest {

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

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, RUN);
    Fixture.scriptCiQueueEmpty(peers);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  @AfterEach
  void drain() {
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  // --- no group trigger from any path -------------------------------------------------------------

  /** The group door and the window doors are gone: nothing answers them, and nothing is sent. */
  @Test
  void theGroupDoorAndTheWindowAreGone() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
        .then()
        .statusCode(org.hamcrest.Matchers.oneOf(404, 405));
    given().when().get(BASE + "/bumps/window").then().statusCode(404);
    given().when().post(BASE + "/bumps/window").then().statusCode(org.hamcrest.Matchers.oneOf(404, 405));

    queue.awaitIdle(Duration.ofSeconds(30));
    assertTrue(store.bumps(Fixture.REPOSITORY, 10).isEmpty(), "no bump row was opened");
    assertFalse(triggered(), "nothing was sent to qits-ci");
  }

  /** The dispatcher, owed a bump, opens a main-only request and never a group bump. */
  @Test
  void theDispatcherCutsNoGroupBranch() {
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.ok(
            "{\"requests\":[],\"request\":{\"id\":\"" + BUMP_ONLY + "\",\"state\":\"PENDING\"}}"));

    for (int tick = 0; tick < 3; tick++) {
      dispatcher.tick();
      queue.awaitIdle(Duration.ofSeconds(30));
    }

    assertTrue(
        store.bumps(Fixture.REPOSITORY, 10).stream()
            .noneMatch(row -> BumpMode.of(row.mode) == BumpMode.GROUP),
        "no group bump row");
    assertFalse(triggered(), "nothing was sent to qits-ci");
    assertTrue(
        store.newestOpenedRequest(Fixture.REPOSITORY, MtReleaseRequest.MAIN_ONLY).isPresent(),
        "a main-only request was opened instead");
  }

  /** A GROUP row found REQUESTED is closed unsent — and for good. */
  @Test
  void aGroupRowLeftRequestedIsClosedWithoutATrigger() {
    UUID id = legacyGroupBump("REQUESTED", null);

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
   * A GROUP row found RUNNING is not followed: its run is not read, nothing is triggered and
   * nothing asks to release what it may have pushed — the legacy sweep deletes the branch instead.
   */
  @Test
  void aRunningGroupRowIsClosedWithoutReadingItsRun() {
    UUID id = legacyGroupBump("RUNNING", RUN);
    Fixture.scriptRun(peers, RUN, "SUCCESS");

    bumps.sweep();
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump row = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.NOTHING_TO_DO.name(), row.status, row.message);
    assertEquals(ReleaseRequestClient.CONVERGED, row.releaseRequestId);
    assertFalse(peers.called(PeerTarget.CI, "/ci/api/runs/" + RUN), "its run was not read");
    assertFalse(releaseAsked(), "nothing asked for a release");
    assertFalse(triggered(), "nothing was triggered");
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
    UUID owed = legacyGroupBump("SUCCEEDED", null);
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

  /**
   * A GROUP row written straight into the table, the way the path before the retirement left one —
   * nothing in this build can open one any more.
   */
  private UUID legacyGroupBump(String status, String runId) {
    UUID id = UUID.randomUUID();
    String changes;
    try {
      changes =
          new ObjectMapper()
              .writeValueAsString(
                  List.of(
                      new Change(
                          "maven", "pom.xml", "eu.wohlben.qits:qits-eventstream", "1.0.0", "1.1.0",
                          "property:qits.eventstream.version")));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    Instant now = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MtBump row = new MtBump();
              row.id = id;
              row.repository = Fixture.REPOSITORY;
              row.groupName = "dependencies";
              row.mode = BumpMode.GROUP.name();
              row.branch = DEPENDENCIES;
              row.environment = "dev";
              row.trigger = BumpTrigger.SCHEDULED.name();
              row.status = status;
              row.changes = changes;
              row.ciRunId = runId;
              row.startedAt = now;
              row.finishedAt =
                  status.equals("REQUESTED") || status.equals("RUNNING") ? null : now;
              row.persist();
            });
    return id;
  }

  private boolean triggered() {
    return peers.called(PeerTarget.CI, CiClient.TRIGGER_PATH);
  }

  /**
   * Whether a release ask was POSTed — the collection's GET (the listing) carries no body and is not
   * one. Read through a method: {@code peers} is a client proxy, whose own field would be empty.
   */
  private boolean releaseAsked() {
    return peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).stream()
        .anyMatch(java.util.Objects::nonNull);
  }
}

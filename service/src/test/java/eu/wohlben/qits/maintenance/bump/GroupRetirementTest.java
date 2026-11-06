package eu.wohlben.qits.maintenance.bump;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.api.SuiteBearers;
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
 * retires their rows — and does nothing at all the second time. It also settles the release state
 * of every green GROUP bump still listed as pending on a request nothing re-read since R5
 * (qits-1895).
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

  /** A request kept open because it carries a released tag (qits-1166). */
  private static final String TAGGED = "4f5e6d7c-8b9a-4c3d-9e8f-7a6b5c4d3e2f";

  private static final String WITH_TAG = "maintenance/withtag";

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
        .header("Authorization", SuiteBearers.operator())
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
        .then()
        .statusCode(org.hamcrest.Matchers.oneOf(404, 405));
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(BASE + "/bumps/window")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .post(BASE + "/bumps/window")
        .then()
        .statusCode(org.hamcrest.Matchers.oneOf(404, 405));

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

  /**
   * <b>A request carrying a released tag is kept open, and its branch still goes</b> (qits-1166):
   * qits-projects reports the tag as an implicit {@code RELEASED_TAG} source, which withdrawing
   * would strand off main. The sweep never asks to withdraw it, but the group branch — no longer a
   * reason to keep it folded — is deleted exactly as a person's would be.
   */
  @Test
  void theSweepKeepsARequestCarryingAReleasedTagOpenAndStillDeletesItsBranch() {
    listBranches("\"main\",\"" + WITH_TAG + "\"");
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.ok("{\"requests\":[" + listed(TAGGED, "PENDING") + "]}"));
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + TAGGED,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + TAGGED + "\",\"repoId\":\"" + Fixture.CATALOG_ID
                + "\",\"state\":\"PENDING\",\"mergedSha\":null,\"sources\":["
                + "{\"kind\":\"BRANCH\",\"name\":\"main\"},"
                + "{\"kind\":\"BRANCH\",\"name\":\"" + WITH_TAG + "\"},"
                + "{\"kind\":\"RELEASED_TAG\",\"name\":\"" + Fixture.TAG_VERSION
                + "\",\"implicit\":true}]}}"));
    peers.answerDelete(PeerTarget.GITHOST, deletePath(WITH_TAG), FakePeers.Scripted.status(204, ""));

    LegacyGroupBranchSweep.Result result = sweep.sweep();

    assertTrue(result.withdrawn().isEmpty(), result.toString());
    assertEquals(List.of(Fixture.REPOSITORY + " " + TAGGED), result.keptOpen());
    assertFalse(
        peers.called(PeerTarget.PROJECTS, withdrawPath(TAGGED)), "never asked to withdraw");
    assertTrue(peers.deleted(PeerTarget.GITHOST, deletePath(WITH_TAG)));
    assertEquals(List.of(Fixture.REPOSITORY + " " + WITH_TAG), result.deleted());
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
    // qits-1166: a released tag among the sources is not bump-only, and neither is an empty list.
    assertFalse(LegacyGroupBranchSweep.bumpOnly(List.of("main", Fixture.TAG_VERSION), "main"));
    assertFalse(LegacyGroupBranchSweep.bumpOnly(List.of(), "main"));
  }

  // --- the release-state reconcile (qits-1895) ---------------------------------------------------

  /** A request FINALIZED long ago ends the bump: written, and gone from the pending listing. */
  @Test
  void aGroupBumpWhoseRequestFinalizedLeavesThePendingListing() {
    listBranches("\"main\"");
    UUID id = awaitingRelease(BUMP_ONLY, "PENDING", Instant.now().minus(Duration.ofDays(27)));
    assertTrue(pending(id), "the stale row is listed before the pass");
    scriptRequest(BUMP_ONLY, "FINALIZED", "main", DEPENDENCIES);

    LegacyGroupBranchSweep.Result result = sweep.sweep();

    MtBump row = store.bump(id).orElseThrow();
    assertEquals("FINALIZED", row.releaseState);
    assertEquals(List.of(Fixture.REPOSITORY + " " + id + " FINALIZED"), result.reconciled());
    assertFalse(pending(id), "a finalized request's bump is pending no more");
    assertEquals(BUMP_ONLY, row.releaseRequestId, "the request id is kept");
  }

  /** WITHDRAWN is written as a state like any other, and the request id stays where it is. */
  @Test
  void aWithdrawnRequestIsWrittenAsAStateAndKeepsItsId() {
    listBranches("\"main\"");
    UUID id = awaitingRelease(BUMP_ONLY, null, null);
    scriptRequest(BUMP_ONLY, "WITHDRAWN", "main", DEPENDENCIES);

    sweep.sweep();

    MtBump row = store.bump(id).orElseThrow();
    assertEquals("WITHDRAWN", row.releaseState);
    assertEquals(BUMP_ONLY, row.releaseRequestId);
    assertFalse(pending(id));
  }

  /** A request still open only re-stamps the row, which stays pending. */
  @Test
  void aStillOpenRequestReStampsTheRowAndKeepsItPending() {
    listBranches("\"main\"");
    Instant before = Instant.now().minus(Duration.ofDays(27));
    UUID id = awaitingRelease(BUMP_ONLY, "PENDING", before);
    scriptRequest(BUMP_ONLY, "READY", "main", DEPENDENCIES);

    LegacyGroupBranchSweep.Result result = sweep.sweep();

    MtBump row = store.bump(id).orElseThrow();
    assertEquals("READY", row.releaseState);
    assertTrue(row.releaseStateAt.isAfter(before), "re-stamped: " + row.releaseStateAt);
    assertTrue(pending(id), "an open request keeps its bump pending");
    assertEquals(List.of(Fixture.REPOSITORY + " " + id + " READY"), result.reconciled());
  }

  /** A request qits-projects holds no longer (404) is UNKNOWN: nothing left to ask. */
  @Test
  void aRequestQitsProjectsDoesNotHoldIsUnknown() {
    listBranches("\"main\"");
    UUID id = awaitingRelease(BUMP_ONLY, "PENDING", Instant.now().minus(Duration.ofDays(27)));
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + BUMP_ONLY,
        FakePeers.Scripted.status(404, "{}"));

    sweep.sweep();

    MtBump row = store.bump(id).orElseThrow();
    assertEquals(LegacyGroupBranchSweep.UNKNOWN, row.releaseState);
    assertTrue(row.releaseDetail.contains(BUMP_ONLY), row.releaseDetail);
    assertFalse(pending(id));
  }

  /**
   * A bump whose repository has no row, or a row with no catalog id, cannot be asked about: UNKNOWN
   * without a request made.
   */
  @Test
  void aBumpWhoseRepositoryCannotBeAddressedIsUnknownWithoutAsking() {
    listBranches("\"main\"");
    UUID gone = awaitingRelease("qits-gone", BUMP_ONLY, "PENDING", Instant.now());
    UUID uncatalogued = awaitingRelease(PERSONS, null, null);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                eu.wohlben.qits.maintenance.entity.MtRepository.update(
                    "catalogId = null where name = ?1", Fixture.REPOSITORY));

    sweep.sweep();

    MtBump goneRow = store.bump(gone).orElseThrow();
    assertEquals(LegacyGroupBranchSweep.UNKNOWN, goneRow.releaseState);
    assertTrue(goneRow.releaseDetail.contains("qits-gone"), goneRow.releaseDetail);
    MtBump uncataloguedRow = store.bump(uncatalogued).orElseThrow();
    assertEquals(LegacyGroupBranchSweep.UNKNOWN, uncataloguedRow.releaseState);
    assertTrue(
        uncataloguedRow.releaseDetail.contains("no catalog id"), uncataloguedRow.releaseDetail);
    assertFalse(pending(gone));
    assertFalse(pending(uncatalogued));
    assertFalse(peers.called(PeerTarget.PROJECTS, Fixture.RELEASE_REQUESTS_PATH + "/" + BUMP_ONLY));
    assertFalse(peers.called(PeerTarget.PROJECTS, Fixture.RELEASE_REQUESTS_PATH + "/" + PERSONS));
  }

  /** A read that fails for any other reason writes nothing; the next pass asks again. */
  @Test
  void aTransientFailureLeavesTheRowForTheNextPass() {
    listBranches("\"main\"");
    Instant before = Instant.now().minus(Duration.ofDays(27));
    UUID id = awaitingRelease(BUMP_ONLY, "PENDING", before);
    UUID other = awaitingRelease(PERSONS, "CONFLICTED", before);
    // Read back what the column holds: the database keeps microseconds, the Instant nanoseconds.
    Instant stored = store.bump(id).orElseThrow().releaseStateAt;
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + BUMP_ONLY,
        FakePeers.Scripted.status(500, "boom"));
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + PERSONS,
        FakePeers.Scripted.unreachable("connection refused"));

    LegacyGroupBranchSweep.Result result = sweep.sweep();

    for (UUID each : List.of(id, other)) {
      MtBump row = store.bump(each).orElseThrow();
      assertEquals(stored, row.releaseStateAt, "not re-stamped");
      assertTrue(pending(each), "still pending");
    }
    assertEquals("PENDING", store.bump(id).orElseThrow().releaseState);
    assertEquals("CONFLICTED", store.bump(other).orElseThrow().releaseState);
    assertTrue(result.reconciled().isEmpty(), result.toString());

    scriptRequest(BUMP_ONLY, "OBSOLETE", "main", DEPENDENCIES);
    sweep.sweep();
    assertEquals("OBSOLETE", store.bump(id).orElseThrow().releaseState);
  }

  /** A settled row no longer matches: the second pass asks about nothing and is idle. */
  @Test
  void theSecondPassAsksAboutNoSettledBump() {
    listBranches("\"main\"");
    UUID id = awaitingRelease(BUMP_ONLY, "PENDING", Instant.now());
    scriptRequest(BUMP_ONLY, "FINALIZED", "main", DEPENDENCIES);
    String path = Fixture.RELEASE_REQUESTS_PATH + "/" + BUMP_ONLY;

    LegacyGroupBranchSweep.Result first = sweep.sweep();
    assertEquals(1, first.reconciled().size(), first.toString());
    long asked = peers.urls().stream().filter(url -> url.endsWith(path)).count();
    assertEquals(1, asked);

    LegacyGroupBranchSweep.Result second = sweep.sweep();

    assertTrue(second.idle(), second.toString());
    assertEquals(
        asked, peers.urls().stream().filter(url -> url.endsWith(path)).count(), "asked once");
    assertEquals("FINALIZED", store.bump(id).orElseThrow().releaseState);
  }

  /**
   * An automation row's request id is the request it belongs to, and a {@code converged} / {@code
   * refused} GROUP row owes nothing: none of them is asked about or written.
   */
  @Test
  void automationRowsAndEndedGroupRowsAreNeverAskedAbout() {
    listBranches("\"main\"");
    UUID automation =
        bumpRow(Fixture.REPOSITORY, BumpMode.AUTOMATION, "SUCCEEDED", BUMP_ONLY, "PENDING", null);
    UUID converged =
        bumpRow(Fixture.REPOSITORY, BumpMode.GROUP, "SUCCEEDED", ReleaseRequestClient.CONVERGED,
            null, null);
    UUID refused =
        bumpRow(Fixture.REPOSITORY, BumpMode.GROUP, "SUCCEEDED", ReleaseRequestClient.REFUSED,
            "PENDING", null);
    UUID failed = bumpRow(Fixture.REPOSITORY, BumpMode.GROUP, "FAILED", PERSONS, null, null);
    scriptRequest(BUMP_ONLY, "FINALIZED", "main", DEPENDENCIES);
    scriptRequest(PERSONS, "FINALIZED", "main", DEPENDENCIES);

    LegacyGroupBranchSweep.Result result = sweep.sweep();

    assertTrue(result.reconciled().isEmpty(), result.toString());
    assertTrue(result.idle(), result.toString());
    assertFalse(peers.called(PeerTarget.PROJECTS, Fixture.RELEASE_REQUESTS_PATH + "/" + BUMP_ONLY));
    assertFalse(peers.called(PeerTarget.PROJECTS, Fixture.RELEASE_REQUESTS_PATH + "/" + PERSONS));
    assertEquals("PENDING", store.bump(automation).orElseThrow().releaseState);
    assertEquals(null, store.bump(converged).orElseThrow().releaseState);
    assertEquals("PENDING", store.bump(refused).orElseThrow().releaseState);
    assertEquals(null, store.bump(failed).orElseThrow().releaseState);
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
    scriptRequest(BUMP_ONLY, "PENDING", false, "main", DEPENDENCIES);
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
    scriptRequest(id, state, true, branches);
  }

  /**
   * One request's answer: its BRANCH sources, and — when {@code withTag} — a released tag beside
   * them (qits-1166). A plain bump-only request carries no tag; a request kept open because of one
   * passes {@code withTag} true.
   */
  private void scriptRequest(String id, String state, boolean withTag, String... branches) {
    StringBuilder sources = new StringBuilder();
    for (String branch : branches) {
      sources.append("{\"kind\":\"BRANCH\",\"name\":\"").append(branch).append("\"},");
    }
    if (withTag) {
      sources.append("{\"kind\":\"TAG\",\"name\":\"").append(Fixture.TAG_VERSION).append("\"}");
    } else if (sources.length() > 0) {
      sources.setLength(sources.length() - 1);
    }
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

  private boolean pending(UUID id) {
    return store.pendingBumps(20).stream().anyMatch(row -> row.id.equals(id));
  }

  /** A green GROUP bump of the fixture repository standing on a request, as R5 left them. */
  private UUID awaitingRelease(String requestId, String state, Instant stateAt) {
    return awaitingRelease(Fixture.REPOSITORY, requestId, state, stateAt);
  }

  private UUID awaitingRelease(
      String repository, String requestId, String state, Instant stateAt) {
    return bumpRow(repository, BumpMode.GROUP, "SUCCEEDED", requestId, state, stateAt);
  }

  /** A finished row with a release request id and a cached state, written straight in. */
  private UUID bumpRow(
      String repository, BumpMode mode, String status, String requestId, String state,
      Instant stateAt) {
    UUID id = legacyGroupBump(status, null);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MtBump row = MtBump.findById(id);
              row.repository = repository;
              row.mode = mode.name();
              row.releaseRequestId = requestId;
              row.releaseState = state;
              row.releaseStateAt = stateAt;
              row.resultSha = Fixture.BUMPED_SHA;
            });
    return id;
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

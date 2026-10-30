package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.PUSHED;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore.AutomationOpening;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A stale own branch is recut</b> (qits-1764): a branch the request no longer joins, whose head
 * the fold does not contain, is deleted before the run so the core's plain push starts it afresh on
 * the fold. A branch still joined, or a head the fold contains, is never touched.
 */
@QuarkusTest
class AutomationBranchRecutTest {

  private static final String RUN = "run-automation-branch-recut";

  private static final String BRANCH = AutomationFixture.branch(REQUEST);

  private static final String DELETE =
      "/githost/api/repositories/" + Fixture.CATALOG_ID + "/branches/" + BRANCH + "?projectId="
          + Fixture.PROJECT + "&repoName=" + Fixture.REPOSITORY;

  @Inject AutomationService automations;

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
    AutomationFixture.scriptFold(peers, FOLD_A, true);
    Fixture.scriptForeignBranchAt(peers, BRANCH, PUSHED);
    peers.answerDelete(PeerTarget.GITHOST, DELETE, FakePeers.Scripted.status(204, ""));
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  /** The request at the fold, joining these branch sources. */
  private void scriptRequestJoining(String... branches) {
    StringBuilder sources = new StringBuilder();
    for (String branch : branches) {
      if (!sources.isEmpty()) {
        sources.append(',');
      }
      sources.append("{\"kind\":\"BRANCH\",\"name\":\"").append(branch).append("\"}");
    }
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + REQUEST,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + REQUEST + "\",\"repoId\":\"" + Fixture.CATALOG_ID
                + "\",\"state\":\"PENDING\",\"mergedSha\":\"" + FOLD_A + "\",\"sources\":["
                + sources + "]}}"));
  }

  private MtBump dispatched() {
    UUID id =
        store.openAutomation(
            new AutomationOpening(
                Fixture.REPOSITORY,
                ScreenshotBaselinesAutomation.KIND,
                REQUEST,
                FOLD_A,
                null,
                null,
                BRANCH,
                null,
                "dev",
                BumpTrigger.MANUAL,
                List.of(),
                Map.of(),
                BumpStatus.REQUESTED,
                null,
                null,
                null),
            true,
            java.time.Instant.now());
    automations.dispatch(id);
    queue.awaitIdle(Duration.ofSeconds(30));
    return store.bump(id).orElseThrow();
  }

  private String payload() {
    List<String> bodies = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertFalse(bodies.isEmpty(), "a run was asked for");
    return bodies.getLast();
  }

  @Test
  void aDroppedBranchOffTheFoldIsDeletedAndTheRunStartsItAfresh() {
    scriptRequestJoining("main", "work");
    Fixture.scriptContains(peers, PUSHED, FOLD_A, false);

    MtBump row = dispatched();

    assertTrue(peers.deleted(PeerTarget.GITHOST, DELETE), "the stale branch is deleted");
    assertEquals(1, peers.deleteCount());
    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertNull(row.resultSha, "the run starts from no branch");
    assertTrue(payload().contains("\"foldSha\":\"" + FOLD_A + "\""), payload());
    assertTrue(payload().contains("\"branch\":\"" + BRANCH + "\""), payload());
  }

  @Test
  void aBranchTheRequestStillJoinsIsNeverDeleted() {
    scriptRequestJoining("main", "work", BRANCH);
    Fixture.scriptContains(peers, PUSHED, FOLD_A, false);

    MtBump row = dispatched();

    assertEquals(0, peers.deleteCount(), "a joined branch is kept");
    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals(PUSHED, row.resultSha, "the head stays the start");
  }

  @Test
  void aHeadTheFoldContainsIsNeverDeleted() {
    scriptRequestJoining("main", "work");
    Fixture.scriptContains(peers, PUSHED, FOLD_A, true);

    MtBump row = dispatched();

    assertEquals(0, peers.deleteCount(), "a head inside the fold is kept");
    assertEquals(PUSHED, row.resultSha);
  }

  @Test
  void anUnreadableRequestKeepsTheBranch() {
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + REQUEST,
        FakePeers.Scripted.unreachable("connection refused"));
    Fixture.scriptContains(peers, PUSHED, FOLD_A, false);

    MtBump row = dispatched();

    assertEquals(0, peers.deleteCount(), "a request that could not be read says nothing");
    assertEquals(PUSHED, row.resultSha);
  }

  @Test
  void anUnreadableContainmentKeepsTheBranch() {
    scriptRequestJoining("main", "work");
    // Unscripted, the ancestry door answers 404: a failed answer, never a false.

    MtBump row = dispatched();

    assertEquals(0, peers.deleteCount());
    assertEquals(PUSHED, row.resultSha);
  }
}

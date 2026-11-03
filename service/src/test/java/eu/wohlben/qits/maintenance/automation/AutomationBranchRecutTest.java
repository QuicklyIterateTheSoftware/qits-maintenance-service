package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_B;
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
 *
 * <p><b>A joined head the fold lacks is held, not sent</b> (qits-1839): the request's re-fold was
 * deferred (qits-1570), and a run from the fold it stands on could only end in the refused push.
 * The row waits REQUESTED until the request moves to a fold that has the head, or closes.
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
    scriptRequest("PENDING", FOLD_A, branches);
  }

  /** The request in this state at this fold, joining these branch sources. */
  private void scriptRequest(String state, String mergedSha, String... branches) {
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
                + "\",\"state\":\"" + state + "\",\"mergedSha\":\"" + mergedSha
                + "\",\"sources\":["
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

  /**
   * THE LIVE CASE (qits-1839, qits-qits-rr-292): the request still joins the branch, and its fold
   * lacks the branch's newer tip because the re-fold the push asked for was deferred. Nothing is
   * deleted and nothing is sent; the row waits, saying for what.
   */
  @Test
  void aJoinedHeadTheFoldLacksIsHeldNotSent() {
    MtBump row = held();

    assertEquals(0, peers.deleteCount(), "a joined branch is kept");
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty(), "no run was asked for");
    assertEquals(BumpStatus.REQUESTED.name(), row.status, row.message);
    assertNull(row.resultSha, "no start head is written for a held row");
    assertTrue(row.message.contains("re-fold"), row.message);
    assertTrue(row.message.contains(FOLD_A.substring(0, 12)), row.message);
    assertTrue(row.message.contains(PUSHED.substring(0, 12)), row.message);
    assertTrue(row.message.contains(BRANCH), row.message);
  }

  /** A row held on a joined head the fold lacks (qits-1839), asserted held. */
  private MtBump held() {
    scriptRequestJoining("main", "work", BRANCH);
    Fixture.scriptContains(peers, PUSHED, FOLD_A, false);
    MtBump row = dispatched();
    assertEquals(BumpStatus.REQUESTED.name(), row.status, row.message);
    return row;
  }

  /**
   * The re-fold lands: the request moves to fold B, which contains the head. The held row is a
   * door's, so the next offer supersedes it and opens the re-run again on B (qits-1726), where it
   * is sent.
   */
  @Test
  void aHeldRowIsSupersededOnceTheRequestReFolds() {
    MtBump row = held();
    AutomationFixture.scriptFold(peers, FOLD_B, true);
    scriptRequest("PENDING", FOLD_B, "main", "work", BRANCH);
    Fixture.scriptContains(peers, PUSHED, FOLD_B, true);

    automations.dispatch(row.id);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump old = store.bump(row.id).orElseThrow();
    assertEquals(BumpStatus.SUPERSEDED.name(), old.status, old.message);
    assertTrue(old.message.contains("moved on to fold " + FOLD_B.substring(0, 12)), old.message);
    List<MtBump> again =
        store.automations(REQUEST, FOLD_B).stream()
            .filter(next -> ScreenshotBaselinesAutomation.KIND.equals(next.automationKind))
            .toList();
    assertEquals(1, again.size(), "the re-run, opened again on the fold the request is at");
    assertEquals(BumpStatus.RUNNING.name(), again.getFirst().status, again.getFirst().message);
    assertEquals(PUSHED, again.getFirst().resultSha, "inside fold B, the head stays the start");
    assertEquals(0, peers.deleteCount());
    assertEquals(1, peers.bodiesFor(CiClient.TRIGGER_PATH).size(), "sent once, on fold B");
    assertTrue(payload().contains("\"foldSha\":\"" + FOLD_B + "\""), payload());
  }

  /** The request closes under the held row: it ends (qits-1578), and nothing is sent. */
  @Test
  void aHeldRowEndsWhenTheRequestCloses() {
    MtBump row = held();
    scriptRequest("FINALIZED", FOLD_A, "main", "work", BRANCH);

    automations.dispatch(row.id);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump ended = store.bump(row.id).orElseThrow();
    assertEquals(BumpStatus.SUPERSEDED.name(), ended.status, ended.message);
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty(), "nothing was sent");
    assertEquals(0, peers.deleteCount());
    assertTrue(store.activeAutomations(REQUEST).isEmpty(), "nothing left to run");
  }

  /** A held row offered again while the request still stands on the fold stays held, unwritten. */
  @Test
  void aHeldRowOfferedAgainStaysHeld() {
    MtBump row = held();

    automations.dispatch(row.id);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump again = store.bump(row.id).orElseThrow();
    assertEquals(BumpStatus.REQUESTED.name(), again.status, again.message);
    assertEquals(row.message, again.message);
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty(), "still nothing sent");
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

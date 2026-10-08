package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The automation-branch sweep: a closed request's branch is deleted, an open one's is kept, an
 * unknown request's is deleted only once old, a failed delete is retried by the next pass, and no
 * branch outside the namespace is ever touched.
 */
@QuarkusTest
class AutomationBranchSweepTest {

  private static final String REQUEST = "fd1a445d-b808-47b9-b6f7-61a337bdf5ff";

  private static final String BRANCH =
      AutomationService.BRANCH_PREFIX + EntityDiagramAutomation.KIND + "/" + REQUEST;

  private static final String DESCRIBE = "/githost/api/repositories/" + Fixture.CATALOG_ID;

  private static final String DELETE = DESCRIBE + "/branches/" + BRANCH;

  @Inject AutomationBranchSweep sweep;

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
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
    peers.answer(
        PeerTarget.GITHOST,
        DESCRIBE,
        FakePeers.Scripted.ok(
            "{\"id\":\"" + Fixture.CATALOG_ID + "\",\"defaultBranch\":\"main\",\"branches\":"
                + "[\"main\",\"maintenance/dependencies\",\"" + BRANCH + "\"]}"));
  }

  private void deleteAnswers(FakePeers.Scripted answer) {
    peers.answerDelete(PeerTarget.GITHOST, deletePath(), answer);
  }

  private static String deletePath() {
    return DELETE + "?projectId=" + Fixture.PROJECT + "&repoName=" + Fixture.REPOSITORY;
  }

  private boolean deleteAsked() {
    return peers.deleteCount() > 0;
  }

  @ParameterizedTest
  @ValueSource(strings = {"WITHDRAWN", "OBSOLETE", "FINALIZED"})
  void aClosedRequestsBranchIsDeleted(String state) {
    AutomationFixture.scriptRequest(peers, REQUEST, state, null);
    deleteAnswers(FakePeers.Scripted.status(204, ""));

    AutomationBranchSweep.Result result = sweep.sweep();

    assertTrue(peers.deleted(PeerTarget.GITHOST, deletePath()), "the branch is deleted");
    assertEquals(List.of(Fixture.REPOSITORY + " " + BRANCH), result.deleted());
    assertEquals(
        1,
        peers.deleteCount(),
        "only the automation branch — never main or maintenance/dependencies");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "READY", "RELEASED", "REJECTED", "FAILED", "CONFLICTED"})
  void anOpenRequestsBranchIsKept(String state) {
    AutomationFixture.scriptRequest(peers, REQUEST, state, null);
    deleteAnswers(FakePeers.Scripted.status(204, ""));

    AutomationBranchSweep.Result result = sweep.sweep();

    assertFalse(deleteAsked(), state + " is open");
    assertTrue(result.deleted().isEmpty());
  }

  @Test
  void anUnreachableQitsProjectsKeepsTheBranch() {
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + REQUEST,
        FakePeers.Scripted.unreachable("connection refused"));

    sweep.sweep();

    assertFalse(deleteAsked(), "a peer that could not be asked says nothing");
  }

  @Test
  void anUnknownRequestsBranchWithNoRowIsDeleted() {
    // Unscripted, qits-projects answers 404 for the request.
    deleteAnswers(FakePeers.Scripted.status(204, ""));

    sweep.sweep();

    assertTrue(peers.deleted(PeerTarget.GITHOST, deletePath()));
  }

  @Test
  void anUnknownRequestsBranchIsKeptUntilItIsOld() {
    open(BumpStatus.NOTHING_TO_DO);
    deleteAnswers(FakePeers.Scripted.status(204, ""));

    sweep.sweep(Instant.now());
    assertFalse(deleteAsked(), "touched today: kept");

    sweep.sweep(Instant.now().plus(AutomationBranchSweep.UNKNOWN_AFTER).plus(Duration.ofHours(1)));
    assertTrue(peers.deleted(PeerTarget.GITHOST, deletePath()), "older than the threshold: deleted");
  }

  @Test
  void aBranchWithAnActiveRunIsKeptWhateverTheRequestSays() {
    open(BumpStatus.REQUESTED);
    AutomationFixture.scriptRequest(peers, REQUEST, "WITHDRAWN", null);
    deleteAnswers(FakePeers.Scripted.status(204, ""));

    sweep.sweep();

    assertFalse(deleteAsked());
  }

  @Test
  void aFailedDeleteIsLoggedAndRetriedNextSweep() {
    AutomationFixture.scriptRequest(peers, REQUEST, "WITHDRAWN", null);
    deleteAnswers(FakePeers.Scripted.status(503, "busy"));

    AutomationBranchSweep.Result first = sweep.sweep();

    assertEquals(List.of(Fixture.REPOSITORY + " " + BRANCH), first.failed());
    assertTrue(first.deleted().isEmpty());

    deleteAnswers(FakePeers.Scripted.status(204, ""));
    AutomationBranchSweep.Result second = sweep.sweep();

    assertEquals(List.of(Fixture.REPOSITORY + " " + BRANCH), second.deleted());
    assertTrue(second.failed().isEmpty());
  }

  @Test
  void aBranchAlreadyGoneIsNotAFailure() {
    AutomationFixture.scriptRequest(peers, REQUEST, "WITHDRAWN", null);
    deleteAnswers(
        FakePeers.Scripted.status(
            404, "{\"error\":\"no-such-branch\",\"detail\":\"refs/heads/" + BRANCH + "\"}"));

    AutomationBranchSweep.Result result = sweep.sweep();

    assertTrue(result.failed().isEmpty());
    assertTrue(result.deleted().isEmpty());
  }

  @Test
  void onlyTheAutomationNamespaceNamesARequest() {
    assertEquals(REQUEST, AutomationBranchSweep.requestOf(BRANCH));
    assertEquals(null, AutomationBranchSweep.requestOf("maintenance/dependencies"));
    assertEquals(null, AutomationBranchSweep.requestOf("maintenance/automations/" + REQUEST));
    assertEquals(
        null, AutomationBranchSweep.requestOf(BRANCH + "/extra"));
    assertEquals(null, AutomationBranchSweep.requestOf("main"));
  }

  private void open(BumpStatus status) {
    store.openAutomation(
        new AutomationOpening(
            Fixture.REPOSITORY,
            EntityDiagramAutomation.KIND,
            REQUEST,
            AutomationFixture.FOLD_A,
            null,
            null,
            BRANCH,
            null,
            "test",
            BumpTrigger.FOLD,
            List.of(),
            Map.of(),
            status,
            null),
        false,
        Instant.now());
  }
}

package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A send to qits-ci that can succeed later is sent again, a bounded number of times</b>
 * (qits-1183). On 2026-10-11 a qits-ci restart and one missed digest lookup each ended an
 * automation FAILED at once, and a person had to re-run it. The suite ships no backoff, so each
 * test sends again by hand.
 */
@QuarkusTest
class AutomationDispatchRetryTest {

  private static final String RUN = "run-automation-retry";

  /** The shipped {@code qits.maintenance.bump.dispatch.retry-attempts}. */
  private static final int ATTEMPTS = 5;

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
    AutomationFixture.scriptFold(peers, FOLD_A, true);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    AutomationFixture.scriptJoin(peers, REQUEST, AutomationFixture.joined(REQUEST));
    Fixture.scriptForeignBranchAt(
        peers, AutomationFixture.branch(REQUEST), AutomationFixture.BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  /** qits-ci was restarting on the first send and took the second. */
  @Test
  void anUnreachableCiIsSentAgainAndTheSecondSendRuns() {
    ciAnswers(FakePeers.Scripted.unreachable("java.net.ConnectException"));
    MtBump row = trigger();

    assertEquals(BumpStatus.REQUESTED.name(), row.status, row.message);
    assertEquals(1, row.dispatchAttempts);
    assertTrue(row.message.contains("attempt 1 of " + ATTEMPTS), row.message);

    Fixture.scriptCiAccepts(peers, RUN);
    row = sendAgain(row);

    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    List<String> sends = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(2, sends.size());
    assertTrue(
        sends.get(1).contains("\"eventId\":\"" + row.id + "\""),
        "the same event id, so a send whose answer was lost records no second run: " + sends);
  }

  /** qits-ci answered every send and started no run: the row ends FAILED and names the count. */
  @Test
  void noRunRecordedEveryTimeEndsFailedAfterTheLastAttempt() {
    ciAnswers(
        FakePeers.Scripted.ok(
            "{\"eventId\":\"e1\",\"runIds\":[],\"repositoriesRead\":1,"
                + "\"repositoriesSkipped\":[\"" + Fixture.REPOSITORY + "\"]}"));
    MtBump row = trigger();
    for (int send = 2; send <= ATTEMPTS; send++) {
      assertEquals(BumpStatus.REQUESTED.name(), row.status, "send " + send + ": " + row.message);
      row = sendAgain(row);
    }

    assertEquals(BumpStatus.FAILED.name(), row.status, row.message);
    assertTrue(row.message.startsWith("no run recorded for ReleaseRequestAutomation"), row.message);
    assertTrue(row.message.contains("asked " + ATTEMPTS + " times"), row.message);
    assertEquals(ATTEMPTS, peers.bodiesFor(CiClient.TRIGGER_PATH).size());
    sendAgain(row);
    assertEquals(ATTEMPTS, peers.bodiesFor(CiClient.TRIGGER_PATH).size(), "a FAILED row is done");
  }

  /** A refusal with a reason is final, as before: one send and FAILED. */
  @Test
  void aRefusalIsFinal() {
    ciAnswers(FakePeers.Scripted.status(400, "{\"error\":\"Event payload is required\"}"));
    MtBump row = trigger();

    assertEquals(BumpStatus.FAILED.name(), row.status, row.message);
    assertTrue(row.message.startsWith("qits-ci refused the trigger"), row.message);
    assertEquals(1, peers.bodiesFor(CiClient.TRIGGER_PATH).size());
  }

  private void ciAnswers(FakePeers.Scripted answer) {
    peers.answer(PeerTarget.CI, CiClient.TRIGGER_PATH, answer);
  }

  /** Opens the screenshot automation on fold A and lets its first send happen. */
  private MtBump trigger() {
    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            REQUEST,
            new AutomationService.Fold(
                Fixture.REPOSITORY, FOLD_A, null, null, List.of("main", "work"), null));
    queue.awaitIdle(Duration.ofSeconds(30));
    String bumpId =
        answer.automations().stream()
            .filter(entry -> ScreenshotBaselinesAutomation.KIND.equals(entry.kind()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no screenshot entry in " + answer))
            .bumpId();
    return store.bump(UUID.fromString(bumpId)).orElseThrow();
  }

  /** What the sweep does for a REQUESTED row. */
  private MtBump sendAgain(MtBump row) {
    automations.dispatch(row.id);
    queue.awaitIdle(Duration.ofSeconds(30));
    return store.bump(row.id).orElseThrow();
  }
}

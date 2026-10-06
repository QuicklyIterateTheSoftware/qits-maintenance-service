package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.BEFORE;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_B;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.PUSHED;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>One ending for every kind</b>, one case per arm of an own-branch automation's: unmoved (FRESH,
 * nothing to do), moved and joined (COMMITTED), a join refused (FAILED), a join not answered
 * (COMMITTED, not joined yet), red (FAILED), a fold that moved on (SUPERSEDED), and the circuit
 * breaker.
 */
@QuarkusTest
class AutomationEndingTest {

  private static final String RUN = "run-automation-ending";

  @Inject AutomationService automations;

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
    AutomationFixture.scriptFold(peers, FOLD_A, true);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    AutomationFixture.scriptJoin(peers, REQUEST, AutomationFixture.joined(REQUEST));
    Fixture.scriptForeignBranchAt(peers, AutomationFixture.branch(REQUEST), BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  /** Fold A's screenshot run, dispatched and RUNNING. */
  private UUID running() {
    AutomationDto entry =
        automations
            .trigger(
                REQUEST,
                new AutomationService.Fold(
                    Fixture.REPOSITORY, FOLD_A, null, null, List.of("work"), "qits-998"))
            .automations()
            .getFirst();
    queue.awaitIdle(Duration.ofSeconds(30));
    UUID id = UUID.fromString(entry.bumpId());
    assertEquals(BumpStatus.RUNNING.name(), store.bump(id).orElseThrow().status);
    return id;
  }

  /** Ends the run with this CI status and lets the poll write the verdict. */
  private MtBump end(UUID id, String ciStatus) {
    Fixture.scriptRun(peers, RUN, ciStatus);
    bumps.poll(id);
    queue.awaitIdle(Duration.ofSeconds(30));
    return store.bump(id).orElseThrow();
  }

  private AutomationDto entry() {
    return automations.automations(REQUEST, FOLD_A).automations().getFirst();
  }

  @Test
  void anUnmovedBranchIsFreshAndJoinsNothing() {
    MtBump done = end(running(), "SUCCESS");

    assertEquals(BumpStatus.NOTHING_TO_DO.name(), done.status);
    assertTrue(done.message.startsWith("unchanged"), done.message);
    assertEquals(AutomationState.FRESH.name(), entry().state());
    assertTrue(peers.bodiesFor(AutomationFixture.joinPath(REQUEST)).isEmpty(), "nothing to join");
  }

  @Test
  void aMovedBranchIsJoinedAndCommitted() {
    UUID id = running();
    Fixture.scriptForeignBranchAt(peers, AutomationFixture.branch(REQUEST), PUSHED);

    MtBump done = end(id, "SUCCESS");

    assertEquals(BumpStatus.SUCCEEDED.name(), done.status, done.message);
    assertEquals(PUSHED, done.resultSha);
    assertTrue(done.message.contains("joined to release request " + REQUEST), done.message);
    AutomationDto entry = entry();
    assertEquals(AutomationState.COMMITTED.name(), entry.state());
    assertEquals(PUSHED, entry.resultSha());
    assertEquals(List.of(RUN), entry.runIds());
    List<String> joins = peers.bodiesFor(AutomationFixture.joinPath(REQUEST));
    assertEquals(1, joins.size());
    assertTrue(joins.getFirst().contains(AutomationFixture.branch(REQUEST)), joins.getFirst());
  }

  @Test
  void aRefusedJoinFails() {
    UUID id = running();
    Fixture.scriptForeignBranchAt(peers, AutomationFixture.branch(REQUEST), PUSHED);
    AutomationFixture.scriptJoin(
        peers, REQUEST, FakePeers.Scripted.status(409, "{\"message\":\"the request is settled\"}"));

    MtBump done = end(id, "SUCCESS");

    assertEquals(BumpStatus.FAILED.name(), done.status, done.message);
    assertTrue(done.message.contains("refused"), done.message);
    assertEquals(AutomationState.FAILED.name(), entry().state());
  }

  @Test
  void aJoinNobodyAnsweredIsCommittedAndSaysSo() {
    UUID id = running();
    Fixture.scriptForeignBranchAt(peers, AutomationFixture.branch(REQUEST), PUSHED);
    AutomationFixture.scriptJoin(peers, REQUEST, FakePeers.Scripted.unreachable("connection refused"));

    MtBump done = end(id, "SUCCESS");

    assertEquals(BumpStatus.SUCCEEDED.name(), done.status, done.message);
    assertTrue(done.message.contains("not joined yet"), done.message);
  }

  @Test
  void aRedRunFails() {
    MtBump done = end(running(), "FAILED");

    assertEquals(BumpStatus.FAILED.name(), done.status);
    assertTrue(done.message.contains("ended FAILED"), done.message);
    assertEquals(AutomationState.FAILED.name(), entry().state());
    assertTrue(peers.bodiesFor(AutomationFixture.joinPath(REQUEST)).isEmpty());
  }

  /** The request re-folded while the run went: its outcome answers nothing, whatever it was. */
  @Test
  void aFoldThatMovedOnSupersedesTheOutcome() {
    UUID id = running();
    Fixture.scriptForeignBranchAt(peers, AutomationFixture.branch(REQUEST), PUSHED);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_B);

    MtBump done = end(id, "SUCCESS");

    assertEquals(BumpStatus.SUPERSEDED.name(), done.status, done.message);
    assertEquals(AutomationState.SUPERSEDED.name(), entry().state());
    assertTrue(peers.bodiesFor(AutomationFixture.joinPath(REQUEST)).isEmpty(), "nothing joined");
  }

  /**
   * <b>THE CIRCUIT BREAKER.</b> Three COMMITTED in a row, each fold after the first changed only by
   * automation output — and a fourth green run that moved the branch again is FAILED, not converging,
   * and is not joined.
   */
  @Test
  void aFourthConsecutiveCommitIsNotConverging() {
    String branch = AutomationFixture.branch(REQUEST);
    Instant at = Instant.parse("2026-10-06T10:00:00Z");
    List<String> folds =
        List.of(
            "e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1",
            "e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2",
            "e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3");
    for (int index = 0; index < folds.size(); index++) {
      store.openAutomation(
          opening(folds.get(index), null, index > 0, BumpStatus.SUCCEEDED, branch),
          false,
          at.plusSeconds(index));
    }
    // The fourth, opened behind a fold with no outcome of its own so carry-over cannot end it, and
    // dispatched by hand.
    UUID fourth =
        store.openAutomation(
            opening(FOLD_A, "f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0", true,
                BumpStatus.REQUESTED, branch),
            false,
            Instant.now());
    automations.dispatch(fourth);
    assertEquals(BumpStatus.RUNNING.name(), store.bump(fourth).orElseThrow().status);
    Fixture.scriptForeignBranchAt(peers, branch, PUSHED);

    MtBump done = end(fourth, "SUCCESS");

    assertEquals(BumpStatus.FAILED.name(), done.status, done.message);
    assertTrue(done.message.startsWith(AutomationService.NOT_CONVERGING), done.message);
    assertTrue(peers.bodiesFor(AutomationFixture.joinPath(REQUEST)).isEmpty(), "never joined");
  }

  private static AutomationOpening opening(
      String fold, String previous, boolean automationOnly, BumpStatus status, String branch) {
    return new AutomationOpening(
        Fixture.REPOSITORY,
        ScreenshotBaselinesAutomation.KIND,
        REQUEST,
        fold,
        previous,
        automationOnly,
        branch,
        null,
        "test",
        BumpTrigger.FOLD,
        List.of(),
        Map.of(),
        status,
        status == BumpStatus.SUCCEEDED ? "screenshot baselines written on " + branch : null);
  }
}

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

  /** Red after the request re-folded: a verdict about a fold nobody will release, discarded. */
  @Test
  void aRedRunOnAFoldThatMovedOnIsSuperseded() {
    UUID id = running();
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_B);

    MtBump done = end(id, "FAILED");

    assertEquals(BumpStatus.SUPERSEDED.name(), done.status, done.message);
    assertEquals(AutomationState.SUPERSEDED.name(), entry().state());
    assertTrue(peers.bodiesFor(AutomationFixture.joinPath(REQUEST)).isEmpty(), "nothing joined");
  }

  /**
   * Green and unchanged after the request re-folded is still FRESH for the fold it ran on: the core
   * refuses a fold that is no longer {@code foldSha} before it starts, so what it found is a fact
   * about that fold.
   */
  @Test
  void aGreenUnchangedRunOnAFoldThatMovedOnIsFreshForItsOwnFold() {
    UUID id = running();
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_B);

    MtBump done = end(id, "SUCCESS");

    assertEquals(BumpStatus.NOTHING_TO_DO.name(), done.status, done.message);
    assertEquals(AutomationState.FRESH.name(), entry().state());
  }

  /**
   * <b>THE BRANCH IS ALREADY JOINED, so the run's own push re-folds the request</b> before the poll
   * sees it end. The outcome is COMMITTED against its own fold — joined again, which converges — and
   * the fold that push made, posted while the run was still going and waiting behind it, is carried
   * the moment it ends: FRESH, with no second dispatch.
   */
  @Test
  void aFoldMovedByTheRunsOwnPushIsCommittedAndTheNextFoldCarries() {
    UUID id = running();
    AutomationFixture.scriptFold(peers, FOLD_B, true);
    // The push lands and qits-projects re-folds; its trigger for the new fold arrives while the run
    // is still RUNNING, so the new fold waits behind it rather than carrying.
    Fixture.scriptForeignBranchAt(peers, AutomationFixture.branch(REQUEST), PUSHED);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_B);
    AutomationDto waiting =
        automations
            .trigger(
                REQUEST,
                new AutomationService.Fold(
                    Fixture.REPOSITORY,
                    FOLD_B,
                    FOLD_A,
                    List.of("src/app/home/__screenshots__/home.png"),
                    List.of("work", AutomationFixture.branch(REQUEST)),
                    null))
            .automations()
            .getFirst();
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(AutomationState.REQUESTED.name(), waiting.state(), waiting.detail());
    int triggers = peers.bodiesFor(eu.wohlben.qits.maintenance.bump.CiClient.TRIGGER_PATH).size();

    MtBump done = end(id, "SUCCESS");

    assertEquals(BumpStatus.SUCCEEDED.name(), done.status, done.message);
    assertEquals(PUSHED, done.resultSha);
    assertEquals(AutomationState.COMMITTED.name(), entry().state());
    MtBump next = store.bump(UUID.fromString(waiting.bumpId())).orElseThrow();
    assertEquals(BumpStatus.NOTHING_TO_DO.name(), next.status, next.message);
    assertTrue(next.message.contains("carried"), next.message);
    assertEquals(
        triggers,
        peers.bodiesFor(eu.wohlben.qits.maintenance.bump.CiClient.TRIGGER_PATH).size(),
        "no second run for the fold the run's own push made");
    assertEquals(
        AutomationState.FRESH.name(),
        automations.automations(REQUEST, FOLD_B).automations().getFirst().state());
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

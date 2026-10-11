package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_B;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Kinds QA checks</b> (qits-1180): with the repository on {@code
 * qits.maintenance.automations.qa-checked.repositories}, entity-diagram and screenshot-baselines are
 * answered DEFERRED and not run — but only for a caller that accepts the word. The re-run door still
 * runs them, and its result shows as it does today. The switch off is in {@link
 * AutomationTriggerTest}.
 */
@QuarkusTest
@TestProfile(QaCheckedAutomationTest.QaChecked.class)
class QaCheckedAutomationTest {

  /** The fixture's repository is QA-checked; nothing else changed. */
  public static class QaChecked implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(MaintenanceConfig.QA_CHECKED_REPOSITORIES, "other-repository," + Fixture.REPOSITORY);
    }
  }

  private static final String RUN = "run-qa-checked";

  private static final List<String> WITH_DEFERRED = List.of("WAITING", "NOT_APPLICABLE", "DEFERRED");

  private static final List<String> WITHOUT_DEFERRED = List.of("WAITING", "NOT_APPLICABLE");

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
    for (String fold : List.of(FOLD_A, FOLD_B)) {
      AutomationFixture.scriptFold(peers, fold, true);
    }
    AutomationFixture.scriptEntityDiagramApplies(peers, FOLD_A);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    AutomationFixture.scriptJoin(peers, REQUEST, AutomationFixture.joined(REQUEST));
    Fixture.scriptForeignBranchAt(
        peers, AutomationFixture.branch(REQUEST), AutomationFixture.BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private ReleaseRequestAutomationsDto trigger(
      String fold, String previous, List<String> changed, List<String> accepts) {
    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            REQUEST,
            new AutomationService.Fold(
                Fixture.REPOSITORY, fold, previous, changed, List.of("main", "work"), null,
                accepts));
    queue.awaitIdle(Duration.ofSeconds(30));
    return answer;
  }

  private List<MtBump> rows(String fold, String kind) {
    return store.automations(REQUEST, fold).stream()
        .filter(row -> kind.equals(row.automationKind))
        .toList();
  }

  private int triggers() {
    return peers.bodiesFor(CiClient.TRIGGER_PATH).size();
  }

  /**
   * A caller that accepts DEFERRED: both kinds still apply and are listed, answered DEFERRED with a
   * reason, nothing is stored and nothing runs.
   */
  @Test
  void aCallerThatAcceptsDeferredGetsDeferredAndNothingRuns() {
    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, WITH_DEFERRED);

    for (String kind : List.of(EntityDiagramAutomation.KIND, ScreenshotBaselinesAutomation.KIND)) {
      AutomationDto entry = AutomationFixture.entry(answer, kind);
      assertEquals(AutomationState.DEFERRED.name(), entry.state(), entry.detail());
      assertTrue(entry.reason().startsWith("checked by QA"), entry.reason());
      assertNull(entry.bumpId());
      assertTrue(rows(FOLD_A, kind).isEmpty(), kind + ": nothing stored");
    }
    assertEquals(0, triggers(), "no run before QA");

    // Answered, never stored: the next ask decides again, and answers the same.
    AutomationDto again =
        AutomationFixture.entry(
            trigger(FOLD_A, null, null, WITH_DEFERRED), ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.DEFERRED.name(), again.state());
    assertEquals(0, triggers());
  }

  /** A caller that does not accept DEFERRED gets today's pre-run, switch or no switch. */
  @Test
  void aCallerThatDoesNotAcceptDeferredRunsAsToday() {
    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, WITHOUT_DEFERRED);

    AutomationDto screenshots =
        AutomationFixture.entry(answer, ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.REQUESTED.name(), screenshots.state(), screenshots.detail());
    assertNotNull(screenshots.bumpId());
    assertEquals(1, rows(FOLD_A, ScreenshotBaselinesAutomation.KIND).size());
    assertEquals(
        AutomationState.REQUESTED.name(),
        AutomationFixture.entry(answer, EntityDiagramAutomation.KIND).state());
    assertTrue(triggers() >= 1, "the kinds were dispatched");
  }

  /**
   * The re-run door still runs a deferred kind. Its row then answers the fold as today, and a
   * COMMITTED run is carried onto the re-fold its own commit causes — the kind's paths are still in
   * the union.
   */
  @Test
  void theRerunDoorStillRunsADeferredKind() {
    assertEquals(
        AutomationState.DEFERRED.name(),
        AutomationFixture.entry(
                trigger(FOLD_A, null, null, WITH_DEFERRED), ScreenshotBaselinesAutomation.KIND)
            .state());

    UUID id =
        automations.run(
            Fixture.REPOSITORY, REQUEST, ScreenshotBaselinesAutomation.KIND, null,
            BumpTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(30));
    assertNotNull(id);
    assertEquals(1, triggers(), "the re-run was dispatched");

    AutomationDto running =
        AutomationFixture.entry(
            trigger(FOLD_A, null, null, WITH_DEFERRED), ScreenshotBaselinesAutomation.KIND);
    assertEquals(id.toString(), running.bumpId(), "answered from the re-run's row");
    assertEquals(AutomationState.RUNNING.name(), running.state(), running.detail());

    Fixture.scriptForeignBranchAt(
        peers, AutomationFixture.branch(REQUEST), AutomationFixture.PUSHED);
    Fixture.scriptRun(peers, RUN, "SUCCESS");
    bumps.poll(id);
    queue.awaitIdle(Duration.ofSeconds(30));
    AutomationDto committed =
        AutomationFixture.entry(
            trigger(FOLD_A, null, null, WITH_DEFERRED), ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.COMMITTED.name(), committed.state(), committed.detail());
    assertEquals(AutomationFixture.PUSHED, committed.resultSha());

    AutomationDto carried =
        AutomationFixture.entry(
            trigger(FOLD_B, FOLD_A, List.of("src/app/__screenshots__/home.png"), WITH_DEFERRED),
            ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.FRESH.name(), carried.state(), carried.detail());
    assertTrue(carried.detail().contains("carried"), carried.detail());
    MtBump row = store.bump(UUID.fromString(carried.bumpId())).orElseThrow();
    assertEquals(BumpStatus.NOTHING_TO_DO.name(), row.status);
    assertEquals(1, triggers(), "and no second run");
  }
}

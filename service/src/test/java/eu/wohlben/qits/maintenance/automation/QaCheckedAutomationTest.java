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
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Kinds QA checks</b> (qits-1180, qits-1178): where the repository's QA names entity-diagram and
 * screenshot-baselines under {@code check-outputs:} at the fold, they are answered DEFERRED and not
 * run — but only for a caller that accepts the word. The re-run door still runs them, and its result
 * shows as it does today. Which file decides follows qits-ci: the fold's own {@code
 * release-request:} slot, else the repository's own recipe copy, else qits-ci's packaged recipe. A
 * fold with no {@code release.yml} is in {@link AutomationTriggerTest}.
 */
@QuarkusTest
class QaCheckedAutomationTest {

  /** A release.yml whose own QA slot checks both kinds. */
  private static final String OWN_SLOT_CHECKS_BOTH =
      """
      archetype: java-service
      release-request:
        - image: qits/build-images/maven-base:latest
          check-outputs: [entity-diagram, screenshot-baselines]
          script: ./mvnw verify
      """;

  /** A recipe whose QA step checks the screenshots only. */
  private static final String RECIPE_CHECKS_SCREENSHOTS =
      """
      release-request:
        - image: qits/build-images/node-browser-base:latest
          check-outputs: [screenshot-baselines]
          script: npm test
      """;

  /** A recipe whose QA step checks nothing. */
  private static final String RECIPE_CHECKS_NOTHING =
      """
      release-request:
        - image: qits/build-images/node-browser-base:latest
          script: npm test
      """;

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
    for (String fold : List.of(FOLD_A, FOLD_B)) {
      AutomationFixture.scriptFile(peers, fold, QaChecks.RELEASE_YML, OWN_SLOT_CHECKS_BOTH);
    }
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

  /** A caller that does not accept DEFERRED gets today's pre-run, whatever QA checks. */
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

  /** The answer's state for one kind. */
  private static String state(ReleaseRequestAutomationsDto answer, String kind) {
    return AutomationFixture.entry(answer, kind).state();
  }

  /**
   * No own slot and no own copy: qits-ci's packaged recipe decides. It checks the screenshots only,
   * so they are DEFERRED and the entity diagram runs before QA as today.
   */
  @Test
  void thePackagedRecipeDecidesWhenTheRepositoryNamesOnlyAnArchetype() {
    AutomationFixture.scriptFile(peers, FOLD_A, QaChecks.RELEASE_YML, "archetype: app\n");
    AutomationFixture.scriptPackagedRecipe(
        peers, "app", FakePeers.Scripted.ok(RECIPE_CHECKS_SCREENSHOTS));

    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, WITH_DEFERRED);

    assertEquals(AutomationState.DEFERRED.name(), state(answer, ScreenshotBaselinesAutomation.KIND));
    assertEquals(AutomationState.REQUESTED.name(), state(answer, EntityDiagramAutomation.KIND));
    assertEquals(1, rows(FOLD_A, EntityDiagramAutomation.KIND).size());
    assertTrue(rows(FOLD_A, ScreenshotBaselinesAutomation.KIND).isEmpty());
  }

  /** The repository's own recipe copy wins over the packaged one, as in qits-ci: no key, no defer. */
  @Test
  void anOwnRecipeCopyWithoutTheKeyKeepsThePreRun() {
    AutomationFixture.scriptFile(peers, FOLD_A, QaChecks.RELEASE_YML, "archetype: app\n");
    AutomationFixture.scriptFile(
        peers, FOLD_A, QaChecks.OWN_RECIPES + "app.yml", RECIPE_CHECKS_NOTHING);
    AutomationFixture.scriptPackagedRecipe(
        peers, "app", FakePeers.Scripted.ok(RECIPE_CHECKS_SCREENSHOTS));

    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, WITH_DEFERRED);

    assertEquals(AutomationState.REQUESTED.name(), state(answer, ScreenshotBaselinesAutomation.KIND));
    assertEquals(AutomationState.REQUESTED.name(), state(answer, EntityDiagramAutomation.KIND));
  }

  /** An own release-request slot replaces the archetype's whole: without the key, no defer. */
  @Test
  void anOwnQaSlotWithoutTheKeyKeepsThePreRun() {
    AutomationFixture.scriptFile(
        peers, FOLD_A, QaChecks.RELEASE_YML, "archetype: app\n" + RECIPE_CHECKS_NOTHING);
    AutomationFixture.scriptPackagedRecipe(
        peers, "app", FakePeers.Scripted.ok(RECIPE_CHECKS_SCREENSHOTS));

    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, WITH_DEFERRED);

    assertEquals(AutomationState.REQUESTED.name(), state(answer, ScreenshotBaselinesAutomation.KIND));
  }

  /** A packaged recipe the git host cannot serve is doubt, and doubt keeps the pre-run. */
  @Test
  void anUnreadablePackagedRecipeKeepsThePreRun() {
    AutomationFixture.scriptFile(peers, FOLD_A, QaChecks.RELEASE_YML, "archetype: app\n");
    AutomationFixture.scriptPackagedRecipe(
        peers, "app", FakePeers.Scripted.unreachable("connection refused"));

    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, WITH_DEFERRED);

    assertEquals(AutomationState.REQUESTED.name(), state(answer, ScreenshotBaselinesAutomation.KIND));
  }
}

package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_B;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_C;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_D;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtScan;
import eu.wohlben.qits.maintenance.error.NoSuchRepositoryException;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Settling a fold</b>: applicability, carry-over, the row, the coalescing of folds that arrive
 * while one runs, the estate-wide cap, and the idempotence of a repeated trigger — the front half of
 * the release-request automations (qits-978), driven through {@code AutomationService.trigger} the
 * way qits-projects' post reaches it.
 */
@QuarkusTest
class AutomationTriggerTest {

  private static final String RUN = "run-automation-trigger";

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
    for (String fold : List.of(FOLD_A, FOLD_B, FOLD_C, FOLD_D)) {
      AutomationFixture.scriptFold(peers, fold, true);
    }
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    AutomationFixture.scriptJoin(peers, REQUEST, AutomationFixture.joined(REQUEST));
    Fixture.scriptForeignBranchAt(
        peers, AutomationFixture.branch(REQUEST), AutomationFixture.BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private ReleaseRequestAutomationsDto trigger(
      String requestId, String fold, String previous, List<String> changed) {
    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            requestId,
            new AutomationService.Fold(
                Fixture.REPOSITORY, fold, previous, changed, List.of("main", "work"), null));
    queue.awaitIdle(Duration.ofSeconds(30));
    return answer;
  }

  private AutomationDto screenshots(ReleaseRequestAutomationsDto answer) {
    return answer.automations().stream()
        .filter(entry -> ScreenshotBaselinesAutomation.KIND.equals(entry.kind()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no screenshot entry in " + answer));
  }

  private List<MtBump> screenshotRows(String fold) {
    return store.automations(REQUEST, fold).stream()
        .filter(row -> ScreenshotBaselinesAutomation.KIND.equals(row.automationKind))
        .toList();
  }

  private MtBump row(AutomationDto entry) {
    return store.bump(UUID.fromString(entry.bumpId())).orElseThrow();
  }

  private int triggers() {
    return peers.bodiesFor(CiClient.TRIGGER_PATH).size();
  }

  /** Runs fold A to a COMMITTED outcome: a green run that moved the branch, joined. */
  private void commitFoldA() {
    AutomationDto entry = screenshots(trigger(REQUEST, FOLD_A, null, null));
    Fixture.scriptForeignBranchAt(
        peers, AutomationFixture.branch(REQUEST), AutomationFixture.PUSHED);
    Fixture.scriptRun(peers, RUN, "SUCCESS");
    bumps.poll(UUID.fromString(entry.bumpId()));
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(BumpStatus.SUCCEEDED.name(), row(entry).status, row(entry).message);
  }

  /**
   * A repository whose fold declares no {@code test:browser} is not listed at all — no row, no CI
   * run — which is what lets every ordinary repository release exactly as it did.
   */
  @Test
  void aRepositoryWithNoBrowserTestsIsNotListed() {
    AutomationFixture.scriptFold(peers, FOLD_A, false);

    ReleaseRequestAutomationsDto answer = trigger(REQUEST, FOLD_A, null, null);

    assertEquals(FOLD_A, answer.foldSha());
    assertTrue(
        answer.automations().stream().noneMatch(AutomationFixture::screenshots),
        "screenshots do not apply: " + answer);
    // The one kind that does is the dependency bump, and the fold's manifests are current: FRESH,
    // with no run (qits-1133).
    assertEquals(1, answer.automations().size(), answer.toString());
    assertEquals(
        AutomationState.FRESH.name(),
        AutomationFixture.entry(answer, DependencyBumpAutomation.KIND).state());
    assertTrue(screenshotRows(FOLD_A).isEmpty(), "and nothing was stored for screenshots");
    assertEquals(0, triggers(), "and no run was asked for");
  }

  /** A fold the git host cannot be asked about is UNKNOWN — answered, and not stored. */
  @Test
  void anUnreadableFoldIsUnknownAndNotStored() {
    AutomationFixture.scriptFoldUnreachable(peers, FOLD_A);

    AutomationDto entry = screenshots(trigger(REQUEST, FOLD_A, null, null));

    assertEquals(AutomationState.UNKNOWN.name(), entry.state());
    assertTrue(screenshotRows(FOLD_A).isEmpty(), "so the next ask decides again");
  }

  /** A repository that follows the convention gets one row and one run, on the kind's branch. */
  @Test
  void anApplicableKindOpensARowAndDispatchesIt() {
    AutomationDto entry = screenshots(trigger(REQUEST, FOLD_A, null, null));

    assertEquals(AutomationState.REQUESTED.name(), entry.state(), "answered before the dispatch");
    MtBump row = row(entry);
    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals(BumpMode.AUTOMATION.name(), row.mode);
    assertEquals(ScreenshotBaselinesAutomation.KIND, row.automationKind);
    assertEquals(FOLD_A, row.foldSha);
    assertEquals(REQUEST, row.releaseRequestId);
    assertEquals(BumpTrigger.FOLD.name(), row.trigger);
    assertEquals(AutomationFixture.branch(REQUEST), row.branch);
    assertEquals(AutomationFixture.BEFORE, row.resultSha, "the start head, for the ending");
    assertEquals(1, triggers());
    String payload = peers.bodiesFor(CiClient.TRIGGER_PATH).getFirst();
    assertTrue(payload.contains("\"name\":\"ReleaseRequestAutomation\""), payload);
    assertTrue(payload.contains("\"foldSha\":\"" + FOLD_A + "\""), payload);
  }

  /**
   * THE MAIN TERMINATOR. The re-fold the kind's own join causes changes only its own paths, so the
   * new fold is FRESH, carried — and no second run is asked for.
   */
  @Test
  void onlyScreenshotPathsChangedCarriesTheOutcomeWithNoRun() {
    commitFoldA();
    int before = triggers();

    AutomationDto entry =
        screenshots(
            trigger(
                REQUEST,
                FOLD_B,
                FOLD_A,
                List.of(
                    "src/app/home/__screenshots__/home.png", "src/testing/browser/renderer.txt")));

    assertEquals(AutomationState.FRESH.name(), entry.state(), entry.detail());
    assertTrue(entry.detail().contains("carried"), entry.detail());
    assertEquals(before, triggers(), "no second run for the fold its own commit made");
  }

  /** A change somebody made is not the automation's output: the fold runs. */
  @Test
  void aSourceChangeIsNotCarried() {
    commitFoldA();

    AutomationDto entry =
        screenshots(
            trigger(
                REQUEST,
                FOLD_B,
                FOLD_A,
                List.of("src/app/home/__screenshots__/home.png", "src/app/x.ts")));

    assertEquals(AutomationState.REQUESTED.name(), entry.state(), entry.detail());
    assertEquals(BumpStatus.RUNNING.name(), row(entry).status);
  }

  /** A diff nobody could read never carries. */
  @Test
  void anUnknownDiffIsNotCarried() {
    commitFoldA();

    AutomationDto entry = screenshots(trigger(REQUEST, FOLD_B, FOLD_A, null));

    assertEquals(AutomationState.REQUESTED.name(), entry.state(), entry.detail());
    assertNull(row(entry).automationOnly, "an unread diff says nothing about the fold");
  }

  /**
   * <b>Three folds arrive while one runs: only the newest waits.</b> Each newer fold supersedes the
   * waiting one before it, and the running one is left to end.
   */
  @Test
  void foldsArrivingWhileOneRunsCoalesceToTheNewest() {
    AutomationDto first = screenshots(trigger(REQUEST, FOLD_A, null, null));
    assertEquals(BumpStatus.RUNNING.name(), row(first).status);

    List<String> changed = List.of("src/app/x.ts");
    AutomationDto second = screenshots(trigger(REQUEST, FOLD_B, FOLD_A, changed));
    AutomationDto third = screenshots(trigger(REQUEST, FOLD_C, FOLD_B, changed));
    AutomationDto fourth = screenshots(trigger(REQUEST, FOLD_D, FOLD_C, changed));

    assertEquals(BumpStatus.RUNNING.name(), row(first).status, "the running one is left to end");
    assertEquals(BumpStatus.SUPERSEDED.name(), row(second).status, row(second).message);
    assertEquals(BumpStatus.SUPERSEDED.name(), row(third).status, row(third).message);
    assertEquals(BumpStatus.REQUESTED.name(), row(fourth).status, "the newest fold waits");
    assertEquals(1, triggers(), "and nothing ran beside the first");
    long waiting =
        store.waitingAutomations().stream().filter(row -> REQUEST.equals(row.releaseRequestId)).count();
    assertEquals(1, waiting);
  }

  /**
   * <b>At most two automation runs estate-wide.</b> A third request waits REQUESTED, and goes the
   * moment one of the two ends.
   */
  @Test
  void aThirdRunWaitsForTheCapAndGoesWhenOneEnds() {
    String second = "6e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b";
    String third = "7e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b";
    for (String request : List.of(second, third)) {
      AutomationFixture.scriptRequest(peers, request, "PENDING", FOLD_A);
      Fixture.scriptForeignBranchAt(
          peers, AutomationFixture.branch(request), AutomationFixture.BEFORE);
    }

    MtBump one = row(screenshots(trigger(REQUEST, FOLD_A, null, null)));
    MtBump two = row(screenshots(trigger(second, FOLD_A, null, null)));
    MtBump three = row(screenshots(trigger(third, FOLD_A, null, null)));

    assertEquals(BumpStatus.RUNNING.name(), one.status);
    assertEquals(BumpStatus.RUNNING.name(), two.status);
    assertEquals(BumpStatus.REQUESTED.name(), three.status, "the cap is two");
    assertEquals(AutomationService.MAX_RUNNING, store.runningAutomationCount());

    Fixture.scriptRun(peers, RUN, "SUCCESS");
    bumps.poll(one.id);
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(
        BumpStatus.RUNNING.name(),
        store.bump(three.id).orElseThrow().status,
        "an ending frees a slot and the waiting one goes");
  }

  private AutomationDto entityDiagram(ReleaseRequestAutomationsDto answer) {
    return answer.automations().stream()
        .filter(entry -> EntityDiagramAutomation.KIND.equals(entry.kind()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no entity-diagram entry in " + answer));
  }

  /**
   * Runs fold A's {@code entity-diagram} to a COMMITTED outcome: a green run that joined. Screenshots
   * are turned off on fold A so the fixture's qits-ci shape exercises only the kind under test.
   */
  private AutomationDto commitEntityDiagramFoldA() {
    String branch = AutomationFixture.branch(EntityDiagramAutomation.KIND, REQUEST);
    AutomationFixture.scriptFold(peers, FOLD_A, false);
    AutomationFixture.scriptEntityDiagramApplies(peers, FOLD_A);
    Fixture.scriptForeignBranchAt(peers, branch, AutomationFixture.BEFORE);

    AutomationDto entry = entityDiagram(trigger(REQUEST, FOLD_A, null, null));
    Fixture.scriptForeignBranchAt(peers, branch, AutomationFixture.PUSHED);
    Fixture.scriptRun(peers, RUN, "SUCCESS");
    bumps.poll(UUID.fromString(entry.bumpId()));
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(BumpStatus.SUCCEEDED.name(), row(entry).status, row(entry).message);
    return entry;
  }

  /**
   * THE ENTITY-DIAGRAM TERMINATOR (qits-1031). {@code docs/database/**} is the kind's own output and
   * no kind's input, so the re-fold its own join causes — changing only {@code docs/database/ci.md}
   * — is carried, with no second run.
   */
  @Test
  void onlyEntityDiagramPathsChangedCarriesTheOutcomeWithNoRun() {
    commitEntityDiagramFoldA();
    AutomationFixture.scriptFold(peers, FOLD_B, false);
    AutomationFixture.scriptEntityDiagramApplies(peers, FOLD_B);
    int before = triggers();

    AutomationDto entry =
        entityDiagram(trigger(REQUEST, FOLD_B, FOLD_A, List.of("docs/database/ci.md")));

    assertEquals(AutomationState.FRESH.name(), entry.state(), entry.detail());
    assertTrue(entry.detail().contains("carried"), entry.detail());
    assertEquals(before, triggers(), "no second run for the fold its own commit made");
  }

  /** A change under the repository's own sources is not the automation's output: the fold runs. */
  @Test
  void aSourceChangeUnderSrcMainIsNotCarriedForEntityDiagram() {
    commitEntityDiagramFoldA();
    AutomationFixture.scriptFold(peers, FOLD_B, false);
    AutomationFixture.scriptEntityDiagramApplies(peers, FOLD_B);

    AutomationDto entry =
        entityDiagram(
            trigger(
                REQUEST,
                FOLD_B,
                FOLD_A,
                List.of(
                    "docs/database/ci.md", "ci/src/main/java/eu/wohlben/qits/ci/CiReport.java")));

    assertEquals(AutomationState.REQUESTED.name(), entry.state(), entry.detail());
    assertEquals(BumpStatus.RUNNING.name(), row(entry).status);
  }

  /** The same fold posted twice is answered from the rows the first one wrote. */
  @Test
  void aRepeatedTriggerForTheSameFoldIsIdempotent() {
    AutomationDto first = screenshots(trigger(REQUEST, FOLD_A, null, null));
    AutomationDto again = screenshots(trigger(REQUEST, FOLD_A, null, null));

    assertEquals(first.bumpId(), again.bumpId());
    assertEquals(AutomationState.RUNNING.name(), again.state());
    assertEquals(1, screenshotRows(FOLD_A).size());
    assertEquals(1, triggers(), "one fold, one run");
    assertEquals(
        again.bumpId(),
        screenshots(automations.automations(REQUEST, FOLD_A)).bumpId(),
        "and the read door answers the same row");
  }

  // --- a repository no scan has read yet (qits-1118) ---------------------------------------------

  private NoSuchRepositoryException unknown(String repository) {
    return assertThrows(
        NoSuchRepositoryException.class,
        () ->
            automations.trigger(
                REQUEST,
                new AutomationService.Fold(
                    repository, FOLD_A, null, null, List.of("main", "work"), null)));
  }

  private List<MtScan> scansOf(String repository) {
    return store.scans(1000).stream().filter(scan -> repository.equals(scan.repository)).toList();
  }

  /** Holds the single worker until the latch opens, so a queued scan stays pending meanwhile. */
  private CountDownLatch holdTheWorker() {
    CountDownLatch release = new CountDownLatch(1);
    queue.submit(
        "hold the worker",
        () -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    return release;
  }

  /**
   * A repository the inventory does not hold is still a 404 — but it queues exactly one scan of
   * itself, and a second ask while that scan is pending queues none. One the catalog does not list
   * fails its scan, which closes the row, so the next ask queues another rather than waiting on a
   * pending one for ever.
   */
  @Test
  void anUnknownRepositoryQueuesOneScanAndStillAnswers404() {
    String repository = "qits-never-scanned";
    CountDownLatch release = holdTheWorker();
    try {
      NoSuchRepositoryException first = unknown(repository);
      assertEquals(404, first.statusCode());
      assertEquals(
          "no repository '" + repository + "' in the inventory yet — a scan of it is queued; ask"
              + " again shortly",
          first.getMessage());
      List<MtScan> queued = scansOf(repository);
      assertEquals(1, queued.size(), "one scan of exactly that repository: " + queued);
      assertEquals(ScanStatus.REQUESTED.name(), queued.getFirst().status);
      assertEquals(ScanTrigger.EVENT.name(), queued.getFirst().trigger);
      assertEquals(ScanScope.INTERNAL.name(), queued.getFirst().scope);

      assertEquals(404, unknown(repository).statusCode());
      assertEquals(1, scansOf(repository).size(), "a pending scan is not asked for twice");
    } finally {
      release.countDown();
    }
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(ScanStatus.FAILED.name(), scansOf(repository).getFirst().status);
    unknown(repository);
    assertEquals(2, scansOf(repository).size(), "a failed scan does not hold the next ask");
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  /**
   * The case qits-1118 is about: a repository the catalog lists and no scan has read yet. The first
   * ask is a 404 that queues its scan; once that scan has run, the next ask settles normally.
   */
  @Test
  void aNewRepositoryIsScannedAndTheNextAskSettles() {
    inventory.clear();

    unknown(Fixture.REPOSITORY);
    queue.awaitIdle(Duration.ofSeconds(60));

    MtScan scan = scansOf(Fixture.REPOSITORY).getFirst();
    assertEquals(ScanStatus.SUCCEEDED.name(), scan.status, scan.message);
    AutomationDto entry = screenshots(trigger(REQUEST, FOLD_A, null, null));
    assertEquals(AutomationState.REQUESTED.name(), entry.state(), entry.detail());
    assertEquals(1, scansOf(Fixture.REPOSITORY).size(), "and a known repository queues no scan");
  }
}

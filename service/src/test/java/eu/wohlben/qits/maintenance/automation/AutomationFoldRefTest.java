package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.error.ReleaseRequestNotOpenException;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Where an own-branch run starts</b> (qits-1158): a release request is folded onto its backing
 * branch, {@code release/<qualifiedId>} for a newer request and {@code release/<uuid>} for an older
 * one, and the run's base ref is that branch — never {@code release/} + the id by rule.
 */
@QuarkusTest
class AutomationFoldRefTest {

  private static final String RUN = "run-automation-fold-ref";

  private static final String QUALIFIED = "contract-app-rr-7";

  private static final String BACKING = "release/" + QUALIFIED;

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
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    Fixture.scriptForeignBranchAt(
        peers, AutomationFixture.branch(REQUEST), AutomationFixture.BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  /** qits-projects' answer about the request, naming its backing branch and logical id. */
  private void scriptRequestNamed(String state, String backingBranch, String qualifiedId) {
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + REQUEST,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + REQUEST + "\",\"qualifiedId\":\"" + qualifiedId
                + "\",\"backingBranch\":\"" + backingBranch + "\",\"state\":\"" + state
                + "\",\"mergedSha\":\"" + FOLD_A + "\",\"sources\":[{\"kind\":\"BRANCH\","
                + "\"name\":\"main\"},{\"kind\":\"BRANCH\",\"name\":\"work\"}]}}"));
  }

  private MtBump trigger(AutomationService.Fold fold) {
    ReleaseRequestAutomationsDto answer = automations.trigger(REQUEST, fold);
    queue.awaitIdle(Duration.ofSeconds(30));
    String id =
        answer.automations().stream()
            .filter(AutomationFixture::screenshots)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no screenshot entry in " + answer))
            .bumpId();
    return store.bump(UUID.fromString(id)).orElseThrow();
  }

  private static AutomationService.Fold fold(String backingBranch, String qualifiedId) {
    return new AutomationService.Fold(
        Fixture.REPOSITORY, FOLD_A, null, null, List.of("main", "work"), null, List.of(),
        backingBranch, qualifiedId);
  }

  private String payload() {
    List<String> bodies = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(1, bodies.size(), "one run was asked for");
    return bodies.getFirst();
  }

  /** The newest run asked for: a REQUESTED row may also be dispatched by the queue's own sweep. */
  private String lastPayload() {
    List<String> bodies = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertFalse(bodies.isEmpty(), "a run was asked for");
    return bodies.getLast();
  }

  private boolean requestRead() {
    return peers.called(PeerTarget.PROJECTS, Fixture.RELEASE_REQUESTS_PATH + "/" + REQUEST);
  }

  /** The branch the trigger names is the base ref, and the request is not read for it. */
  @Test
  void theBackingBranchTheTriggerNamesIsTheBaseRef() {
    MtBump row = trigger(fold(BACKING, QUALIFIED));

    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals(BACKING, row.foldRef);
    assertEquals(QUALIFIED, row.releaseRequestQualifiedId);
    assertEquals(REQUEST, row.releaseRequestId, "the uuid stays the key");
    assertEquals(AutomationFixture.branch(REQUEST), row.branch, "the own branch keeps the uuid");
    assertTrue(payload().contains("\"baseRef\":\"" + BACKING + "\""), payload());
    assertFalse(requestRead(), "the trigger said everything");
  }

  /** A trigger that names no branch costs one read of the request, and the answer's branch wins. */
  @Test
  void aTriggerWithNoBranchReadsItFromQitsProjects() {
    scriptRequestNamed("PENDING", BACKING, QUALIFIED);

    MtBump row = trigger(fold(null, null));

    assertEquals(BACKING, row.foldRef);
    assertEquals(QUALIFIED, row.releaseRequestQualifiedId);
    assertTrue(payload().contains("\"baseRef\":\"" + BACKING + "\""), payload());
  }

  /** A request qits-projects names no branch for is an older one, folded onto its uuid. */
  @Test
  void aRequestWithNoBranchAnywhereIsFoldedOntoItsUuid() {
    MtBump row = trigger(fold(null, null));

    assertEquals("release/" + REQUEST, row.foldRef);
    assertNull(row.releaseRequestQualifiedId);
    assertTrue(payload().contains("\"baseRef\":\"release/" + REQUEST + "\""), payload());
  }

  /**
   * Dispatch reads the branch off the row, and a row opened before the column existed falls back to
   * {@code release/<uuid>}.
   */
  @Test
  void dispatchUsesTheStoredFoldRefAndFallsBackForAnOlderRow() {
    UUID stored = open("release/stored-rr-3");
    automations.dispatch(stored);
    queue.awaitIdle(Duration.ofSeconds(30));
    assertTrue(lastPayload().contains("\"baseRef\":\"release/stored-rr-3\""), lastPayload());

    store.bumpFinished(stored, BumpStatus.FAILED, null, "done with it", Instant.now());
    peers.calls.clear();
    UUID older = open(null);
    automations.dispatch(older);
    queue.awaitIdle(Duration.ofSeconds(30));
    assertTrue(
        lastPayload().contains("\"baseRef\":\"release/" + REQUEST + "\""), lastPayload());
  }

  private UUID open(String foldRef) {
    return store.openAutomation(
        new AutomationOpening(
            Fixture.REPOSITORY,
            ScreenshotBaselinesAutomation.KIND,
            REQUEST,
            FOLD_A,
            null,
            null,
            AutomationFixture.branch(REQUEST),
            null,
            "dev",
            BumpTrigger.MANUAL,
            List.of(),
            Map.of(),
            BumpStatus.REQUESTED,
            null,
            foldRef,
            null),
        true,
        Instant.now());
  }

  /** A refusal names the request by its logical id when qits-projects gives one. */
  @Test
  void aRefusalNamesTheLogicalId() {
    scriptRequestNamed("RELEASED", BACKING, QUALIFIED);

    ReleaseRequestNotOpenException refused =
        assertThrows(
            ReleaseRequestNotOpenException.class,
            () ->
                automations.run(
                    Fixture.REPOSITORY,
                    REQUEST,
                    ScreenshotBaselinesAutomation.KIND,
                    null,
                    BumpTrigger.MANUAL));

    assertTrue(refused.getMessage().contains(QUALIFIED), refused.getMessage());
    assertFalse(refused.getMessage().contains(REQUEST), refused.getMessage());
  }
}

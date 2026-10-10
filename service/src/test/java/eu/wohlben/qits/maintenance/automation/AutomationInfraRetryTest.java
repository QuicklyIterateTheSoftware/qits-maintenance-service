package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.BEFORE;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>An automation follows qits-ci's automatic infra retry to its verdict</b> (qits-760). qits-ci
 * re-fires a run whose runner disconnected as a NEW run carrying {@code retryOfRunId} and {@code
 * autoRetry}, and leaves the original row FAILED; the automation adopts the retry and its verdict
 * decides — FRESH, COMMITTED or FAILED — through chained retries and whichever order the two are
 * seen to end in. A red run qits-ci did not retry still fails.
 */
@QuarkusTest
class AutomationInfraRetryTest {

  private static final String RUN = "030945bf-2238-4f2a-8671-d04e2ce60ced";

  private static final String RETRY = "693bebdd-8526-4107-8c04-d0460c19a16f";

  private static final String SECOND_RETRY = "7a1c2d3e-4f50-4617-8829-3a4b5c6d7e8f";

  private static final String CI_REPO = "ci-repo-0001";

  private static final String LISTING = "/ci/api/runs?repositoryId=" + CI_REPO + "&limit=100";

  /** Long enough ago that a missing retry is not still being recorded. */
  private static final Instant LONG_AGO = Instant.parse("2026-10-01T10:00:00Z");

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

  // --- the cases --------------------------------------------------------------------------------

  /** The evidence: the runner disconnected, the retry passed and found nothing to write. */
  @Test
  void anInfraFailureWhoseRetryFindsNothingIsFresh() {
    UUID id = running();
    scriptRun(RUN, "FAILED", null, false, LONG_AGO, RETRY);
    scriptRun(RETRY, "SUCCESS", RUN, true, Instant.now(), null);
    scriptListing(listed(RETRY, "SUCCESS", RUN, true));

    MtBump done = poll(id);

    assertEquals(BumpStatus.NOTHING_TO_DO.name(), done.status, done.message);
    assertTrue(done.message.startsWith("unchanged"), done.message);
    AutomationDto entry = entry();
    assertEquals(AutomationState.FRESH.name(), entry.state(), entry.detail());
    assertEquals(List.of(RUN, RETRY), entry.runIds());
  }

  @Test
  void anInfraFailureWhoseRetryPushedIsCommitted() {
    UUID id = running();
    Fixture.scriptForeignBranchAt(peers, AutomationFixture.branch(REQUEST), PUSHED);
    scriptRun(RUN, "FAILED", null, false, LONG_AGO, null);
    scriptRun(RETRY, "SUCCESS", RUN, true, Instant.now(), null);
    // No line in the step output: the link is found from the retry's side, in the listing.
    scriptListing(listed(RETRY, "SUCCESS", RUN, true));

    MtBump done = poll(id);

    assertEquals(BumpStatus.SUCCEEDED.name(), done.status, done.message);
    assertEquals(PUSHED, done.resultSha);
    AutomationDto entry = entry();
    assertEquals(AutomationState.COMMITTED.name(), entry.state());
    assertEquals(List.of(RUN, RETRY), entry.runIds());
  }

  @Test
  void anInfraFailureWhoseRetryFailsForRealFails() {
    UUID id = running();
    scriptRun(RUN, "FAILED", null, false, LONG_AGO, RETRY);
    scriptRun(RETRY, "FAILED", RUN, true, LONG_AGO, null);
    scriptListing(listed(RETRY, "FAILED", RUN, true));

    MtBump done = poll(id);

    assertEquals(BumpStatus.FAILED.name(), done.status, done.message);
    assertEquals(AutomationState.FAILED.name(), entry().state());
    assertEquals(List.of(RUN, RETRY), entry().runIds());
  }

  /**
   * A real failure: nothing re-fired it. A person's retry of it ({@code autoRetry} false) is not the
   * infra retry and is not followed either.
   */
  @Test
  void aRealFailureStillFails() {
    UUID id = running();
    scriptRun(RUN, "FAILED", null, false, LONG_AGO, null);
    scriptListing(listed(RETRY, "SUCCESS", RUN, false));

    MtBump done = poll(id);

    assertEquals(BumpStatus.FAILED.name(), done.status, done.message);
    assertTrue(done.message.contains("ended FAILED"), done.message);
    assertEquals(AutomationState.FAILED.name(), entry().state());
    assertEquals(List.of(RUN), entry().runIds());
  }

  /**
   * <b>{@code TIMED_OUT} is terminal and ends the automation FAILED</b> (qits-760 follow-up). Before
   * this fix {@code CiClient.RunState.terminal()} did not list {@code TIMED_OUT}, so a timed-out run
   * read back as "not terminal" forever and the automation never left RUNNING. It is also checked
   * for a retry the same way a FAILED run is — qits-ci never fires one for a deadline, so the listing
   * here answers nothing and the run's own TIMED_OUT verdict decides, same as a FAILED run with no
   * retry does in {@link #aRealFailureStillFails}.
   */
  @Test
  void aTimedOutRunWithNoRetryStillFails() {
    UUID id = running();
    scriptRun(RUN, "TIMED_OUT", null, false, LONG_AGO, null);
    scriptListing();

    MtBump done = poll(id);

    assertEquals(BumpStatus.FAILED.name(), done.status, done.message);
    assertTrue(done.message.contains("ended TIMED_OUT"), done.message);
    assertEquals(AutomationState.FAILED.name(), entry().state());
    assertEquals(List.of(RUN), entry().runIds());
  }

  /** A line naming a run that does not carry the link back is not believed. */
  @Test
  void aNamedRunWithoutTheLinkIsNotFollowed() {
    UUID id = running();
    scriptRun(RUN, "FAILED", null, false, LONG_AGO, RETRY);
    scriptRun(RETRY, "SUCCESS", "some-other-run", true, Instant.now(), null);
    scriptListing();

    MtBump done = poll(id);

    assertEquals(BumpStatus.FAILED.name(), done.status, done.message);
  }

  /** Two automatic retries in a chain: the last one decides. */
  @Test
  void aChainOfRetriesIsFollowedToItsEnd() {
    UUID id = running();
    scriptRun(RUN, "FAILED", null, false, LONG_AGO, RETRY);
    scriptRun(RETRY, "FAILED", RUN, true, LONG_AGO, SECOND_RETRY);
    scriptRun(SECOND_RETRY, "SUCCESS", RETRY, true, Instant.now(), null);
    scriptListing(
        listed(SECOND_RETRY, "SUCCESS", RETRY, true), listed(RETRY, "FAILED", RUN, true));

    MtBump done = poll(id);

    assertEquals(BumpStatus.NOTHING_TO_DO.name(), done.status, done.message);
    assertEquals(List.of(RUN, RETRY, SECOND_RETRY), entry().runIds());
  }

  /**
   * The original's FAILED is seen while its retry is still going: the automation stays RUNNING with
   * the retry adopted, and the retry's later SUCCESS decides.
   */
  @Test
  void theOriginalsFailureSeenBeforeTheRetryEndsWaitsForIt() {
    UUID id = running();
    scriptRun(RUN, "FAILED", null, false, LONG_AGO, RETRY);
    scriptRun(RETRY, "RUNNING", RUN, true, null, null);
    scriptListing(listed(RETRY, "RUNNING", RUN, true));

    MtBump waiting = poll(id);

    assertEquals(BumpStatus.RUNNING.name(), waiting.status, waiting.message);
    assertEquals(AutomationState.RUNNING.name(), entry().state());
    assertEquals(RUN + "," + RETRY, waiting.ciRunId);

    scriptRun(RETRY, "SUCCESS", RUN, true, Instant.now(), null);
    MtBump done = poll(id);

    assertEquals(BumpStatus.NOTHING_TO_DO.name(), done.status, done.message);
    assertEquals(List.of(RUN, RETRY), entry().runIds());
  }

  /**
   * The original is read FAILED in the moment before qits-ci has recorded its retry: a fresh red
   * with no retry yet is not a verdict, and the retry — ended by the next poll — decides.
   */
  @Test
  void aFreshFailureWaitsForTheRetryToBeRecorded() {
    UUID id = running();
    scriptRun(RUN, "FAILED", null, false, Instant.now(), null);
    scriptListing();

    MtBump waiting = poll(id);

    assertEquals(BumpStatus.RUNNING.name(), waiting.status, waiting.message);
    assertEquals(RUN, waiting.ciRunId);

    scriptRun(RUN, "FAILED", null, false, Instant.now(), RETRY);
    scriptRun(RETRY, "SUCCESS", RUN, true, Instant.now(), null);
    scriptListing(listed(RETRY, "SUCCESS", RUN, true));
    MtBump done = poll(id);

    assertEquals(BumpStatus.NOTHING_TO_DO.name(), done.status, done.message);
    assertEquals(List.of(RUN, RETRY), entry().runIds());
  }

  /** A listing that cannot be read is not "no retry": the bump stays RUNNING and asks again. */
  @Test
  void anUnreadableListingIsNotAVerdict() {
    UUID id = running();
    scriptRun(RUN, "FAILED", null, false, LONG_AGO, null);
    peers.answer(PeerTarget.CI, LISTING, FakePeers.Scripted.unreachable("connection refused"));

    MtBump waiting = poll(id);

    assertEquals(BumpStatus.RUNNING.name(), waiting.status, waiting.message);
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** Fold A's own-branch run, dispatched and RUNNING. */
  private UUID running() {
    AutomationDto entry =
        automations
            .trigger(
                REQUEST,
                new AutomationService.Fold(
                    Fixture.REPOSITORY, FOLD_A, null, null, List.of("work"), "qits-760"))
            .automations()
            .stream().filter(AutomationFixture::screenshots).findFirst().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    UUID id = UUID.fromString(entry.bumpId());
    assertEquals(BumpStatus.RUNNING.name(), store.bump(id).orElseThrow().status);
    return id;
  }

  private MtBump poll(UUID id) {
    bumps.poll(id);
    queue.awaitIdle(Duration.ofSeconds(30));
    return store.bump(id).orElseThrow();
  }

  private AutomationDto entry() {
    return automations.automations(REQUEST, FOLD_A).automations().stream().filter(AutomationFixture::screenshots).findFirst().orElseThrow();
  }

  /** One run as {@code GET /ci/api/runs/{id}} answers it, with its step output when it names a retry. */
  private void scriptRun(
      String runId,
      String status,
      String retryOf,
      boolean autoRetry,
      Instant finishedAt,
      String retriedAs) {
    String steps =
        retriedAs == null
            ? "[{\"stepIndex\":0,\"status\":\"" + status + "\",\"output\":\"building\"}]"
            : "[{\"stepIndex\":0,\"status\":\"FAILED\",\"output\":\"building\\n[infra failure"
                + " (runner workstation disconnected) — retried automatically as run "
                + retriedAs + "]\"}]";
    peers.answer(
        PeerTarget.CI,
        "/ci/api/runs/" + runId,
        FakePeers.Scripted.ok(body(runId, status, retryOf, autoRetry, finishedAt, steps)));
  }

  private void scriptListing(String... runs) {
    peers.answer(
        PeerTarget.CI,
        LISTING,
        FakePeers.Scripted.ok("{\"runs\":[" + String.join(",", runs) + "]}"));
  }

  private static String listed(String runId, String status, String retryOf, boolean autoRetry) {
    return body(runId, status, retryOf, autoRetry, null, "[]");
  }

  private static String body(
      String runId,
      String status,
      String retryOf,
      boolean autoRetry,
      Instant finishedAt,
      String steps) {
    return "{\"id\":\"" + runId + "\",\"repoId\":\"" + CI_REPO + "\",\"status\":\"" + status
        + "\",\"retryOfRunId\":" + (retryOf == null ? "null" : "\"" + retryOf + "\"")
        + ",\"autoRetry\":" + autoRetry
        + ",\"retryReason\":" + (autoRetry ? "\"infra failure\"" : "null")
        + ",\"finishedAt\":" + (finishedAt == null ? "null" : "\"" + finishedAt + "\"")
        + ",\"steps\":" + steps + "}";
  }
}

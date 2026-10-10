package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.config.UpstreamSwitch;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE RELEASE ASK FOLLOWS THE BRANCH, NOT THE RUN.
 *
 * <p>A green group bump used to ask for a release only when the branch head had moved across the
 * run. That is a question about what THIS run pushed, and the thing the ask is for is whether the
 * branch is carrying unreleased commits — two different questions, and every way they disagree
 * strands a branch permanently. NOTHING_TO_DO made no ask, nothing looked at the row again, and the
 * dispatcher then HELD the repository for a release nobody would ever request. Measured live on
 * qits-mirror-platform-service, 2026-09-16: a run recorded NOTHING_TO_DO at 01:17:19 whose branch
 * carried a commit authored 01:17:06, leaving the service the only one of nineteen adopters still on
 * an old qits-integrations — while {@code GET /maintenance/api/bumps/window} said, correctly, "its
 * branch is pushed and it waits on its own release".
 *
 * <p>So the three cases here are the three readings of a green run whose branch did not move, and
 * they are the whole of the change: <b>ahead of main</b> asks, <b>level with main</b> does not, and
 * a row that <b>ended before any of this</b> is healed by the sweep rather than by a hand.
 *
 * <p>The status stays NOTHING_TO_DO throughout, deliberately. This run really did write nothing, and
 * a reader is owed that rather than a SUCCEEDED that invents a push.
 */
@QuarkusTest
class GroupBumpEndingTest {

  private static final String RUN = "run-group-ending";

  @Inject BumpService bumps;

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject BumpDispatcher dispatcher;

  @Inject MaintenanceConfig config;

  /** The real bean behind the proxy, put back after every method: a mock lives for the run. */
  private MaintenanceConfig realConfig;

  @AfterEach
  void restoreTheConfig() {
    queue.awaitIdle(Duration.ofSeconds(30));
    UpstreamSwitch.restore(realConfig);
  }

  @BeforeEach
  void scriptThePeers() {
    // THE LEGACY GROUP PATH, pinned with the cutover switch OFF (qits-1133 R2): it ships on, which
    // retires group bumps; off is the emergency position that restores this path until R5.
    realConfig = UpstreamSwitch.install(config, false);
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, RUN);
    Fixture.scriptReleaseRequestAccepted(peers, "rr-group");
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  /**
   * Opens a group bump and lets the dispatch reach qits-ci.
   *
   * <p>The branch is armed at its sha <b>before</b> the trigger, which is what makes the head read
   * that records "before" see the same value the ending will see. That is the whole reproduction: an
   * unmoved head across a run whose branch is nevertheless ahead of main.
   */
  private UUID triggerWithBranchAt(String sha) {
    Fixture.scriptBranchAt(peers, sha);
    UUID id = bumps.request(Fixture.REPOSITORY, "dependencies", BumpTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(30));
    return id;
  }

  /** Ends the run green and lets the poll write the verdict. */
  private MtBump completeGreen(UUID id) {
    Fixture.scriptRun(peers, RUN, "SUCCESS");
    bumps.poll(id);
    queue.awaitIdle(Duration.ofSeconds(30));
    return store.bump(id).orElseThrow();
  }

  /**
   * The bug, from the front: green, unmoved, and ahead of main — so the release is asked for anyway.
   */
  @Test
  void aBranchThatDidNotMoveButIsAheadOfMainIsStillAskedToBeReleased() {
    UUID id = triggerWithBranchAt(Fixture.BUMPED_SHA);

    MtBump done = completeGreen(id);

    assertEquals(
        BumpStatus.NOTHING_TO_DO.name(),
        done.status,
        "this run pushed nothing and the status must keep saying so");
    assertNotNull(
        done.releaseRequestId,
        "…but the branch is ahead of main, so a release was asked for: " + done.message);
    assertEquals("rr-group", done.releaseRequestId);
    // The message is the ASK's, not the ending's: `note` recomposes the sentence from the frozen
    // change list every time the ask is retried, which is what keeps the column from growing a line
    // per tick during an outage. So `status` is where "this run pushed nothing" is recorded, and
    // asserting the ending's wording here would be asserting against a string that is designed to
    // be replaced.
    assertEquals(

        1,
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).size(),
        "exactly one ask left this process");
  }

  /**
   * The case the old wording always had right, and the one that must not regress into a request
   * somebody has to close by hand: the branch exists and is exactly main.
   *
   * <p>{@code Fixture.scriptScan} answers main at {@code HEAD_SHA}, so arming the branch there is a
   * branch with nothing on it — which is what a first bump that found every version already correct
   * really leaves behind.
   */
  @Test
  void aBranchThatIsLevelWithMainIsNotAskedToBeReleased() {
    UUID id = triggerWithBranchAt(Fixture.HEAD_SHA);

    MtBump done = completeGreen(id);

    assertEquals(BumpStatus.NOTHING_TO_DO.name(), done.status);
    assertNull(done.releaseRequestId, "there is nothing on the branch to release");
    assertTrue(
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).isEmpty(),
        "and nothing was asked of qits-projects at all");
  }

  /**
   * THE SELF-HEAL. A NOTHING_TO_DO row whose ask did not land is sitting with a pushed branch and an
   * empty column, and until this change nothing would ever have looked at it again — only SUCCEEDED
   * rows were swept, so the live branch sat from 01:17 until somebody noticed. That is the same
   * shape as a row left behind by the old ending, and the same sweep is what reaches both.
   *
   * <p>Staged through an unreachable qits-projects rather than by editing the row, because that is
   * the real way a green ending leaves the column empty: a retryable answer writes the message and
   * deliberately leaves the id alone for the next tick.
   *
   * <p>Driven through {@code retryRelease} directly rather than through the scheduler: the suite's
   * clock is off, so a test that waited for a tick would be indistinguishable from one that hung.
   */
  @Test
  void aBumpWhoseAskDidNotLandIsHealedByTheSweep() {
    Fixture.scriptReleaseRequestUnreachable(peers);
    UUID id = triggerWithBranchAt(Fixture.BUMPED_SHA);

    MtBump done = completeGreen(id);
    assertEquals(BumpStatus.NOTHING_TO_DO.name(), done.status);
    assertNull(done.releaseRequestId, "qits-projects was away, so the ask is still owed");

    assertTrue(
        store.bumpsOwedARelease().stream().anyMatch(row -> row.id.equals(id)),
        "and the sweep has to see a NOTHING_TO_DO row before it can heal it");
    Fixture.scriptReleaseRequestAccepted(peers, "rr-healed");
    bumps.retryRelease(id);
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(
        "rr-healed",
        store.bump(id).orElseThrow().releaseRequestId,
        "the stranded branch got its release request without anybody touching it");
  }

  /**
   * <b>A WITHDRAWN REQUEST IS NO REQUEST, AND THE SWEEP ASKS AGAIN</b> (owner decision 2026-10-04,
   * qits-886). Live: qits-maintenance-frontend's bump pointed at a request a person withdrew after an
   * infrastructure-red gate, its branch one commit ahead of main and no request open — and nothing
   * would ever re-ask, because the sweep only reads rows whose column is empty and no writer could
   * empty it.
   *
   * <p>The whole chain, end to end: the dispatcher's hold reads WITHDRAWN and clears the id; the row
   * is back on the sweep's listing; {@code retryRelease} finds the branch PUSHED and ahead of main
   * and asks qits-projects, and the fresh request is what the row records.
   */
  @Test
  void aBumpWhoseRequestWasWithdrawnIsReAskedByTheSweep() {
    UUID id = triggerWithBranchAt(Fixture.BUMPED_SHA);
    MtBump done = completeGreen(id);
    assertEquals("rr-group", done.releaseRequestId);

    Fixture.scriptReleaseRequestState(peers, "rr-group", "WITHDRAWN", "withdrawn by a person");
    BumpDispatcher.Assessment assessment = dispatcher.assess();
    assertTrue(assessment.stalled().isEmpty(), "a withdrawal is not a stall");
    assertNull(
        store.bump(id).orElseThrow().releaseRequestId,
        "the withdrawn request is forgotten: " + store.bump(id).orElseThrow().message);
    assertTrue(
        store.bumpsOwedARelease().stream().anyMatch(row -> row.id.equals(id)),
        "and the sweep sees the row again");

    Fixture.scriptReleaseRequestAccepted(peers, "rr-fresh");
    bumps.retryRelease(id);
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(
        "rr-fresh",
        store.bump(id).orElseThrow().releaseRequestId,
        "the branch got a fresh release request without anybody pressing anything");
    assertEquals(
        2,
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).size(),
        "the ending's ask, and the sweep's one after the withdrawal");
  }
}

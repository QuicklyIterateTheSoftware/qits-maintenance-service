package eu.wohlben.qits.maintenance.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.bump.BumpOrder;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.pending.Change;
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
 * THE GATE: bumps leave as many at a time as qits-ci has free slots, and none when it has none.
 *
 * <p>The subject here is the DISPATCH DECISION rather than the selection — which repository goes
 * first is {@code BumpOrderTest}'s, and it is stated there against a graph rather than against this
 * fixture's one repository. What this pins is the part no unit test can: that the window, the CI
 * queue snapshot, its runners' slots and our REQUESTED bumps are actually wired to the thing that
 * sends.
 *
 * <p><b>Every method drives {@link BumpDispatcher#tick()} by hand.</b> The suite's scheduler is off,
 * so a test that waited for the timer would be indistinguishable from a test that hung.
 */
@QuarkusTest
class BumpDispatchTest {

  @Inject BumpDispatcher dispatcher;

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
    Fixture.scriptCiAccepts(peers, "run-dispatched");
    Fixture.scriptReleaseRequestAccepted(peers, "rr-dispatched");
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
    dispatcher.close("a fresh test");
  }

  /** The one bump a tick sent, failing when it sent none or several. */
  private static UUID only(List<UUID> sent) {
    assertEquals(1, sent.size(), "exactly one bump was expected: " + sent);
    return sent.get(0);
  }

  private boolean bumped() {
    queue.awaitIdle(Duration.ofSeconds(30));
    return !store.bumps(Fixture.REPOSITORY, 50).isEmpty();
  }

  /**
   * <b>THE FIFTH LIVE FAILURE, AND THE WHOLE OF THIS FIX: DEBT ARMS THE DISPATCH, NOT THE HOUR.</b>
   * On 2026-09-11 {@code @qits/ui-components} was cut at 10:44, fifteen repositories were owed by
   * 10:45 and at 17:20 none of them had been sent: every gate would have passed, but the first one
   * could only be opened by a 02:00 cron. The intended primary upgrade path — something becomes
   * owed, qits-ci goes idle, the bump is dispatched — had never once run on its own.
   *
   * <p><b>So this test never calls {@link BumpDispatcher#open}</b>, and it is the exact scenario of
   * the ticket: no window row, an owed repository, an idle queue. The tick sends, and the window it
   * opened for itself is there afterwards.
   */
  @Test
  void anOwedBumpAndAnIdleQueueDispatchWithNoWindowAndNoCron() {
    Fixture.scriptCiQueueEmpty(peers);
    assertTrue(store.bumpWindow().isEmpty(), "no cron has run and nobody pressed the door");

    assertTrue(!dispatcher.tick().isEmpty(), "the debt is the reason, and it is enough");
    assertTrue(bumped());
    assertTrue(
        store.bumpWindow().isPresent(),
        "and the window is a consequence of the debt rather than of an hour");
  }

  /** Nothing owed is still nothing dispatched, and it opens no window to find that out. */
  @Test
  void nothingIsDispatchedWhenNothingIsOwed() {
    Fixture.scriptCiQueueEmpty(peers);
    inventory.clearLatest();

    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped());
    assertTrue(store.bumpWindow().isEmpty(), "a window with nothing to hand out is not opened");
  }

  /**
   * The whole point: a busy qits-ci is not handed another build. <b>And it is asked without a
   * window row</b>, because debt is what arms this now — the capacity gate is the one that decides
   * whether the owed work goes, and it is unchanged.
   */
  @Test
  void aFullQueueDispatchesNothingAndAFreeSlotDispatchesOne() {
    Fixture.scriptCiQueue(peers, 2);

    assertTrue(dispatcher.tick().isEmpty(), "two runs are active on two slots");
    assertFalse(bumped());

    // A slot frees, and the same tick that declined now sends.
    Fixture.scriptCiQueueEmpty(peers);
    assertTrue(!dispatcher.tick().isEmpty(), "a free slot is what it was waiting for");
    assertTrue(bumped());
  }

  /**
   * <b>UNREADABLE IS BUSY.</b> A gate that read "I could not ask" as "nothing is going" would fire
   * the whole night's bumps at the one moment qits-ci is least able to say so — which is worse than
   * the stampede it replaced, because it would be aimed at a service already in trouble.
   */
  @Test
  void aQueueThatCannotBeReadIsTreatedAsBusy() {
    peers.answer(
        PeerTarget.CI, CiClient.QUEUE_PATH, FakePeers.Scripted.unreachable("connection refused"));
    dispatcher.open(Instant.now());

    assertEquals("CI_UNREADABLE", dispatcher.explain(Instant.now()).outcome());
    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped(), "nothing is dispatched blind");
  }

  /**
   * A snapshot that answered but is missing a half is not an empty queue either — a qits-ci that
   * stopped reporting its runners has not stopped running anything.
   */
  @Test
  void aSnapshotMissingItsRunnersIsUnreadableNotEmpty() {
    peers.answer(
        PeerTarget.CI,
        CiClient.QUEUE_PATH,
        FakePeers.Scripted.ok("{\"running\":[],\"queued\":[]}"));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_UNREADABLE", decision.outcome());
    assertTrue(decision.summary().contains("runners"), decision.summary());
    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped());
  }

  /**
   * <b>A REQUESTED BUMP OF OURS CONSUMES A SLOT.</b> It is a build this service asked for that
   * qits-ci has not accepted yet, so it is in no listing qits-ci answers — counting only qits-ci's
   * runs would hand the same free slot out twice. One slot, nothing in qits-ci, one bump of ours
   * still REQUESTED: nothing is free.
   */
  @Test
  void aRequestedBumpOfOursConsumesASlot() {
    owes("qits-other-service", "eu.wohlben.qits:qits-other");
    UUID requested =
        store.openBump(
            "qits-other-service",
            GroupConfig.DEFAULT_GROUP,
            "maintenance/" + GroupConfig.DEFAULT_GROUP,
            "dev",
            BumpTrigger.MANUAL,
            List.of(),
            Instant.now());
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_BUSY", decision.outcome());
    assertEquals(1, decision.slots());
    assertEquals(0, decision.free());
    assertEquals(0, decision.ciActive());
    assertEquals(1, decision.inFlight());
    assertTrue(
        decision.summary().contains("1 slot(s), 0 active run(s) and 1 bump(s) waiting to reach it"),
        decision.summary());
    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped(), "the one slot is spoken for");

    // qits-ci accepts it: the bump is RUNNING and its run is in qits-ci's listing now. Counted
    // there and NOT again here — two slots, one taken, one free.
    store.bumpDispatched(requested, "e-other", List.of("run-other"));
    Fixture.scriptCiQueue(peers, 1, Fixture.runner("qits-ci", 2, true, false));
    assertEquals(1, dispatcher.explain(Instant.now()).free(), "a RUNNING bump is not counted twice");
    assertEquals(1, dispatcher.tick().size());
    assertTrue(bumped());
  }

  /** The ordinary ending: the window shuts itself the moment nothing is owed. */
  @Test
  void theWindowClosesItselfWhenEverythingOwedHasBeenAskedFor() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());
    assertTrue(dispatcher.windowOpen(Instant.now()));

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    // The bump has to END before the window can: while one of ours is still going the window stays
    // open, so it does not shut on the night's last bump while it runs. Ended here rather than
    // driven through qits-ci, because what this test is about is the window and not the run.
    store.bumpFinished(id, BumpStatus.NOTHING_TO_DO, "SUCCESS", "ended by the test", Instant.now());
    inventory.clearLatest();

    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(dispatcher.windowOpen(Instant.now()), "nothing is owed, so the night is over");
  }

  /** …and while that last bump is still going, an empty owed list does not close the window. */
  @Test
  void theWindowStaysOpenWhileTheLastBumpIsStillGoing() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clearLatest();

    assertEquals("NOTHING_OWED", dispatcher.explain(Instant.now()).outcome());
    assertTrue(dispatcher.tick().isEmpty());
    assertTrue(dispatcher.windowOpen(Instant.now()), "one of ours is still running");
  }

  /**
   * <b>THE LIVE FAILURE, END TO END.</b> At 05:52 one repository was dispatched, came back
   * SUCCEEDED with a release request open, and was dispatched again thirty seconds later — then
   * again, and again, every one of those a CI run that could only answer NOTHING_TO_DO. Pending is
   * read off the pins on <i>main</i>, and main does not move until that release lands, so the
   * repository is genuinely still owed; what it is not, is sendable.
   *
   * <p>The two assertions are one fact each and both matter: no second bump, and <b>the window is
   * still open</b>. Held has to keep counting as owed, or a night whose chain is waiting on releases
   * would declare itself finished and lose everything behind it.
   */
  @Test
  void aBumpWhoseBranchIsWaitingOnItsReleaseIsNotDispatchedAgain() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(
        id, BumpStatus.SUCCEEDED, "SUCCESS", "the branch is pushed and its release is open", Instant.now());

    assertTrue(dispatcher.tick().isEmpty(), "the same changes have already been asked for");
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size(), "and no second row was written");
    assertTrue(
        dispatcher.windowOpen(Instant.now()),
        "held is still owed: the window must not close on a chain that is only half sent");
  }

  /**
   * The same hold for the other ending that leaves the pins where they were. NOTHING_TO_DO is what
   * the re-dispatch loop kept producing, and a run that found nothing to write is the strongest
   * possible evidence that sending it once more would find nothing either.
   */
  @Test
  void aBumpThatFoundNothingToDoAlsoHoldsItsRepositoryBack() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(
        id, BumpStatus.NOTHING_TO_DO, "SUCCESS", "the versions were already there", Instant.now());

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size());
  }

  /**
   * <b>A FAILURE HOLDS NOTHING.</b> There is no branch waiting on a release — the run went red, or
   * qits-ci recorded no run at all — so the work is owed in the plainest sense and the next tick
   * inside the window is exactly the retry.
   */
  @Test
  void aFailedBumpIsRetriedRatherThanHeld() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.FAILED, "FAILED", "the run went red", Instant.now());

    assertTrue(!dispatcher.tick().isEmpty(), "a failure must stay retryable");
    assertEquals(2, store.bumps(Fixture.REPOSITORY, 50).size());
  }

  /**
   * <b>THE SECOND LIVE FAILURE: THE NIGHT MUST SURVIVE THIS SERVICE'S OWN REDEPLOY.</b> On
   * 2026-09-10 nineteen bumps went out one at a time from 06:32, and one of them was the bump of
   * {@code qits-maintenance-platform-service} itself. It succeeded at 08:01, its release deployed at
   * 08:11, the container was replaced — and with the window held in a field, the night ended there:
   * eleven repositories owed, four hours of window left, and no cron until 02:00.
   *
   * <p><b>So this test never calls {@link BumpDispatcher#open}.</b> The window is written to the
   * store as a previous process left it, and the dispatcher — which is what a restarted one is —
   * picks the night up from the row.
   */
  @Test
  void aWindowOpenedBeforeARestartIsResumedFromTheStore() {
    Fixture.scriptCiQueueEmpty(peers);
    Instant now = Instant.now();
    store.openBumpWindow(now.minus(Duration.ofHours(2)), now.plus(Duration.ofHours(4)));

    assertTrue(dispatcher.windowOpen(now), "the row is the window; the field was only a cache");
    assertTrue(!dispatcher.tick().isEmpty(), "the night carries on where the last process left it");
    assertTrue(bumped());
  }

  /**
   * <b>AN EXPIRY IS A RESET, NOT A GUILLOTINE.</b> An expired row is closed — it is never read as a
   * window that is still running — and then the same tick asks the only question that decides
   * anything now: is something owed. It is, so a fresh window opens and the chain carries on, which
   * is the half the old behaviour got wrong: what a window did not reach by its sixth hour was
   * silently dropped until the next night, and one {@code @qits/ui-components} release is roughly
   * twenty-eight dispatches deep.
   */
  @Test
  void anExpiredWindowIsReplacedRatherThanDroppingTheWorkItDidNotReach() {
    Fixture.scriptCiQueueEmpty(peers);
    Instant now = Instant.now();
    Instant stale = now.minus(Duration.ofHours(1));
    store.openBumpWindow(now.minus(Duration.ofHours(7)), stale);

    assertFalse(dispatcher.windowOpen(now), "the row is over and is not treated as open");
    assertTrue(!dispatcher.tick().isEmpty(), "the work is still owed, so it still goes");
    assertTrue(bumped());
    assertTrue(
        store.bumpWindow().orElseThrow().isAfter(stale),
        "and the expired row was replaced rather than re-read every tick");
  }

  /**
   * <b>THE FOURTH LIVE FAILURE: A HOLD THAT WAITS FOR A RELEASE THAT IS NOT COMING.</b> On
   * 2026-09-10 the night's twentieth bump pushed its branch at 07:05 and its release request was
   * REJECTED nine minutes later — the repository's gating build does not compile. Four hours on, the
   * window was still open, qits-ci was idle, that one repository was the only thing owed, nothing
   * had been dispatched since 10:54, and no line anywhere said why. A dead release and a release in
   * flight were the same state, and a dead one holds for ever.
   *
   * <p>Three assertions, one fact each: it is not dispatched again (the branch already carries the
   * change, so a fresh bump could only answer NOTHING_TO_DO), it is <b>not a candidate at all</b> —
   * so it stops holding its consumers back — and the window therefore <b>closes</b>, because a night
   * must not stay open for work that cannot be done.
   */
  @Test
  void aBumpWhoseReleaseWasRejectedStopsBeingWaitedOnAndSaysSo() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, "rr-rejected", "the release request rr-rejected is PENDING");
    Fixture.scriptReleaseRequestState(
        peers, "rr-rejected", "REJECTED", "Gating run 3248b7f4 finished FAILED");

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("ALL_STALLED", decision.outcome());
    assertEquals(0, decision.owed(), "a release that has stopped is not work this gate can do");
    assertEquals(1, decision.stalled().size());
    assertEquals(Fixture.REPOSITORY, decision.stalled().get(0).repository());
    assertEquals("REJECTED", decision.stalled().get(0).state());
    assertTrue(
        decision.stalled().get(0).reason().contains("3248b7f4"),
        "and it carries qits-projects' own sentence, which is the failing gating run");

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size(), "no second, pointless CI run");
    assertFalse(
        dispatcher.windowOpen(Instant.now()),
        "and the night ends rather than standing open on a build that needs a person");
    assertEquals(
        "rr-rejected",
        store.bump(id).orElseThrow().releaseRequestId,
        "a rejection is re-armed by qits-projects, so the request is kept — only WITHDRAWN is"
            + " forgotten");
  }

  /**
   * <b>WITHDRAWN COUNTS AS NO REQUEST AT ALL</b> (owner decision 2026-10-04, qits-886). It used to
   * stall like a rejection, but qits-projects re-arms nothing it withdrew, and nothing here could
   * put the request id back to null — so the sweep never re-asked, and qits-maintenance-frontend sat
   * one commit ahead of main with no request open and the window door calling it stalled. Now the
   * id is cleared, the observation with it, and the candidate is HELD for the sweep's fresh ask —
   * not re-dispatched, since its branch already carries the change.
   */
  @Test
  void aWithdrawnReleaseRequestIsForgottenAndHeldForAFreshAsk() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, "rr-withdrawn", "the release request rr-withdrawn is PENDING");
    Fixture.scriptReleaseRequestState(peers, "rr-withdrawn", "WITHDRAWN", "withdrawn by a person");

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertTrue(decision.stalled().isEmpty(), "a withdrawal is not a stall");
    assertEquals(1, decision.owed());
    assertEquals(1, decision.held());

    var row = store.bump(id).orElseThrow();
    assertEquals(null, row.releaseRequestId, "the request is forgotten, so the sweep asks again");
    assertEquals(
        null, row.releaseState, "and no WITHDRAWN is left beside a null id to read as stopped");
    assertTrue(row.message.contains("withdrawn"), row.message);
    assertTrue(
        store.bumpsOwedARelease().stream().anyMatch(owed -> owed.id.equals(id)),
        "the row is back on the release sweep's listing");

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size(), "no second, pointless CI run");
    assertTrue(dispatcher.windowOpen(Instant.now()), "a fresh request is still coming");
  }

  /**
   * <b>FINALIZED SHIPPED</b>, and reading it as stalled listed qits-events-service as waiting on a
   * release that stopped on the morning it had released (2026-10-04). It holds like RELEASED: the
   * next scan of main empties the pending set.
   */
  @Test
  void aFinalizedReleaseHoldsRatherThanStalls() {
    assertAShippedReleaseHolds("rr-finalized", "FINALIZED");
  }

  /**
   * <b>OBSOLETE SHIPPED TOO</b>: qits-projects marks a request OBSOLETE only once it is RELEASED and
   * before it finalized, so a version was cut.
   */
  @Test
  void anObsoleteReleaseHoldsRatherThanStalls() {
    assertAShippedReleaseHolds("rr-obsolete", "OBSOLETE");
  }

  private void assertAShippedReleaseHolds(String requestId, String state) {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, requestId, "the release request " + requestId + " is PENDING");
    Fixture.scriptReleaseRequestState(peers, requestId, state, null);

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertTrue(decision.stalled().isEmpty(), state + " shipped and is not a stall");
    assertEquals(1, decision.owed());
    assertEquals(1, decision.held());
    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size());
    assertTrue(dispatcher.windowOpen(Instant.now()));
    assertEquals(requestId, store.bump(id).orElseThrow().releaseRequestId, "nothing to re-ask");
    assertEquals(state, store.bump(id).orElseThrow().releaseState);
  }

  /**
   * <b>The ordinary case is unchanged, and that is the half worth pinning.</b> A release that is
   * still PENDING is exactly what a hold is for: not dispatched, still owed, window still open.
   */
  @Test
  void aReleaseStillOnItsWayHoldsExactlyAsItDidBefore() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, "rr-open", "the release request rr-open is PENDING");
    Fixture.scriptReleaseRequestState(peers, "rr-open", "PENDING", null);

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size());
    assertTrue(dispatcher.windowOpen(Instant.now()), "something is still coming");
    assertEquals(
        "PENDING",
        store.bump(id).orElseThrow().releaseState,
        "and what was read is on the row, so a bump standing for hours explains itself");
  }

  /**
   * <b>UNREADABLE IS NOT STALLED</b>, the same ruling the CI queue gets one gate down: a peer that
   * could not be asked is evidence about nothing. Reading it as "the release has stopped" would drop
   * a repository out of the night — and let its consumers build against the old pin — because
   * qits-projects restarted.
   */
  @Test
  void aReleaseRequestThatCannotBeReadIsHeldRatherThanStalled() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, "rr-silent", "the release request rr-silent is PENDING");
    Fixture.scriptReleaseRequestStateUnreachable(peers, "rr-silent");

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals(1, decision.owed());
    assertEquals(1, decision.held());
    assertTrue(decision.stalled().isEmpty());
    assertTrue(dispatcher.tick().isEmpty());
    assertTrue(dispatcher.windowOpen(Instant.now()));
  }

  /**
   * <b>A stall is not a verdict.</b> qits-projects re-arms REJECTED back to PENDING on the next
   * merged sha — a push to the branch, a sibling's release, a pending tag reaching main — so the
   * answer is asked again on every tick and a request that came back to life is held again with
   * nothing to unwind. Recording the rejection would have kept the repository out of every night
   * after the thing that rejected it was fixed.
   */
  @Test
  void aRejectionThatIsReArmedIsWaitedOnAgain() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, "rr-rearmed", "the release request rr-rearmed is PENDING");
    Fixture.scriptReleaseRequestState(peers, "rr-rearmed", "REJECTED", "a red gate");
    assertEquals(1, dispatcher.explain(Instant.now()).stalled().size());

    // Somebody pushes the fix; the fold re-arms.
    Fixture.scriptReleaseRequestState(peers, "rr-rearmed", "PENDING", null);
    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertTrue(decision.stalled().isEmpty());
    assertEquals(1, decision.held(), "held again, with no state of ours to undo");
  }

  /**
   * <b>"FIFTEEN OWED, NOTHING SENT" AND "THE SCHEDULER IS DEAD" MUST NOT LOOK THE SAME.</b> The
   * bump listing holds only bumps that were dispatched and the window door used to 404 with no
   * window open, so between two dispatches this service said nothing at all about the work it was
   * holding. The owed set is answered in dispatch order with each entry's reason — here, from
   * behind a busy queue and with no window row anywhere.
   */
  @Test
  void theOwedSetIsReportedWithItsReasonsWhileNothingIsBeingDispatched() {
    Fixture.scriptCiQueue(peers, 2);

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_BUSY", decision.outcome());
    assertEquals(1, decision.owed());
    assertEquals(1, decision.queue().size());
    assertEquals(Fixture.REPOSITORY, decision.queue().get(0).repository());
    assertEquals("READY", decision.queue().get(0).reason());
    assertTrue(store.bumpWindow().isEmpty(), "and reading changed nothing");
  }

  /** The same answer at the door, which is where an operator asks it — 200, not a 404. */
  @Test
  void theWindowDoorAnswersTheOwedSetWithNoWindowOpen() {
    Fixture.scriptCiQueue(peers, 2);

    io.restassured.RestAssured.given()
        .get("/maintenance/api/bumps/window")
        .then()
        .statusCode(200)
        .body("open", org.hamcrest.Matchers.equalTo(false))
        .body("openedAt", org.hamcrest.Matchers.nullValue())
        .body("outcome", org.hamcrest.Matchers.equalTo("CI_BUSY"))
        .body("slots", org.hamcrest.Matchers.equalTo(2))
        .body("free", org.hamcrest.Matchers.equalTo(0))
        .body("ciActive", org.hamcrest.Matchers.equalTo(2))
        .body("picks", org.hamcrest.Matchers.empty())
        .body("owed", org.hamcrest.Matchers.equalTo(1))
        .body("queue[0].repository", org.hamcrest.Matchers.equalTo(Fixture.REPOSITORY))
        .body("queue[0].reason", org.hamcrest.Matchers.equalTo("READY"));
  }

  /**
   * <b>THE SIXTH LIVE FAILURE: THE ALPHABET WAS THE TIEBREAK, AND IT STARVED THE SAME REPOSITORIES
   * EVERY NIGHT.</b> {@code assess} walked {@code MaintenanceStore.repositories()}, which sorts by
   * name, and {@code BumpOrder} takes the first free candidate in the order it was handed — so among
   * repositories that are equally ready the arbiter was the first letter of the name. One bump went
   * at a time then and each is held until its own release lands, so an estate-wide fan-out drained
   * at roughly one repository every five to fifteen minutes and the end of the alphabet was the end
   * of every night. Measured 2026-09-13: {@code qits-projects-daemon} and {@code qits-workspace-daemon}
   * consume the identical two jars from one {@code qits-coding-agents} release; the first was bumped
   * at 19:53 and the second at 21:28, nine repositories later, for no reason but its name.
   *
   * <p>So the two repositories here are equally ready and deliberately named so that the alphabet
   * would answer wrongly: {@code qits-zzz-daemon} was last reached by the clock a fortnight ago and
   * {@code qits-aaa-daemon} an hour ago, and it is the starved one that goes.
   */
  @Test
  void theCandidateTheClockReachedLongestAgoGoesFirstEvenWhenItsNameSortsLast() {
    inventory.clear();
    owes("qits-aaa-daemon", "eu.wohlben.qits:qits-agents-early");
    owes("qits-zzz-daemon", "eu.wohlben.qits:qits-agents-late");
    lastReachedByTheClock("qits-aaa-daemon", Instant.now().minus(Duration.ofHours(1)));
    lastReachedByTheClock("qits-zzz-daemon", Instant.now().minus(Duration.ofDays(14)));

    List<String> order =
        dispatcher.assess().candidates().stream().map(BumpOrder.Candidate::repository).toList();
    assertEquals(
        List.of("qits-zzz-daemon", "qits-aaa-daemon"),
        order,
        "equally ready, so the one waiting longest goes — not the one earliest in the alphabet");
  }

  /**
   * <b>AND THE TOPOLOGY STILL OVERRULES IT, which is the half that must not have moved.</b> The
   * recency tiebreak decides only among candidates that are equally ready; an upstream that is
   * itself owed a bump goes before its consumer whatever the clock last did to either, because the
   * consumer's {@code to} is not worth writing until that upstream's release exists. Here the
   * consumer is both earlier in the alphabet and starved — a fortnight since its last night against
   * the upstream's hour — and it still waits, blocked by the submodule it carries.
   */
  @Test
  void anOwedUpstreamStillGoesBeforeItsConsumerHoweverLongTheConsumerHasWaited() {
    inventory.clear();
    owes("qits-zzz-lib", "eu.wohlben.qits:qits-agents-lib");
    owesItsSubmodule("qits-aaa-consumer", "qits-zzz-lib");
    lastReachedByTheClock("qits-zzz-lib", Instant.now().minus(Duration.ofHours(1)));
    lastReachedByTheClock("qits-aaa-consumer", Instant.now().minus(Duration.ofDays(14)));
    Fixture.scriptCiQueueEmpty(peers);

    assertEquals(
        "qits-aaa-consumer",
        dispatcher.assess().candidates().get(0).repository(),
        "the starved one IS first in the order handed to BumpOrder — recency put it there");

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals(
        "qits-zzz-lib",
        decision.pick().candidate().repository(),
        "and it is still not the pick: the thing it waits on has to be released first");
    assertTrue(
        decision.queue().stream()
            .anyMatch(
                owed ->
                    "qits-aaa-consumer".equals(owed.repository())
                        && "BLOCKED".equals(owed.reason())
                        && owed.detail().contains("qits-zzz-lib")),
        "and the consumer says what it is waiting for");
  }

  /**
   * A repository with one INTERNAL maven pin a release has moved past — the plainest possible
   * candidate, built through the store rather than through a scan because what these two tests are
   * about is the ORDER of several repositories and the fixture describes exactly one.
   */
  private void owes(String repository, String dependency) {
    scanned(
        repository,
        ParsedPin.of(
            Ecosystem.MAVEN, "pom.xml", dependency, "2026.901.1", null, "dependency:" + dependency));
    store.recordLatestIfNewer(Ecosystem.MAVEN, dependency, "2026.913.1", "test", Instant.now());
  }

  /**
   * …and one whose pending change is the SUBMODULE it carries, which is the edge {@link BumpOrder}
   * reads by name rather than through the artifact ledger: a gitlink's {@code name} IS the
   * repository it pins.
   */
  private void owesItsSubmodule(String repository, String submodule) {
    scanned(
        repository,
        ParsedPin.of(
            Ecosystem.GITLINK,
            ".gitmodules",
            submodule,
            "1111111111111111111111111111111111111111",
            null,
            "gitlink:webui"));
    // The pin is a commit and the verdict is a DIFFERENCE, so the latest row has to carry the sha
    // its release was cut from — a version alone leaves nothing to compare and nothing pending.
    store.recordLatestIfNewer(
        Ecosystem.GITLINK,
        submodule,
        "2026.913.1",
        GitlinkSha.of("2222222222222222222222222222222222222222"),
        Instant.now());
  }

  private void scanned(String repository, ParsedPin pin) {
    store.replaceInventory(
        repository,
        Fixture.PROJECT,
        "catalog-" + repository,
        null,
        "main",
        RepositoryStatus.OK,
        "sha-" + repository,
        null,
        List.of(pin),
        List.of(GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
  }

  /**
   * One night's scheduled bump of this repository, ended.
   *
   * <p><b>It carries no changes on purpose.</b> The row is history — what the tiebreak reads — and a
   * row whose changes matched the pending set would HOLD the repository instead, which is a
   * different rule and not the one under test here.
   */
  private void lastReachedByTheClock(String repository, Instant at) {
    UUID id =
        store.openBump(
            repository,
            GroupConfig.DEFAULT_GROUP,
            "maintenance/" + GroupConfig.DEFAULT_GROUP,
            "dev",
            BumpTrigger.SCHEDULED,
            List.of(),
            at);
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "a night that has landed", at);
  }

  /**
   * <b>THE HOLD IS ON THE CHANGES, NOT ON THE REPOSITORY.</b> An upstream released while the first
   * branch was waiting, so the pending set is no longer the set that was sent — that is a different
   * bump, and refusing it would sit on a genuinely new version until the window expired.
   */
  @Test
  void aPendingSetThatMovedSinceTheLastBumpGoesAgain() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = only(dispatcher.tick());
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    assertTrue(dispatcher.tick().isEmpty(), "held, until something moves");

    // One of the dependencies it just asked for releases again. Nothing else about the repository
    // changes: the same group, the same branch, one different `to`.
    Change moved =
        BumpService.changes(store.bump(id).orElseThrow()).stream()
            .filter(change -> Ecosystem.MAVEN.wireName().equals(change.ecosystem()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the internal group carries a maven change"));
    store.recordLatestIfNewer(
        Ecosystem.MAVEN, moved.name(), "2029.101.1", "test", Instant.now());

    assertTrue(!dispatcher.tick().isEmpty(), "a new upstream release is a new bump");
    assertEquals(2, store.bumps(Fixture.REPOSITORY, 50).size());
  }

  /**
   * <b>QITS-882: THE FREE SLOTS ARE FILLED IN ONE TICK.</b> On 2026-10-03 the window door answered
   * CI_BUSY, "1 active run, 1 may be", with two READY repositories owed — while qits-ci had the
   * runners {@code qits-ci} (2 slots, 1 held) and {@code workstation} (6 slots) connected: eight
   * slots, seven free. The same estate here: 2 + 6 slots, one active run, three READY candidates —
   * all three go on one tick, least-recently-bumped first.
   */
  @Test
  void everyReadyBumpGoesInOneTickWhenQitsCiHasTheSlots() {
    inventory.clear();
    owes("qits-aaa-service", "eu.wohlben.qits:qits-a");
    owes("qits-bbb-service", "eu.wohlben.qits:qits-b");
    owes("qits-ccc-service", "eu.wohlben.qits:qits-c");
    lastReachedByTheClock("qits-aaa-service", Instant.now().minus(Duration.ofHours(1)));
    lastReachedByTheClock("qits-bbb-service", Instant.now().minus(Duration.ofDays(2)));
    lastReachedByTheClock("qits-ccc-service", Instant.now().minus(Duration.ofDays(1)));
    Fixture.scriptCiQueue(
        peers,
        1,
        Fixture.runner("qits-ci", 2, true, false),
        Fixture.runner("workstation", 6, true, false));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("DISPATCH", decision.outcome());
    assertEquals(8, decision.slots());
    assertEquals(1, decision.ciActive());
    assertEquals(7, decision.free());
    assertEquals(
        List.of("qits-bbb-service", "qits-ccc-service", "qits-aaa-service"),
        decision.picks().stream().map(pick -> pick.candidate().repository()).toList(),
        "every READY one, the longest-waiting first");
    io.restassured.RestAssured.given()
        .get("/maintenance/api/bumps/window")
        .then()
        .statusCode(200)
        .body("next", org.hamcrest.Matchers.equalTo("qits-bbb-service"))
        .body(
            "picks",
            org.hamcrest.Matchers.contains(
                "qits-bbb-service", "qits-ccc-service", "qits-aaa-service"));

    List<UUID> sent = dispatcher.tick();
    assertEquals(3, sent.size(), "three slots' worth in one tick, not one per idle queue");
    assertEquals(
        List.of("qits-bbb-service", "qits-ccc-service", "qits-aaa-service"),
        sent.stream().map(id -> store.bump(id).orElseThrow().repository).toList(),
        "sent in the order they were picked");
  }

  /** Three free slots and five READY: exactly three go, and the other two wait for the next tick. */
  @Test
  void threeFreeSlotsSendExactlyThreeOfFive() {
    inventory.clear();
    for (String name : List.of("qits-r1", "qits-r2", "qits-r3", "qits-r4", "qits-r5")) {
      owes(name, "eu.wohlben.qits:" + name);
    }
    Fixture.scriptCiQueue(peers, 1, Fixture.runner("qits-ci", 4, true, false));

    assertEquals(3, dispatcher.explain(Instant.now()).free());
    List<UUID> sent = dispatcher.tick();
    assertEquals(3, sent.size());
    assertEquals(
        List.of("qits-r1", "qits-r2", "qits-r3"),
        sent.stream().map(id -> store.bump(id).orElseThrow().repository).toList());
    assertTrue(store.bumps("qits-r4", 10).isEmpty());
    assertTrue(store.bumps("qits-r5", 10).isEmpty());
  }

  /** Free slots do not let a consumer go beside its owed upstream: only the upstream goes. */
  @Test
  void anOwedUpstreamAndItsConsumerSendOnlyTheUpstreamWhateverTheSlots() {
    inventory.clear();
    owes("qits-zzz-lib", "eu.wohlben.qits:qits-agents-lib");
    owesItsSubmodule("qits-aaa-consumer", "qits-zzz-lib");
    Fixture.scriptCiQueue(peers, 0, Fixture.runner("workstation", 6, true, false));

    List<UUID> sent = dispatcher.tick();
    assertEquals(
        List.of("qits-zzz-lib"),
        sent.stream().map(id -> store.bump(id).orElseThrow().repository).toList());
    assertTrue(store.bumps("qits-aaa-consumer", 10).isEmpty(), "it waits on the upstream's release");
  }

  /** Every slot taken: CI_BUSY, saying how it counted, and nothing sent. */
  @Test
  void everySlotTakenIsBusyAndSendsNothing() {
    Fixture.scriptCiQueue(
        peers,
        8,
        Fixture.runner("qits-ci", 2, true, false),
        Fixture.runner("workstation", 6, true, false));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_BUSY", decision.outcome());
    assertEquals(8, decision.slots());
    assertEquals(0, decision.free());
    assertEquals(
        "qits-ci has 8 slot(s), 8 active run(s) and 0 bump(s) waiting to reach it; nothing is free",
        decision.summary());
    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped());
  }

  /**
   * qits-ci's own rule: a runner that is not connected claims nothing, a quarantined one takes
   * nothing but its health check. Neither one's slots count.
   */
  @Test
  void disconnectedAndQuarantinedRunnersOfferNoSlots() {
    Fixture.scriptCiQueue(
        peers,
        0,
        Fixture.runner("gone", 4, false, false),
        Fixture.runner("sick", 4, true, true),
        Fixture.runner("qits-ci", 1, true, false),
        Fixture.runner("misconfigured", -3, true, false));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals(1, decision.slots(), "only the connected, healthy runner counts");
    assertEquals(1, decision.free());
    assertEquals(1, dispatcher.tick().size());
  }

  /** No runner at all that could claim a run is NO_SLOTS — not busy, and nothing is sent. */
  @Test
  void noUsableRunnerIsNoSlots() {
    Fixture.scriptCiQueue(
        peers, 0, Fixture.runner("gone", 4, false, false), Fixture.runner("sick", 2, true, true));

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("NO_SLOTS", decision.outcome());
    assertEquals(0, decision.slots());
    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped());

    Fixture.scriptCiQueue(peers, 0);
    peers.answer(
        PeerTarget.CI,
        CiClient.QUEUE_PATH,
        FakePeers.Scripted.ok("{\"running\":[],\"queued\":[],\"runners\":[]}"));
    assertEquals("NO_SLOTS", dispatcher.explain(Instant.now()).outcome(), "and none declared");
  }
}

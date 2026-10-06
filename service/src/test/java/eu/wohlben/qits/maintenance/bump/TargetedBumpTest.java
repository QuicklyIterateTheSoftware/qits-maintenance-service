package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.automation.EstatePinsAutomation;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.error.BumpAlreadyActiveException;
import eu.wohlben.qits.maintenance.error.NoSuchRepositoryException;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A BUMP ONTO A BRANCH THIS SERVICE DOES NOT OWN.
 *
 * <p>The subject is the ENDING. Everything before it — the payload, the dedupe key, the 503 rule,
 * the poller — is the same machinery the group path uses and is pinned where that path pins it; what
 * is new, and what nothing else could catch, is that the verdict is read out of the CI RUN instead
 * of out of the branch head. A workspace commits to its own branch all afternoon, so the group
 * ending's before-and-after would call somebody's editor this bump's success, and — under a red run
 * — would call a live branch STALE and refuse to write to it ever again. That last one is the
 * failure worth a test, because it is silent and permanent.
 *
 * <p><b>Every method drives {@code request → poll} by hand.</b> The suite's scheduler is off, so a
 * test that waited for the sweep would be indistinguishable from a test that hung.
 */
@QuarkusTest
class TargetedBumpTest {

  /**
   * The caller's branch: a workspace branch carrying a release request, which is the whole reason
   * this mode exists. Nothing here derives it and nothing here will ever delete it.
   */
  private static final String WORKSPACE_BRANCH = "workspace/ws-7";

  /** A second one, of the same repository — two release requests open at once is ordinary. */
  private static final String OTHER_BRANCH = "workspace/ws-8";

  /** Where the branch stood when the caller asked. */
  private static final String BEFORE_SHA = "1111111111111111111111111111111111111111";

  /** …and where it stands when the run ends: what the CI step pushed. */
  private static final String AFTER_SHA = "2222222222222222222222222222222222222222";

  private static final String RUN = "run-targeted";

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
    // ARMED AND NEVER EXPECTED TO BE CALLED. Leaving qits-projects unscripted would make "no release
    // was asked for" indistinguishable from "the ask was made and 404'd", which is the one thing
    // these tests are asserting.
    Fixture.scriptReleaseRequestAccepted(peers, "rr-targeted");
    // The branch exists and is somebody's: the state a targeted bump always finds.
    Fixture.scriptForeignBranchAt(peers, WORKSPACE_BRANCH, BEFORE_SHA);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  /** The pins a wrapper release request wants inside its fold: a gitlink move on a submodule path. */
  private static List<Change> gitlink() {
    return List.of(
        new Change(
            Ecosystem.GITLINK.wireName(),
            "webui",
            "qits-ci-frontend",
            Fixture.GITLINK_SHA,
            "2026.910.180413",
            "gitlink:webui"));
  }

  /** Opens one and lets the dispatch reach qits-ci. */
  private UUID trigger(String branch) {
    UUID id = bumps.requestTargeted(Fixture.REPOSITORY, branch, gitlink(), BumpTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(30));
    return id;
  }

  /** Ends the run one way or the other and lets the poll write the verdict. */
  private MtBump complete(UUID id, String ciStatus) {
    Fixture.scriptRun(peers, RUN, ciStatus);
    bumps.poll(id);
    queue.awaitIdle(Duration.ofSeconds(30));
    return store.bump(id).orElseThrow();
  }

  /**
   * The whole of it: the pins go onto the caller's branch, and the row says which commit holds them.
   *
   * <p>The sha is the point. The caller asked for pins to be put in a fold it is about to gate, so
   * "it worked" is not an answer — <b>which commit</b> is, and that is what {@code result_sha} is
   * for. A group bump keeps the same fact on its branch row, which a targeted bump has no right to
   * write.
   */
  @Test
  void aTargetedBumpWritesTheCallersBranchAndReportsItsCommit() {
    UUID id = trigger(WORKSPACE_BRANCH);

    MtBump running = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), running.status);
    assertEquals(
        BumpMode.AUTOMATION.name(),
        running.mode,
        "since qits-999 the door opens an estate-pins automation, on the row from the start");
    assertEquals(EstatePinsAutomation.KIND, running.automationKind);
    assertEquals(WORKSPACE_BRANCH, running.branch, "the branch is the caller's, not a derived one");
    assertNull(running.releaseRequestId, "the caller named a branch, not a request");

    // The step pushed; the branch now stands somewhere else.
    Fixture.scriptForeignBranchAt(peers, WORKSPACE_BRANCH, AFTER_SHA);
    MtBump done = complete(id, "SUCCESS");

    assertEquals(BumpStatus.SUCCEEDED.name(), done.status);
    assertEquals(AFTER_SHA, done.resultSha, "the row answers WHAT COMMIT THIS WROTE");
    assertTrue(done.message.contains(AFTER_SHA), "and the sentence names it too: " + done.message);

    // And the payload really did name the caller's branch — read off the wire rather than off the
    // row, because "the branch is right" is a claim about what left this process.
    List<String> triggers = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(1, triggers.size());
    assertTrue(
        triggers.getFirst().contains("\"branch\":\"" + WORKSPACE_BRANCH + "\""),
        "the payload writes the branch the caller named: " + triggers.getFirst());
    assertTrue(
        triggers.getFirst().contains("\"group\":\"" + BumpService.TARGETED_GROUP + "\""),
        "the step refuses a payload with no plain group name, so the sentinel travels: "
            + triggers.getFirst());
  }

  /**
   * <b>A WORKSPACE COMMIT LANDING MID-RUN IS NOT THIS BUMP'S BUSINESS.</b>
   *
   * <p>The branch moves while the run goes — that is what a workspace branch is — and under the
   * group ending a moved head means one thing under green and quite another under red. Neither
   * reading may be applied here: green is the run's success whatever the head did, and red is a
   * plain failure. <b>There is no STALE arm at all</b>, and if there were, this service would have
   * declared a live workspace branch abandoned on the strength of somebody saving a file.
   */
  @Test
  void aWorkspaceCommitBetweenTriggerAndCompletionIsNeverStale() {
    UUID id = trigger(WORKSPACE_BRANCH);

    // Somebody commits to their own branch while the run is going, and then the run goes RED.
    Fixture.scriptForeignBranchAt(peers, WORKSPACE_BRANCH, AFTER_SHA);
    MtBump done = complete(id, "FAILED");

    assertEquals(BumpStatus.FAILED.name(), done.status, "a red run is a failed bump, plainly");
    assertEquals("the ci run ended FAILED", done.message, "and the sentence says only that");
    assertFalse(
        done.message.toLowerCase().contains("stale")
            || done.message.contains("rewritten by hand"),
        "nothing here may accuse the branch's owner of anything: " + done.message);
    assertNull(done.resultSha, "a failed run wrote no commit to report");
    assertTrue(
        store.branches(Fixture.REPOSITORY).stream()
            .noneMatch(row -> WORKSPACE_BRANCH.equals(row.branch)),
        "and no branch row was written — STALE is a state of a row this mode never creates");
  }

  /**
   * Two things this mode must never do, asserted where a green ending would do them.
   *
   * <p><b>No {@code mt_branch} row</b>: that row's unique {@code (repository, group_name)} index is
   * an ownership claim over a ref, and this service owns nothing here. <b>No release request</b>: the
   * caller already holds the one these pins are for, and a second ask would be a second request for
   * the same work.
   */
  @Test
  void itTracksNoBranchAndAsksForNoRelease() {
    UUID id = trigger(WORKSPACE_BRANCH);
    Fixture.scriptForeignBranchAt(peers, WORKSPACE_BRANCH, AFTER_SHA);
    assertEquals(BumpStatus.SUCCEEDED.name(), complete(id, "SUCCESS").status);

    // The sweep is the other place the ask could come from, and a targeted row must not be in its
    // listing at all. Driving it here is what pins that, rather than the ending alone.
    bumps.sweep();
    queue.awaitIdle(Duration.ofSeconds(30));

    assertFalse(
        peers.called(PeerTarget.PROJECTS, Fixture.RELEASE_REQUESTS_PATH),
        "the caller already has the release request these pins are for");
    assertNull(store.bump(id).orElseThrow().releaseRequestId, "so nothing was ever asked");

    List<MtBranch> branches = store.branches(Fixture.REPOSITORY);
    assertTrue(
        branches.stream().noneMatch(row -> WORKSPACE_BRANCH.equals(row.branch)),
        "a branch this service does not own gets no row of ours");
    assertTrue(
        branches.stream().noneMatch(row -> BumpService.TARGETED_GROUP.equals(row.groupName)),
        "and nothing was written under the sentinel either, which would collide with a real group");
  }

  /**
   * The lock is on the REF, because the ref is the thing that cannot be written twice at once.
   *
   * <p>Two release requests open on one repository is the ordinary shape of a busy day, and each is
   * entitled to its own pins. Refusing the second because the first is running would serialize
   * unrelated work; admitting a second onto the SAME branch would make the ff-only push a rejection.
   */
  @Test
  void theLockIsOnTheBranchAndNotOnTheRepository() {
    UUID first = trigger(WORKSPACE_BRANCH);

    BumpAlreadyActiveException refused =
        assertThrows(
            BumpAlreadyActiveException.class,
            () ->
                bumps.requestTargeted(
                    Fixture.REPOSITORY, WORKSPACE_BRANCH, gitlink(), BumpTrigger.MANUAL));
    assertEquals(first, refused.activeBumpId(), "the refusal names the bump that holds the ref");
    assertTrue(
        refused.getMessage().contains(WORKSPACE_BRANCH),
        "and says which ref, not which group: " + refused.getMessage());

    // The other branch of the same repository goes, because nothing about it is held.
    Fixture.scriptForeignBranchAt(peers, OTHER_BRANCH, BEFORE_SHA);
    UUID second = trigger(OTHER_BRANCH);
    assertNotEquals(first, second);
    assertEquals(BumpStatus.RUNNING.name(), store.bump(second).orElseThrow().status);

    // …and neither of them holds the nightly group's lock, which is a different ref again.
    assertTrue(
        store.activeBump(Fixture.REPOSITORY, "dependencies").isEmpty(),
        "a targeted bump must not hold up the group whose branch it never touches");
  }

  /** Nothing to write is an ending, not a refusal — and no CI run is started for it. */
  @Test
  void anEmptyChangeListEndsNothingToDoWithoutARun() {
    UUID id = bumps.requestTargeted(Fixture.REPOSITORY, WORKSPACE_BRANCH, List.of(), BumpTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump done = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.NOTHING_TO_DO.name(), done.status);
    assertNull(done.resultSha);
    assertFalse(
        peers.called(PeerTarget.CI, CiClient.TRIGGER_PATH), "there was nothing to ask qits-ci for");
  }

  /**
   * A green run whose branch head could not be read afterwards is still a green run.
   *
   * <p>The verdict belongs to the RUN. Turning another service's momentary silence into a failed
   * bump would be this service reporting the git host's downtime as its own, over pins that really
   * were pushed — so the outage costs the sha and a sentence, and nothing else.
   */
  @Test
  void anUnreadableHeadCostsTheShaAndNotTheVerdict() {
    UUID id = trigger(WORKSPACE_BRANCH);

    Fixture.scriptForeignBranchUnreachable(peers, WORKSPACE_BRANCH);
    MtBump done = complete(id, "SUCCESS");

    assertEquals(BumpStatus.SUCCEEDED.name(), done.status);
    assertNull(done.resultSha);
    assertTrue(
        done.message.contains("could not be read"),
        "and the row says why there is no sha: " + done.message);
  }

  /** The refusals the door rests on, taken at the service rather than over HTTP. */
  @Test
  void itRefusesAnImplausibleBranchAndAnUnknownRepository() {
    assertThrows(
        BadRequestException.class,
        () ->
            bumps.requestTargeted(
                Fixture.REPOSITORY, "--force; rm -rf /", gitlink(), BumpTrigger.MANUAL),
        "the branch reaches a git ref and a shell in the step; the same rules apply on this side");

    assertThrows(
        NoSuchRepositoryException.class,
        () ->
            bumps.requestTargeted("never-scanned", WORKSPACE_BRANCH, gitlink(), BumpTrigger.MANUAL),
        "a repository with no inventory row has no project to address the git host with");
  }
}

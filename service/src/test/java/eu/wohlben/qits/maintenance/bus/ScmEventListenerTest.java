package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.scmDeleteBranchPayload;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.scmPublishCommitPayload;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.scmReleasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.adoption.ReleaseLedger;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SCM listener's three decisions, in isolation from the bus, the database and the work queue.
 *
 * <p>The stand-ins are a {@link MaintenanceStore} holding rows in maps and a {@link ScanService} that
 * records what it was asked for instead of queueing it. That is the whole seam: what a scan then
 * does is {@code ScanCycleIT}'s and {@code MaintenanceApiTest}'s, and what a branch row means to a
 * bump is {@code BumpIT}'s.
 *
 * <p>Every payload is produced by {@link ForeignEventContractTest}'s transcription through the real
 * canonical serializer, so the bytes are the bytes qits-workspaces and qits-githost publish.
 */
class ScmEventListenerTest {

  private static final String REPOSITORY = "qits-ci-service";
  private static final String MAIN = "main";
  private static final String GROUP = "dependencies";
  private static final String BRANCH = "maintenance/" + GROUP;

  /** A store whose seven tables are three maps. */
  private static final class RecordingStore extends MaintenanceStore {

    final Map<String, MtRepository> repositories = new LinkedHashMap<>();
    final Map<String, List<MtGroup>> groups = new LinkedHashMap<>();
    final Map<String, MtBranch> branches = new LinkedHashMap<>();
    final Set<String> pendingScans = new java.util.LinkedHashSet<>();
    RuntimeException failWith;

    void repository(String name, String mainBranch, String... groupNames) {
      MtRepository row = new MtRepository();
      row.name = name;
      row.project = "qits";
      row.mainBranch = mainBranch;
      repositories.put(name, row);
      List<MtGroup> declared = new ArrayList<>();
      for (String groupName : groupNames) {
        MtGroup group = new MtGroup();
        group.id = UUID.randomUUID();
        group.repository = name;
        group.name = groupName;
        declared.add(group);
      }
      groups.put(name, declared);
    }

    void branch(String repository, String group, BranchState state, String headSha) {
      MtBranch row = new MtBranch();
      row.id = UUID.randomUUID();
      row.repository = repository;
      row.groupName = group;
      row.branch = "maintenance/" + group;
      row.state = state.name();
      row.headSha = headSha;
      branches.put(repository + "/" + group, row);
    }

    @Override
    public Optional<MtRepository> repository(String name) {
      if (failWith != null) {
        throw failWith;
      }
      return Optional.ofNullable(repositories.get(name));
    }

    @Override
    public List<MtGroup> groups(String repository) {
      return groups.getOrDefault(repository, List.of());
    }

    @Override
    public Optional<MtBranch> branch(String repository, String group) {
      return Optional.ofNullable(branches.get(repository + "/" + group));
    }

    @Override
    public void recordBranch(
        String repository,
        String group,
        String branchName,
        BranchState state,
        String headSha,
        Instant now) {
      MtBranch row = new MtBranch();
      row.id = UUID.randomUUID();
      row.repository = repository;
      row.groupName = group;
      row.branch = branchName;
      row.state = state.name();
      row.headSha = headSha;
      row.updatedAt = now;
      branches.put(repository + "/" + group, row);
    }

    @Override
    public boolean scanPending(String repository) {
      return pendingScans.contains(repository);
    }

    MtBranch branchRow(String repository, String group) {
      return branches.get(repository + "/" + group);
    }

    /** {@code mt_latest}, as the forward-only writer sees it: a version and its provenance. */
    final Map<String, MtLatest> latest = new LinkedHashMap<>();

    @Override
    public boolean recordLatestIfNewer(
        Ecosystem ecosystem, String name, String version, String sourceUrl, Instant now) {
      String key = PendingChanges.key(ecosystem.wireName(), name);
      MtLatest row = latest.get(key);
      if (row != null && row.latest != null && !VersionOrder.newer(ecosystem, row.latest, version)) {
        return false;
      }
      MtLatest written = new MtLatest();
      written.ecosystem = ecosystem.wireName();
      written.name = name;
      written.latest = version;
      written.sourceUrl = sourceUrl;
      written.checkedAt = now;
      latest.put(key, written);
      return true;
    }

    MtLatest latestRow(Ecosystem ecosystem, String name) {
      return latest.get(PendingChanges.key(ecosystem.wireName(), name));
    }
  }

  /** A git host answering one question: which commit does this revision name? */
  private static final class RecordingGitHost extends GitHostReader {

    final List<String> asked = new ArrayList<>();
    final Map<String, TreeLookup> answers = new LinkedHashMap<>();

    void holds(String repository, String revision, String sha) {
      answers.put(repository + " " + revision, TreeLookup.found(sha, List.of()));
    }

    void unreachable(String repository, String revision) {
      answers.put(repository + " " + revision, TreeLookup.unreachable("the git host is not there"));
    }

    @Override
    public TreeLookup head(String project, String repository, String revision) {
      asked.add(project + "/" + repository + " " + revision);
      return answers.getOrDefault(repository + " " + revision, TreeLookup.gone());
    }
  }

  /**
   * A release ledger that records the ask instead of reading a released tree.
   *
   * <p>What a tag read produces is {@code ReleaseLedgerTest}'s subject; what is this listener's is
   * that the ask is made at all, with the values it has already resolved.
   */
  private static final class RecordingLedger extends ReleaseLedger {

    record Recorded(String project, String repository, String version, String sha) {}

    final List<Recorded> recorded = new ArrayList<>();
    RuntimeException failWith;

    @Override
    public boolean record(
        String project, String repository, String version, String sha, Instant occurredAt) {
      if (failWith != null) {
        throw failWith;
      }
      recorded.add(new Recorded(project, repository, version, sha));
      return true;
    }
  }

  /** A scan service that records the request instead of opening a row and queueing work. */
  private static final class RecordingScans extends ScanService {

    record Requested(
        ScanScope scope, String repository, ScanTrigger trigger, String revision) {}

    final List<Requested> requested = new ArrayList<>();

    /**
     * Only the four-argument overload is recorded, because it is the one the production code reaches:
     * the three-argument form delegates to it with a null revision, so overriding both would let a
     * caller's revision go unobserved.
     */
    @Override
    public UUID request(
        ScanScope scope, String repository, ScanTrigger trigger, String revision) {
      requested.add(new Requested(scope, repository, trigger, revision));
      return UUID.randomUUID();
    }
  }


  /** The upstream hook, recording what it was told instead of queueing a re-plan (qits-1133). */
  static final class RecordingUpstream extends eu.wohlben.qits.maintenance.automation.UpstreamReplan {
    final List<String> moved = new java.util.ArrayList<>();

    @Override
    public void latestMoved(eu.wohlben.qits.maintenance.model.Ecosystem ecosystem, String name) {
      moved.add(ecosystem.wireName() + ":" + name);
    }
  }

  private ScmEventListener listener;
  private RecordingStore store;
  private RecordingUpstream upstream;
  private RecordingScans scans;
  private RecordingGitHost gitHost;
  private RecordingLedger ledger;

  @BeforeEach
  void setUp() {
    store = new RecordingStore();
    scans = new RecordingScans();
    gitHost = new RecordingGitHost();
    ledger = new RecordingLedger();
    listener = new ScmEventListener();
    listener.store = store;
    listener.scans = scans;
    listener.gitHost = gitHost;
    listener.ledger = ledger;
    upstream = new RecordingUpstream();
    listener.upstream = upstream;
    store.repository(REPOSITORY, MAIN, GROUP, "external");
  }

  private void released(String repository, String branch) {
    listener.onFrame(frame("SCMRelease", scmReleasePayload(repository, branch, "2026.901.1")));
  }

  private void deleted(String repository, String branch) {
    listener.onFrame(frame("SCMDeleteBranch", scmDeleteBranchPayload(repository, branch)));
  }

  private void pushed(String repository, String branch, String sha) {
    listener.onFrame(frame("SCMPublishCommit", scmPublishCommitPayload(repository, branch, sha)));
  }

  @Test
  void itSubscribesToTheThreeSignaturesUnderItsOwnStorageKey() {
    assertEquals(
        Set.of("SCMRelease", "SCMDeleteBranch", "SCMPublishCommit"), listener.signatures());
    assertEquals("maintenance-branch-tracking", listener.consumerId());
  }

  // --- the branch's life ------------------------------------------------------------------------

  /**
   * <b>THE DELETE IS THE WHOLE OF A MAINTENANCE BRANCH'S ENDING NOW.</b> This listener used to write
   * RELEASED off {@code SCMRelease}, because qits-workspaces' release door published that event
   * naming the branch it had just tagged over. A release is a tag on a release request's fold,
   * {@code release/<id>}, so no event names a {@code maintenance/} branch any more — and the ending
   * a branch really has is the delete the release performs on its named sources, which is the same
   * signal a person deleting it by hand sends. NONE is the right answer to both.
   */
  @Test
  void aMaintenanceBranchEndsAtTheDeleteAndTheReleaseDoesNotTouchIt() {
    store.branch(REPOSITORY, GROUP, BranchState.PUSHED, "abc1234");

    released(REPOSITORY, BRANCH);
    MtBranch afterRelease = store.branchRow(REPOSITORY, GROUP);
    assertEquals(
        BranchState.PUSHED.name(),
        afterRelease.state,
        "a release names its own fold, never this branch, so nothing here moves");

    deleted(REPOSITORY, BRANCH);
    MtBranch afterDelete = store.branchRow(REPOSITORY, GROUP);
    assertEquals(BranchState.NONE.name(), afterDelete.state);
    assertNull(afterDelete.headSha, "a branch that is gone has no head, and the next bump starts fresh");
  }

  /** The same delete is the only thing that ever clears a branch somebody rewrote by hand. */
  @Test
  void aHandDeletedStaleBranchIsClearedByTheDeleteEvent() {
    store.branch(REPOSITORY, GROUP, BranchState.STALE, "somebody-elses-sha");

    deleted(REPOSITORY, BRANCH);

    assertEquals(BranchState.NONE.name(), store.branchRow(REPOSITORY, GROUP).state);
  }

  /** Every release on the platform rides this signature, and no branch row is any of their business. */
  @Test
  void noReleaseWritesABranchRowWhateverBranchItNames() {
    released(REPOSITORY, MAIN);
    released(REPOSITORY, BRANCH);
    released(REPOSITORY, "release/6f0d1f2e-0000-4000-8000-000000000000");
    released("some-repository-nobody-scanned", BRANCH);
    deleted(REPOSITORY, "feature/something");

    assertTrue(store.branches.isEmpty());
  }

  // --- the gitlink latest -----------------------------------------------------------------------

  private static final String FRONTEND = "qits-artifacts-frontend";
  private static final String VERSION = "2026.901.1";
  private static final String RELEASE_SHA = "0011223344556677889900aabbccddeeff001122";

  private void released(String repository, String branch, String version) {
    listener.onFrame(frame("SCMRelease", scmReleasePayload(repository, branch, version)));
  }

  /**
   * THE HOP FILES' REPLACEMENT. A frontend releases on its own main branch — no maintenance branch
   * anywhere in it — and every gitlink pinned at that repository now has somewhere to move.
   */
  @Test
  void anyReleaseRecordsTheGitlinkLatestWithTheCommitItsTagResolvesTo() {
    gitHost.holds(FRONTEND, "refs/tags/" + VERSION, RELEASE_SHA);

    released(FRONTEND, MAIN, VERSION);

    MtLatest row = store.latestRow(Ecosystem.GITLINK, FRONTEND);
    assertEquals(VERSION, row.latest, "the version is what the bump step fetches as a tag");
    assertEquals(
        RELEASE_SHA,
        GitlinkSha.read(row.sourceUrl).orElseThrow(),
        "and the sha is what the pending rule compares a pin against");
    assertEquals(
        List.of("qits/" + FRONTEND + " refs/tags/" + VERSION),
        gitHost.asked,
        "the tag is spelled in full, so no branch of that name can answer for it");
  }

  /**
   * A release of a repository whose maintenance branch is pushed records the gitlink and leaves the
   * branch alone — the two facts are independent, and only one of them is this event's to state.
   */
  @Test
  void aReleaseWritesTheGitlinkLatestAndLeavesThePushedBranchWhereItIs() {
    store.branch(REPOSITORY, GROUP, BranchState.PUSHED, "abc1234");
    gitHost.holds(REPOSITORY, "refs/tags/" + VERSION, RELEASE_SHA);

    released(REPOSITORY, BRANCH, VERSION);

    assertEquals(BranchState.PUSHED.name(), store.branchRow(REPOSITORY, GROUP).state);
    assertEquals(VERSION, store.latestRow(Ecosystem.GITLINK, REPOSITORY).latest);
  }

  /**
   * A tag the git host does not hold is POISON — the same question has the same answer for ever —
   * so it is settled rather than left owed.
   */
  @Test
  void aReleaseWhoseTagTheGitHostDoesNotHoldRecordsNothingAndIsSettled() {
    released(FRONTEND, MAIN, VERSION);

    assertNull(store.latestRow(Ecosystem.GITLINK, FRONTEND));
  }

  /**
   * A git host that cannot be ASKED is retryable, and it is the only thing that can recover a
   * latest nothing else ever writes — no scan refreshes a gitlink.
   */
  @Test
  void aGitHostThatWillNotAnswerIsLeftToThrowSoTheReleaseStaysOwed() {
    gitHost.unreachable(FRONTEND, "refs/tags/" + VERSION);

    assertThrows(IllegalStateException.class, () -> released(FRONTEND, MAIN, VERSION));
  }

  /** The bus's write is forward-only, gitlinks included: a catch-up frame rewinds nothing. */
  @Test
  void anOlderReleaseArrivingLateDoesNotRewindTheGitlinkLatest() {
    gitHost.holds(FRONTEND, "refs/tags/2026.902.1", RELEASE_SHA);
    gitHost.holds(FRONTEND, "refs/tags/2026.801.1", "cccccccccccccccccccccccccccccccccccccccc");

    released(FRONTEND, MAIN, "2026.902.1");
    released(FRONTEND, MAIN, "2026.801.1");

    MtLatest row = store.latestRow(Ecosystem.GITLINK, FRONTEND);
    assertEquals("2026.902.1", row.latest);
    assertEquals(RELEASE_SHA, GitlinkSha.read(row.sourceUrl).orElseThrow());
    assertEquals(
        List.of("gitlink:" + FRONTEND), upstream.moved, "the hook hears the one that moved");
  }

  /**
   * A repository no scan has reached has no {@code mt_repository} row to take a project from, and
   * the payload's own is the fallback — otherwise the first release of a new repository would be
   * the one that could not be recorded.
   */
  @Test
  void aRepositoryTheInventoryDoesNotHoldIsAddressedByThePayloadsProject() {
    gitHost.holds(FRONTEND, "refs/tags/" + VERSION, RELEASE_SHA);

    released(FRONTEND, MAIN, VERSION);

    assertEquals(List.of("qits/" + FRONTEND + " refs/tags/" + VERSION), gitHost.asked);
    assertEquals(VERSION, store.latestRow(Ecosystem.GITLINK, FRONTEND).latest);
  }

  // --- the release ledger -----------------------------------------------------------------------

  /**
   * <b>A release writes TWO rows, and they are two different facts about one tag.</b> The gitlink
   * latest says where a submodule pinned at this repository can move to; the ledger says what the
   * released tree was itself carrying, which is how an adoption of somebody ELSE's release is later
   * proved. The tag sha is already resolved above, so the ledger is handed it rather than resolving
   * it again.
   */
  @Test
  void aReleaseRecordsTheLedgerBesideTheGitlinkLatest() {
    gitHost.holds(REPOSITORY, "refs/tags/" + VERSION, RELEASE_SHA);

    released(REPOSITORY, MAIN, VERSION);

    assertEquals(
        List.of(new RecordingLedger.Recorded("qits", REPOSITORY, VERSION, RELEASE_SHA)),
        ledger.recorded);
    assertEquals(VERSION, store.latestRow(Ecosystem.GITLINK, REPOSITORY).latest);
  }

  /**
   * <b>WHETHER OR NOT THE COLUMN MOVED.</b> {@code mt_latest} is forward-only because it answers
   * "where can a pin move to", which a catch-up frame must not rewind. A ledger row answers "what
   * did THIS release declare" — a fact about a version rather than about the newest one — so a
   * late-announced older release deserves its row exactly as much as this morning's does.
   */
  @Test
  void anOlderReleaseArrivingLateStillGetsItsLedgerRow() {
    gitHost.holds(FRONTEND, "refs/tags/2026.902.1", RELEASE_SHA);
    gitHost.holds(FRONTEND, "refs/tags/2026.801.1", "cccccccccccccccccccccccccccccccccccccccc");

    released(FRONTEND, MAIN, "2026.902.1");
    released(FRONTEND, MAIN, "2026.801.1");

    assertEquals("2026.902.1", store.latestRow(Ecosystem.GITLINK, FRONTEND).latest);
    assertEquals(
        List.of("2026.902.1", "2026.801.1"),
        ledger.recorded.stream().map(RecordingLedger.Recorded::version).toList(),
        "both releases happened, and both declared something");
  }

  /** A tag the git host does not hold is settled before either write is reached. */
  @Test
  void aReleaseWhoseTagIsNotThereRecordsNoLedgerRowEither() {
    released(FRONTEND, MAIN, VERSION);

    assertTrue(ledger.recorded.isEmpty());
  }

  /**
   * The ledger's own retryable failure is the listener's: a git host that cannot be asked for the
   * released TREE is the same outage as one that cannot be asked for the tag, and the frame stays
   * owed either way.
   */
  @Test
  void aLedgerThatCannotReadTheReleasedTreeIsLeftToThrow() {
    gitHost.holds(FRONTEND, "refs/tags/" + VERSION, RELEASE_SHA);
    ledger.failWith = new IllegalStateException("the git host could not be asked");

    assertThrows(IllegalStateException.class, () -> released(FRONTEND, MAIN, VERSION));
  }

  // --- the release's manifests ------------------------------------------------------------------

  /**
   * <b>The gap that let a released repository stay stale, and the read that closes it.</b> A release
   * changes a repository's manifests, and the push it eventually produces — the finalize merge into
   * {@code main}, which qits-githost's REST door announces since qits-886 — comes later, after the
   * deployment for a repository that deploys. So the scan is queued here as well, and it is queued
   * <b>at the tag</b>: a scan of the branch at release time would read the previous release's pins
   * and stamp the row as freshly checked. Measured live
   * 2026-09-09 on qits-artifacts-frontend — scan 32ms after the event, pins two days old.
   */
  @Test
  void aReleaseQueuesAScanOfThatRepositoryAtTheReleasedTag() {
    gitHost.holds(REPOSITORY, "refs/tags/" + VERSION, RELEASE_SHA);

    released(REPOSITORY, "release/6f0d1f2e-0000-4000-8000-000000000000", VERSION);

    assertEquals(1, scans.requested.size());
    RecordingScans.Requested queued = scans.requested.get(0);
    assertEquals(REPOSITORY, queued.repository());
    assertEquals(ScanTrigger.EVENT, queued.trigger(), "a release scans and never bumps");
    assertEquals(ScanScope.INTERNAL, queued.scope());
    assertEquals(
        "refs/tags/" + VERSION,
        queued.revision(),
        "the tag and never the branch — main has not caught up when this frame arrives");
  }

  /**
   * The scan is asked for BEFORE the tag is resolved for the gitlink latest, so a git host that will
   * not answer leaves the frame owed without the manifest refresh depending on that retry.
   */
  @Test
  void aReleaseQueuesTheScanEvenWhenTheGitHostCannotResolveItsTag() {
    gitHost.unreachable(REPOSITORY, "refs/tags/" + VERSION);

    assertThrows(
        IllegalStateException.class,
        () -> released(REPOSITORY, MAIN, VERSION),
        "the gitlink half is retryable and stays owed");
    assertEquals(1, scans.requested.size(), "the scan was already asked for");
  }

  /** The push half's debounce, on the other signature: one queued scan covers both announcements. */
  @Test
  void aReleaseIsDebouncedAgainstTheScanAlreadyQueued() {
    gitHost.holds(REPOSITORY, "refs/tags/" + VERSION, RELEASE_SHA);
    released(REPOSITORY, MAIN, VERSION);
    store.pendingScans.add(REPOSITORY);

    released(REPOSITORY, MAIN, VERSION);

    assertEquals(1, scans.requested.size());
  }

  /**
   * The guard is on the scan half ALONE. A repository no scan has read has no row to refresh — but
   * its gitlink latest is keyed by name and needs no row to be true, so that half still writes.
   */
  @Test
  void aReleaseOfARepositoryThisInventoryDoesNotHoldStillRecordsTheGitlinkLatest() {
    gitHost.holds(FRONTEND, "refs/tags/" + VERSION, RELEASE_SHA);

    released(FRONTEND, MAIN, VERSION);

    assertTrue(scans.requested.isEmpty(), "there is no row to refresh yet");
    assertEquals(VERSION, store.latestRow(Ecosystem.GITLINK, FRONTEND).latest);
  }

  // --- the push ---------------------------------------------------------------------------------

  @Test
  void aPushToTheRepositorysMainBranchQueuesAScanOfThatOneRepository() {
    pushed(REPOSITORY, MAIN, "abc1234");

    assertEquals(1, scans.requested.size());
    RecordingScans.Requested queued = scans.requested.get(0);
    assertEquals(REPOSITORY, queued.repository());
    assertEquals(ScanTrigger.EVENT, queued.trigger());
    assertEquals(
        ScanScope.INTERNAL,
        queued.scope(),
        "every scan re-reads every manifest; the scope governs only which registries answer");
    assertNull(
        queued.revision(),
        "a push names the branch it landed on, so the scan reads the default branch as always");
  }

  /** A merge is a burst of pushes, and a scan already queued reads the head at the moment it runs. */
  @Test
  void aSecondPushIsDebouncedAgainstTheScanAlreadyQueued() {
    pushed(REPOSITORY, MAIN, "abc1234");
    store.pendingScans.add(REPOSITORY);
    pushed(REPOSITORY, MAIN, "def5678");

    assertEquals(1, scans.requested.size(), "one queued scan covers both pushes");
  }

  @Test
  void aPushToAnyOtherBranchQueuesNothing() {
    pushed(REPOSITORY, BRANCH, "abc1234");
    pushed(REPOSITORY, "feature/whatever", "abc1234");

    assertTrue(
        scans.requested.isEmpty(),
        "only the branch a scan reads manifests at can invalidate the inventory");
  }

  /** A repository no scan has read yet has no row and no main branch to compare against. */
  @Test
  void aPushToARepositoryThisInventoryDoesNotHoldIsSettled() {
    pushed("some-repository-nobody-scanned", MAIN, "abc1234");

    assertTrue(scans.requested.isEmpty());
  }

  /** An id-addressed push — a mirror sync — announces neither a project nor a name. */
  @Test
  void aPushThatNamesNoRepositoryIsSettled() {
    listener.onFrame(frame("SCMPublishCommit", scmPublishCommitPayload(null, MAIN, "abc1234")));

    assertTrue(scans.requested.isEmpty());
  }

  // --- failure ----------------------------------------------------------------------------------

  @Test
  void anUnreadablePayloadIsPoisonAndIsSettledOnEverySignature() {
    listener.onFrame(frame("SCMRelease", "not json"));
    listener.onFrame(frame("SCMDeleteBranch", "not json"));
    listener.onFrame(frame("SCMPublishCommit", "not json"));

    assertTrue(store.branches.isEmpty());
    assertTrue(scans.requested.isEmpty());
  }

  @Test
  void aStoreThatWillNotAnswerIsLeftToThrowSoTheEventStaysOwed() {
    store.failWith = new IllegalStateException("the database is not there");

    assertThrows(IllegalStateException.class, () -> pushed(REPOSITORY, MAIN, "abc1234"));
  }
}

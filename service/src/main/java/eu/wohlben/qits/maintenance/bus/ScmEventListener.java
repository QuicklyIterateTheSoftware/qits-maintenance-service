package eu.wohlben.qits.maintenance.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.adoption.ReleaseLedger;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.latest.MainHeadLatest;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>What source control did to a repository this service tracks</b> — the three SCM facts that
 * change something here, and nothing else.
 *
 * <table>
 *   <caption>The three events and what each one moves</caption>
 *   <tr><th>event</th><th>publisher</th><th>what it means here</th></tr>
 *   <tr><td>{@code SCMRelease}</td><td>qits-projects</td>
 *       <td>a repository has a new released commit. One row follows and one scan, both read AT
 *           THE TAG: the release LEDGER gets the commit the tag resolves to and what the tree at
 *           that tag declared, which is how an adoption of somebody else's release is later proved
 *           and how a {@code main} head is later named; and the released repository is rescanned
 *           at that same tag, because the release is what changed its manifests. It does NOT move
 *           the GITLINK latest any more (qits-1257): a cut release is not yet a finalized
 *           one</td></tr>
 *   <tr><td>{@code SCMDeleteBranch}</td><td>qits-githost</td>
 *       <td>a {@code maintenance/<group>} branch is gone — the branch row becomes NONE, so the next
 *           bump starts fresh from main</td></tr>
 *   <tr><td>{@code SCMPublishCommit}</td><td>qits-githost</td>
 *       <td>a push landed on a repository's OWN main branch — a finalize merge among them — so
 *           its GITLINK latest becomes the release main's head carries (qits-1257), and its
 *           manifests are not what this inventory holds — one repository is queued for a
 *           rescan</td></tr>
 * </table>
 *
 * <h2>{@code SCMRelease} no longer says anything about a maintenance branch</h2>
 *
 * <p>It used to say two things, and the second one is gone. qits-workspaces' release door published
 * it the instant it pushed a tag over the branch it had been given, so the event named that branch
 * and this listener could write {@code BranchState.RELEASED} from it — the one fact nothing else on
 * the platform could tell this service.
 *
 * <p>The door is retired. A release is now a tag on a release request's fold, {@code
 * release/<id>}, published by qits-projects — so {@code branch} on this event names that fold and
 * never a {@code maintenance/} one, and there is nothing left to match. A maintenance branch's whole
 * ending is the {@code SCMDeleteBranch} that follows the release (a request's named sources are
 * deleted when it lands), which is the same signal a person deleting it by hand sends, and NONE is
 * the right answer to both: the next bump starts fresh from main. Until qits-886 that ending was
 * dead: the release deletes its sources through qits-githost's REST door, and the door announced
 * nothing, so a released bump's branch row read PUSHED for a branch that no longer existed. The door
 * announces every ref it moves now, the delete included. That delete is also what clears a
 * STALE row — a branch somebody rewrote is one this service stops writing to until it is gone, and
 * this is how it learns that it is.
 *
 * <h2>Why a main-branch push is a scan and not a bump</h2>
 *
 * <p>A push changes a MANIFEST, so the honest answer to one is to re-read it — one repository, at
 * the head the push just made. Whether the pending set that falls out should become a branch is
 * still the clock's standing instruction or a person's press: {@link ScanTrigger#EVENT} scans and
 * never bumps, or every repository somebody touched during the day would grow a branch.
 *
 * <p><b>A RELEASE draws the same conclusion twice, and the first one from its own signature.</b>
 * Main moves two ways on this platform: a person's push, and a release merging its tag into {@code
 * main} at FINALIZED through qits-githost's REST door. That door used to fire no post-receive and
 * publish nothing, so the push half's main-branch test could never match a release — which is why
 * {@link #onReleasedManifests} exists. Since qits-886 the door announces the merge as an ordinary
 * {@code SCMPublishCommit} (the branch's short name, with {@code repoName}), so the finalize merge
 * IS a push this listener sees, and the push half rescans main once it has moved. That second scan
 * is what heals a row a scheduled scan read between the release and the finalize merge
 * (qits-events-service, 2026-10-04: the 01:00 scan read main six minutes before it was merged, and
 * reported a change owed that had already shipped). Both halves queue the same {@link
 * ScanTrigger#EVENT} scan through the same debounce; neither bumps.
 *
 * <p><b>But the release half reads the TAG where the push half reads the branch</b>, and that is the
 * difference that makes it correct rather than merely early — and why it stays now that the merge is
 * announced too. {@code main} is finalized after the
 * release — at once for a repository that deploys nothing, after the deployment for one that does —
 * so a release-triggered scan of the branch reads the PREVIOUS release's manifests and stamps the row
 * as freshly checked, which is worse than not scanning at all. Measured on 2026-09-09,
 * qits-artifacts-frontend: event at 19:20:53.190, scan at 19:20:53.222, pins two days old. The tag is
 * true the moment the release is announced, for every archetype, and needs no second event from
 * anybody. See {@code ManifestScanner.read(CatalogEntry, String)}.
 *
 * <p>The scan goes through {@link ScanService#request} — the same path {@code POST /scans} with a
 * {@code repository} takes — so it is a row a client can follow, it is queued behind the one worker
 * thread, and it closes itself on failure like any other. Nothing here is a second scanning
 * mechanism.
 *
 * <p><b>It is debounced against the store, not against a field.</b> {@link
 * MaintenanceStore#scanPending} answers whether a scan of exactly this repository is already queued
 * or running; a merge is a burst of pushes, and without this a burst is five rows queued behind one
 * thread each re-reading a tree the one in front of it already read. A field would not survive a
 * restart and would not see the scan a person queued from the UI a second earlier.
 *
 * <h2>{@code selects} stays default, and the filtering is here</h2>
 *
 * <p>Every frame of the three signatures leaves a claim row, including the pushes to branches this
 * service has no opinion about — which on this platform is most of them. That is deliberate: the
 * seam asks a predicate to be PURE, and every question worth asking here is a database read (is this
 * repository in the catalog, is that its main branch, is that group one of its own). A predicate
 * that read the store would be asked once per frame and then asked again in {@link #onFrame}, and
 * one that threw on a database blip would leave the event owed for ever. The claim table is bounded
 * anyway — the sweeper prunes claims the watermark has passed by more than the library's
 * {@code prune-horizon}.
 *
 * <h2>The three payloads are TRANSCRIPTIONS, and that is a standing instruction</h2>
 *
 * <p>Neither publisher ships a vocabulary jar this repository could depend on — qits-projects keeps
 * {@code SCMRelease} in its own {@code service/…/bus/} package, and taking qits-githost-events would
 * be a compile-time dependency on another context for three field lists. So each record below transcribes only the fields this
 * listener consumes, decoded by {@link CanonicalJson} exactly as the publisher encoded them, and
 * {@code bus/ForeignEventContractTest} pins every name against the canonical form. <b>A rename over
 * there is a change to that transcription in the same campaign</b>; landing it there and not here
 * leaves this suite green and this listener silently deaf, which is the one failure the test cannot
 * prevent and is why it says so out loud.
 *
 * <h2>Failure</h2>
 *
 * <p>The seam's rule: a throw rolls the claim back and the event is owed for ever, so swallow what
 * retrying cannot fix and throw what it can. A payload that will not parse, one that names no
 * repository this service knows, and one naming a group that repository does not have are all
 * poison — the same bytes fail identically every time — so each is a WARN or a DEBUG and a return. A
 * database that could not answer is left to throw, because the next attempt is exactly what fixes
 * it.
 */
@ApplicationScoped
public class ScmEventListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ScmEventListener.class);

  /**
   * This consumption's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   *
   * <p><b>Never change it.</b> A new value is a brand-new consumer initializing at the head of the
   * log, silently skipping everything in between. It names the consumption, not the class.
   */
  static final String CONSUMER_ID = "maintenance-branch-tracking";

  /** qits-projects' "this version of this repository is tagged". */
  static final String RELEASE_SIGNATURE = "SCMRelease";

  /** qits-githost's "this branch is gone, and here is the tip it last had". */
  static final String DELETE_SIGNATURE = "SCMDeleteBranch";

  /** qits-githost's "this branch moved", one per successfully updated ref of a push. */
  static final String PUSH_SIGNATURE = "SCMPublishCommit";

  /**
   * The main branch of a repository no scan has read yet — what {@code ManifestScanner.read} reads
   * for a row that names none, and so the only branch whose push says anything here.
   */
  static final String DEFAULT_MAIN = "main";

  /** Every branch this service writes is under it, and the group is what follows. */
  static final String BRANCH_PREFIX = "maintenance/";

  /**
   * A release's tag, spelled in full — and spelled in ONE place, which is {@link
   * ReleaseLedger#TAG_PREFIX}.
   *
   * <p>Fully qualified rather than bare: git's own ref search would try {@code refs/<version>} and
   * a branch of that name before it reached the tag, and a release version is exactly the kind of
   * string somebody once made a branch out of. This resolves the release's commit at it — the one
   * read of it — and hands that commit to the ledger, which reads the released tree AT THE COMMIT
   * rather than resolving this same tag a second time (qits-1733): a second copy of the string would
   * be the one that quietly asked about a ref nobody has, and a second resolution of the very same
   * ref was the bug that spelling would have reintroduced.
   */
  static final String TAG_PREFIX = ReleaseLedger.TAG_PREFIX;

  /**
   * The {@code SCMRelease} fields this listener consumes, transcribed from qits-projects'
   * {@code service/…/bus/SCMRelease.java} — which is itself a field-for-field replica of the record
   * qits-workspaces published before the release door was retired, so the transcription did not have
   * to move when the publisher did.
   *
   * <p>{@code repositoryName} is the coordinate this service is keyed by and {@code repository} is
   * the registry's row id, which for a repository the platform manifest declares happens to be the
   * same string — so the name is preferred and the id is the fallback, exactly the tolerance
   * qits-ci's release join carries and for the same reason. {@code projectId} is transcribed
   * because it is part of the shape and deliberately unused: this service resolves a project from
   * its own {@code mt_repository} row. So is {@code branch}: it names the release request's fold,
   * {@code release/<id>}, and nothing here has an opinion about that ref.
   */
  public record ScmReleasePayload(
      String projectId, String repository, String repositoryName, String branch, String version) {}

  /**
   * The {@code SCMDeleteBranch} fields this listener consumes, transcribed from qits-githost's
   * {@code githost-events/…/SCMDeleteBranch.java}.
   *
   * <p>{@code repoName} and {@code projectId} are the address the push arrived on, echoed rather
   * than resolved, and both are <b>null for a push on the internal {@code /git/<storageId>}
   * scheme</b> — a mirror sync. Such an event names no repository this service can address and is
   * settled. {@code sha} is the OLD tip and is transcribed for the shape; nothing here reads it,
   * because a deleted branch has no head to record.
   */
  public record ScmDeleteBranchPayload(
      String repoId, String projectId, String repoName, String branch, String sha) {}

  /**
   * The {@code SCMPublishCommit} fields this listener consumes, transcribed from qits-githost's
   * {@code githost-events/…/SCMPublishCommit.java}.
   *
   * <p>That record carries more components — the head commit's parents, author, both timestamps
   * and the message — and none of them is here. Only what is
   * consumed is transcribed: the mapper ignores what it is not told about, which is what lets
   * qits-githost add a field without this becoming a poison payload. {@code sha} is kept because it
   * is what a log line needs to say WHICH push queued a scan; the scan itself resolves the head for
   * itself, once, exactly as every scan does.
   */
  public record ScmPublishCommitPayload(
      String repoId, String projectId, String repoName, String branch, String sha) {}

  @Inject MaintenanceStore store;

  @Inject ScanService scans;

  /**
   * The git host, for two questions only: which commit does a released tag name, and which does
   * {@code main} name now that it moved?
   *
   * <p>It is the same reader every scan resolves a head with, so a released tag is read exactly the
   * way a branch is — one tree of the root, and the {@code Git-Commit-Sha} the answer carries.
   */
  @Inject GitHostReader gitHost;

  /**
   * The release ledger: what the tree at this release's tag DECLARED.
   *
   * <p>The one row a release writes here. It says what the released tree was itself carrying,
   * which is the evidence an adoption of somebody ELSE's release is proved by — see {@link
   * ReleaseLedger} for why a pin read at a tag is release-grade and a pin read at {@code main} is
   * not — and it ties the version to its commit, which is how a {@code main} head is named once the
   * finalize merge makes the commit main's (qits-1257). It does not say a submodule has somewhere
   * to move: a cut release may never finalize.
   */
  @Inject ReleaseLedger ledger;

  /**
   * The gitlink latest's one rule (qits-1257): the release a repository's {@code main} head
   * carries. A main-branch push is when that head moves.
   */
  @Inject MainHeadLatest mainHead;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(RELEASE_SIGNATURE, DELETE_SIGNATURE, PUSH_SIGNATURE);
  }

  @Override
  public void onFrame(EventFrame frame) {
    switch (frame.name()) {
      case RELEASE_SIGNATURE -> onRelease(frame);
      case DELETE_SIGNATURE -> onDelete(frame);
      case PUSH_SIGNATURE -> onPush(frame);
      default ->
          // Unreachable through the funnel, which offers only the signatures above. Settling is the
          // right answer anyway: an event this listener did not ask for is not one it can act on.
          LOG.debugf("%s %s is not a signature this listener acts on", frame.name(), frame.id());
    }
  }

  // --- SCMRelease -----------------------------------------------------------------------------

  /**
   * A repository was released — which here is three facts, and all three are about the TAG: where a
   * gitlink pinned at this repository can move to, what the released tree declared, and what that
   * repository's own manifests now pin.
   */
  private void onRelease(EventFrame frame) {
    ScmReleasePayload release = decode(frame, ScmReleasePayload.class);
    if (release == null) {
      return;
    }
    onReleasedManifests(frame, release);
    onReleaseLedger(frame, release);
  }

  // --- SCMRelease, the manifest half ------------------------------------------------------------

  /**
   * <b>A release changed this repository's manifests, so they are re-read — AT THE TAG.</b>
   *
   * <p>This is the same conclusion {@link #onPush} draws from a push, reached from the other
   * signature because <b>a release did not use to produce a push this listener could see</b>. A
   * release merges onto {@code main} through qits-githost's REST door, which until qits-886 fired no
   * post-receive and published nothing, so {@link #onPush}'s main-branch test never matched a
   * release, and until this existed every release left the inventory holding whatever the last
   * scheduled scan happened to read. The door announces the finalize merge now, and {@link #onPush}
   * rescans main when it lands; this half stays because it is the one that is true at the moment of
   * release — main is merged later, after the deployment for a repository that deploys — and
   * because the release ledger is a tag-grade fact that a read of main cannot give.
   *
   * <p><b>Which was worst for the pins this service moves itself.</b> A bump lands on
   * {@code maintenance/<group>}, is released, and the pin it changed still read the old version here
   * until the next 00:30 scan — so the dependency page showed a repository as behind on the very
   * dependency it had itself brought up to date, and the nightly bump composed its pending set
   * against a day-stale manifest. Measured live 2026-09-09: qits-projects-frontend released
   * {@code @qits/ui-components 2026.908.204937} at 18:34 and this inventory's pin for it stayed at
   * {@code 2026.907.212914}.
   *
   * <p><b>The revision is {@code refs/tags/<version>} and NOT the default branch, which is the whole
   * correctness of this handler.</b> A release is a tag; {@code main} is finalized afterwards — at
   * once for a repository that deploys nothing, after the deployment for one that does. A scan of the
   * branch here reads the PREVIOUS release's manifests and stamps the row as freshly checked, which
   * is worse than not scanning because nothing afterwards looks stale enough to re-read. Measured the
   * same day on qits-artifacts-frontend: event at 19:20:53.190, scan at 19:20:53.222, pins two days
   * old. Reading the tag also needs no new event from anybody — it is true the moment the release is
   * announced, for every archetype, and it is the read {@code adoption/ReleaseLedger} already calls
   * release-grade.
   *
   * <p><b>Ahead of the ledger half, and deliberately.</b> Both halves reach the git host, but this
   * one is queued work while that one is a call in this frame's own thread: a tag the git host cannot
   * resolve throws there and the frame stays owed, and the scan must already be asked for so a retry
   * does not have to be what finally refreshes the manifests. A redelivery can queue a second scan
   * once the first has finished; a scan is idempotent and the debounce absorbs the burst, which is
   * the same trade {@link #onPush} makes for a merge.
   *
   * <p>A repository this inventory does not hold is skipped for the reason a push to one is: there is
   * no row to refresh, and the next scheduled scan reads the catalog and creates it. The ledger half
   * does NOT share that guard — it addresses such a repository by the payload's own project —
   * because a ledger row is keyed by name alone and needs no inventory row to be true.
   */
  private void onReleasedManifests(EventFrame frame, ScmReleasePayload release) {
    String repository = repositoryName(release);
    String version = trimmed(release.version());
    if (repository == null || version == null) {
      // The ledger half logs this too, and says the same thing about the same payload.
      return;
    }
    if (store.repository(repository).isEmpty()) {
      LOG.debugf(
          "%s %s released %s, which this inventory does not hold yet; no scan is queued",
          frame.name(), frame.id(), repository);
      return;
    }
    if (store.scanPending(repository)) {
      LOG.debugf(
          "%s %s released %s, and a scan of it is already queued or running",
          frame.name(), frame.id(), repository);
      return;
    }
    // INTERNAL scope, for the reason the push half takes it: every scan re-reads every manifest and
    // the scope governs only which half of the registry lookups refresh.
    UUID id =
        scans.request(ScanScope.INTERNAL, repository, ScanTrigger.EVENT, TAG_PREFIX + version);
    LOG.infof(
        "%s %s released %s %s; queued the scan %s of that repository at its tag",
        frame.name(), frame.id(), repository, version, id);
  }

  // --- SCMRelease, the ledger half ------------------------------------------------------------

  /**
   * <b>A repository was released, so the ledger learns what the released tree declared — and that
   * is ALL a release says about a gitlink now</b> (qits-1257).
   *
   * <p><b>It used to move the gitlink latest too, and that was the bug.</b> Until qits-1257 this
   * half wrote {@code mt_latest}'s GITLINK row from the tag — version and the commit it resolves to
   * — and told both re-plan hooks. But a release request tags its fold at RELEASED, and the tag is
   * merged into {@code main} only at FINALIZED; between the two a release can still fail. Two did:
   * {@code qits-edge-service 2026.1014.232301} and {@code qits-workspace-oci 2026.1015.21231} were
   * cut, their publish FAILED, they never finalized — and qits-qits-rr-137 pinned both and was
   * rejected. The gitlink latest is now the release a repository's {@code main} head carries,
   * written by {@link #onPush} when the finalize merge lands and by every scan at main ({@code
   * latest/MainHeadLatest}). This half no longer writes it, and no longer re-plans anything: a
   * release that is cut has given no submodule anywhere to move.
   *
   * <p><b>The ledger stays, and it is what makes the new rule work.</b> A {@code main} head is a
   * commit; the ledger row written here, keyed by the same commit, is how the push half later
   * names its version. It is also the adoption view's evidence — what THIS release declared, a
   * fact about a version rather than about the newest one — so a late-announced older release
   * deserves its row exactly as much as this morning's does.
   *
   * <p><b>The tag is resolved rather than correlated with a push.</b> A release IS the tag, and
   * the {@code SCMRelease} is published the moment qits-githost's tag primitive answers, so the tag
   * is there when this frame arrives; reading it is one call whose answer is the release's own
   * commit, handed to the ledger so its own read of the tree costs no second resolution.
   *
   * <p><b>The failure split is the seam's.</b> A git host that cannot be ASKED is retryable, so it
   * is thrown and the frame stays owed; a tag the git host does not HOLD is poison — the same
   * question has the same answer for ever — so it is a WARN and a return.
   */
  private void onReleaseLedger(EventFrame frame, ScmReleasePayload release) {
    String repository = repositoryName(release);
    String version = trimmed(release.version());
    if (repository == null || version == null) {
      LOG.debugf(
          "%s %s names no (repository, version) to record a release under",
          frame.name(), frame.id());
      return;
    }
    // The inventory's own project first: it is the value every other read of this repository is
    // addressed with. The payload's is the fallback for a repository no scan has reached yet.
    String project =
        store.repository(repository).map(row -> row.project).orElse(trimmed(release.projectId()));
    if (project == null) {
      LOG.debugf(
          "%s %s released %s under no project this service can address",
          frame.name(), frame.id(), repository);
      return;
    }
    TreeLookup tag = gitHost.head(project, repository, TAG_PREFIX + version);
    if (tag.status() == FileLookup.Status.UNREACHABLE) {
      // Retryable, and thrown on purpose: the claim rolls back and the next sweep offers this
      // release again.
      throw new IllegalStateException(
          "the git host could not resolve " + repository + " " + version + ": " + tag.message());
    }
    if (!tag.found() || tag.headSha() == null || tag.headSha().isBlank()) {
      LOG.warnf(
          "%s %s released %s %s, which the git host does not hold as a tag; no release is"
              + " recorded",
          frame.name(), frame.id(), repository, version);
      return;
    }
    ledger.record(project, repository, version, tag.headSha(), Instant.now());
  }

  /**
   * The repository a release names.
   *
   * <p>{@code repositoryName} is the coordinate this service is keyed by; {@code repository} is the
   * registry's row id, which for a repository the platform manifest declares is the same string.
   * The same tolerance the branch half carries, in one place because both halves need it.
   */
  private static String repositoryName(ScmReleasePayload release) {
    String name = trimmed(release.repositoryName());
    return name == null ? trimmed(release.repository()) : name;
  }

  // --- SCMDeleteBranch ------------------------------------------------------------------------

  /**
   * A maintenance branch is gone — released (the release deletes its named sources through
   * qits-githost's REST door, which announces it since qits-886), or deleted by hand.
   */
  private void onDelete(EventFrame frame) {
    ScmDeleteBranchPayload deleted = decode(frame, ScmDeleteBranchPayload.class);
    if (deleted == null) {
      return;
    }
    String branch = trimmed(deleted.branch());
    if (branch == null || !branch.startsWith(BRANCH_PREFIX)) {
      LOG.debugf(
          "%s %s deleted the branch '%s', which is not a maintenance branch",
          frame.name(), frame.id(), deleted.branch());
      return;
    }
    String repository = trimmed(deleted.repoName());
    String group = branch.substring(BRANCH_PREFIX.length());
    Optional<MtGroup> known = group(repository, group);
    if (known.isEmpty()) {
      LOG.warnf(
          "%s %s deleted %s of a repository or group this inventory does not hold (%s/%s);"
              + " it is settled",
          frame.name(), frame.id(), branch, deleted.repoName(), group);
      return;
    }
    // NONE, and the head cleared with it: the next bump branches from main again. This is also the
    // only thing that ever clears a STALE row — a branch somebody rewrote is one this service stops
    // writing to until it is gone, and this is how it learns that it is.
    store.recordBranch(repository, group, branch, BranchState.NONE, null, Instant.now());
    LOG.infof(
        "%s %s deleted %s of %s; the branch row is NONE and the next bump starts from main",
        frame.name(), frame.id(), branch, repository);
  }

  // --- SCMPublishCommit -----------------------------------------------------------------------

  /**
   * A push landed. If it was on the repository's own main branch, its gitlink latest is set to the
   * release main's head now carries, and its manifests are re-read — and that includes a release's
   * finalize merge into main, which qits-githost's REST door announces as an ordinary push since
   * qits-886.
   */
  private void onPush(EventFrame frame) {
    ScmPublishCommitPayload push = decode(frame, ScmPublishCommitPayload.class);
    if (push == null) {
      return;
    }
    String repository = trimmed(push.repoName());
    String branch = trimmed(push.branch());
    if (repository == null || branch == null) {
      // An id-addressed push (a mirror sync) announces no name at all. Nothing to look up.
      LOG.debugf(
          "%s %s names no (repository, branch) this inventory can address", frame.name(),
          frame.id());
      return;
    }
    Optional<MtRepository> row = store.repository(repository);
    // A repository no scan has read has no main branch on record; `main` is what a scan of it would
    // read too (`ManifestScanner.read`), so its latest is not left waiting for the first scan.
    String mainBranch = row.isPresent() ? trimmed(row.get().mainBranch) : DEFAULT_MAIN;
    if (mainBranch == null || !mainBranch.equals(branch)) {
      // Every branch on the platform rides this signature, maintenance branches this service pushed
      // included. Only the branch a scan reads manifests at can invalidate the inventory, and only
      // main's head is a finalized release.
      LOG.debugf(
          "%s %s pushed %s of %s, which is not its main branch (%s)",
          frame.name(), frame.id(), branch, repository, mainBranch);
      return;
    }
    // BEFORE the debounce, and never behind it: a scan already queued would set the same row, but
    // it runs on the worker whenever it gets there, and a member is meant to be pinnable minutes
    // after it finalizes (qits-1257).
    onMainMoved(frame, push, row, repository, mainBranch);
    if (row.isEmpty()) {
      // A repository the catalog has, that no scan has read yet — or one this platform does not
      // track at all. Either way there is no row to refresh; the next scheduled scan reads the
      // catalog and creates it.
      LOG.debugf(
          "%s %s pushed to %s, which this inventory does not hold yet; no scan is queued",
          frame.name(), frame.id(), repository);
      return;
    }
    if (store.scanPending(repository)) {
      // The debounce. A merge is a burst of pushes, and a scan already queued reads the head at the
      // moment it runs — which is at or after this push.
      LOG.debugf(
          "%s %s pushed %s of %s, and a scan of it is already queued or running",
          frame.name(), frame.id(), branch, repository);
      return;
    }
    // INTERNAL scope, not ALL: every scan re-reads every manifest whatever the scope says, and the
    // scope governs only which half of the registry lookups refresh. The internal half is one hop
    // away; asking Maven Central and npmjs about a push is the daily external scan's job.
    UUID id = scans.request(ScanScope.INTERNAL, repository, ScanTrigger.EVENT);
    LOG.infof(
        "%s %s pushed %s of %s at %s; queued the scan %s of that repository",
        frame.name(), frame.id(), branch, repository, push.sha(), id);
  }

  /**
   * <b>Main moved, so the gitlink latest is whatever release main's head now carries</b>
   * (qits-1257) — see {@link MainHeadLatest} for the rule and why it replaced "the tag that was just
   * cut".
   *
   * <p><b>The head is READ, not taken off the payload.</b> A frame can be handled late — a
   * redelivery, a catch-up after an outage — and its {@code sha} is main as it was then. The head
   * read now is main as it is, so the write may overwrite rather than move forward only: a late
   * frame cannot rewind anything, it can only find the row already true. It is the same one read
   * the release half makes of a tag.
   *
   * <p>A head that carries no release the ledger knows (a direct push to main, or a release whose
   * tree the ledger could not read) leaves the row as it was — {@link MainHeadLatest.Outcome#NO_RELEASE}
   * — and when the row does change, {@link MainHeadLatest#record} tells both re-plan hooks, the
   * gitlink bump's and the estate's (qits-1224), which is what the release half used to do.
   *
   * <p><b>Addressed like the release half</b>: the inventory's project, the payload's for a
   * repository no scan has reached. The failure split is the seam's: a git host that cannot be
   * ASKED is thrown and the frame stays owed; a main the git host does not hold is settled.
   */
  private void onMainMoved(
      EventFrame frame,
      ScmPublishCommitPayload push,
      Optional<MtRepository> row,
      String repository,
      String mainBranch) {
    String project = row.map(found -> found.project).orElse(trimmed(push.projectId()));
    if (project == null) {
      LOG.debugf(
          "%s %s pushed to %s under no project this service can address",
          frame.name(), frame.id(), repository);
      return;
    }
    TreeLookup head = gitHost.head(project, repository, mainBranch);
    if (head.status() == FileLookup.Status.UNREACHABLE) {
      // Retryable, and thrown on purpose: the claim rolls back and the push is offered again.
      throw new IllegalStateException(
          "the git host could not resolve " + mainBranch + " of " + repository + ": "
              + head.message());
    }
    if (!head.found() || head.headSha() == null || head.headSha().isBlank()) {
      LOG.warnf(
          "%s %s pushed %s of %s, which the git host does not hold; its gitlink latest is left"
              + " as it was",
          frame.name(), frame.id(), mainBranch, repository);
      return;
    }
    MainHeadLatest.Outcome outcome = mainHead.record(repository, head.headSha(), Instant.now());
    LOG.debugf(
        "%s %s moved %s of %s to %s: %s", frame.name(), frame.id(), mainBranch, repository,
        head.headSha(), outcome);
  }

  // --- shared ---------------------------------------------------------------------------------

  /**
   * The named group of the named repository, empty when either is unknown.
   *
   * <p>Both halves have to hold: a branch row is keyed on {@code (repository, group)} and writing
   * one for a group the repository does not have would put a row on a page nothing else ever
   * refreshes.
   */
  private Optional<MtGroup> group(String repository, String group) {
    if (repository == null || group == null || group.isBlank()) {
      return Optional.empty();
    }
    if (store.repository(repository).isEmpty()) {
      return Optional.empty();
    }
    return store.groups(repository).stream().filter(row -> group.equals(row.name)).findFirst();
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private static <P> P decode(EventFrame frame, Class<P> type) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), type);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload: %s",
          frame.name(), frame.id(), unreadable.toString());
      return null;
    }
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}

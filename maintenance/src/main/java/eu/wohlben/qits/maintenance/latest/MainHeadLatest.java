package eu.wohlben.qits.maintenance.latest;

import eu.wohlben.qits.maintenance.automation.UpstreamReplan;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtRelease;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>The latest of a submodule is that repository's {@code main} head</b> (qits-1257) — the one
 * rule every GITLINK {@code mt_latest} row is written by, and so the one rule estate pins and
 * gitlink bumps move a submodule to.
 *
 * <h2>Why not the newest release</h2>
 *
 * <p>Until qits-1257 this row was written from {@code SCMRelease}: a tag was cut, so the gitlink
 * latest moved to it. But a release request tags its fold at RELEASED, and only at FINALIZED — for
 * a deployable, once it is deployed — is the tag merged into {@code main}. Between the two the
 * release can still fail, and qits-qits-rr-137 was rejected for exactly that: its estate pins named
 * {@code qits-edge-service 2026.1014.232301} and {@code qits-workspace-oci 2026.1015.21231}, both
 * cut, both with a FAILED publish, neither ever finalized. A wrapper pinning them asserted an
 * estate that was never released.
 *
 * <p><b>Only finalization merges into {@code main}, so {@code main}'s head IS the newest finalized
 * release.</b> Measured on the estate: every member's {@code main} head carries its release tag
 * (edge's {@code main} is the commit of tag {@code 2026.1014.173542}). Code that is not on {@code
 * main} is never pinned or bumped to. That makes the rule a head read and a lookup, and not a
 * question about release states this service has no feed for.
 *
 * <h2>The head names a commit; the ledger names its version</h2>
 *
 * <p>The version is found in the release ledger ({@code mt_release}, written from {@code
 * SCMRelease} when the tag is cut — so it is there long before the finalize merge makes it the
 * head): the release whose {@code sha} is the head, through {@link GitlinkSha#same}. The ledger is
 * used to LABEL a known commit, never to find the newest release — "newest ledger row" is exactly
 * the rule that pinned rr-137. Two tags on one commit name the highest of them.
 *
 * <p><b>A head that carries no known release is left alone, and said so.</b> The row keeps what it
 * had (an ancestor's release, which is still on {@code main}); a version is never invented for a
 * commit nothing tagged. On this platform that is a {@code main} somebody pushed to directly, or a
 * release the ledger could not read.
 *
 * <h2>Three writers of one rule</h2>
 *
 * <ul>
 *   <li>{@code bus/ScmEventListener}, on a push to a repository's {@code main} — the finalize merge
 *       is announced as one since qits-886 — so a member is pinnable minutes after it finalizes
 *       rather than after the next scan;
 *   <li>{@code scan/ScanService}, on every scan at {@code main}, from the head that scan already
 *       resolved — no extra read — which catches what a missed frame did not write;
 *   <li>{@link #reconcile()}, once after every boot, one head read per repository, which is what
 *       heals the rows the old rule wrote (edge at {@code 2026.1014.232301}) on the deploy rather
 *       than at the next night.
 * </ul>
 *
 * <p>All three OVERWRITE ({@link MaintenanceStore#recordGitlinkLatest}) rather than move forward:
 * each resolves the CURRENT head at the moment it runs, so a late caller cannot rewind anything,
 * and repairing a row that names an unfinalized tag is a move backwards in version order. A row
 * that changed tells both re-plan hooks — {@link UpstreamReplan#latestMoved} for the gitlink bump's
 * consumers, {@link UpstreamReplan#released} for every open wrapper request's estate pins
 * (qits-1224) — exactly as {@code SCMRelease} used to.
 */
@ApplicationScoped
public class MainHeadLatest {

  private static final Logger LOG = Logger.getLogger(MainHeadLatest.class);

  /** What one head did to its repository's gitlink latest. */
  public enum Outcome {
    /** The row was written: a new row, or another version or commit than it named. */
    MOVED,
    /** The row already named the head's release. */
    UNCHANGED,
    /** The head carries no release the ledger knows; the row is left as it was. */
    NO_RELEASE
  }

  @Inject public MaintenanceStore store;

  @Inject public UpstreamReplan upstream;

  @Inject public GitHostReader gitHost;

  @Inject public WorkQueue work;

  /**
   * The release of {@code repository} whose tag is {@code sha}, from the ledger — the highest
   * version where two tags name one commit.
   */
  public Optional<MtRelease> releaseAt(String repository, String sha) {
    if (repository == null || sha == null || sha.isBlank()) {
      return Optional.empty();
    }
    MtRelease found = null;
    for (MtRelease release : store.releasesOf(repository)) {
      if (release.version == null || !GitlinkSha.same(release.sha, sha)) {
        continue;
      }
      if (found == null || VersionOrder.newer(Ecosystem.GITLINK, found.version, release.version)) {
        found = release;
      }
    }
    return Optional.ofNullable(found);
  }

  /**
   * Sets {@code repository}'s gitlink latest to the release its {@code main} head {@code headSha}
   * carries, and tells the re-plan hooks when the row changed. The caller resolved the head; a
   * retryable failure to do so is the caller's to decide about.
   */
  public Outcome record(String repository, String headSha, Instant now) {
    Optional<MtRelease> release = releaseAt(repository, headSha);
    if (release.isEmpty()) {
      Optional<MtLatest> row = store.latest(Ecosystem.GITLINK, repository);
      LOG.infof(
          "The main head %s of %s carries no release the ledger knows; its gitlink latest stays %s",
          headSha, repository, row.map(found -> found.latest).orElse("unrecorded"));
      return Outcome.NO_RELEASE;
    }
    String version = release.get().version;
    // The head as resolved, not the ledger's spelling: it is the full name the git host answered.
    if (!store.recordGitlinkLatest(repository, version, GitlinkSha.of(headSha), now)) {
      return Outcome.UNCHANGED;
    }
    LOG.infof(
        "The gitlink latest of %s is now %s (%s), the release its main head carries",
        repository, version, headSha);
    // Both are queue submits, never calls: a bus caller is inside a claim transaction (qits-1133).
    upstream.latestMoved(Ecosystem.GITLINK, repository);
    upstream.released(repository);
    return Outcome.MOVED;
  }

  /** Queued after every boot, behind the release ledger backfill that may label more heads. */
  void onStart(
      @Observes @Priority(Interceptor.Priority.APPLICATION + 600) StartupEvent event) {
    try {
      work.submit("the main-head gitlink latest reconcile", this::reconcile);
    } catch (RuntimeException e) {
      // It never stops the boot; the next scan at main reconciles every repository anyway.
      LOG.error("The main-head gitlink latest reconcile could not be queued.", e);
    }
  }

  /**
   * <b>Every repository's gitlink latest set to its {@code main} head's release</b> — one head read
   * per repository, overwriting. What heals the rows the cut-tag rule wrote, and catches a finalize
   * merge whose frame this service never saw.
   *
   * <p>A repository the catalog dropped ({@link RepositoryStatus#ABSENT}) or that has no project to
   * address is skipped. A head that cannot be read costs that repository and is retried by the next
   * scan at {@code main}; nothing here throws.
   *
   * @return how many rows were written
   */
  public int reconcile() {
    int moved = 0;
    for (MtRepository repository : store.repositories()) {
      if (repository.project == null
          || repository.project.isBlank()
          || RepositoryStatus.ABSENT.name().equals(repository.status)) {
        continue;
      }
      try {
        TreeLookup head =
            gitHost.head(repository.project, repository.name, repository.mainBranchOrDefault());
        if (!head.found() || head.headSha() == null || head.headSha().isBlank()) {
          LOG.debugf(
              "The main head of %s could not be read (%s); its gitlink latest is left to the next"
                  + " scan",
              repository.name,
              head.status() == FileLookup.Status.UNREACHABLE ? head.message() : head.status());
          continue;
        }
        if (record(repository.name, head.headSha(), Instant.now()) == Outcome.MOVED) {
          moved++;
        }
      } catch (RuntimeException e) {
        LOG.warnf(
            "The gitlink latest of %s could not be reconciled with its main head: %s",
            repository.name, e.toString());
      }
    }
    if (moved > 0) {
      LOG.infof("The main-head reconcile moved %d gitlink latest row(s).", moved);
    }
    return moved;
  }
}

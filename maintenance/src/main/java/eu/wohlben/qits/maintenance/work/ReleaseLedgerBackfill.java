package eu.wohlben.qits.maintenance.work;

import eu.wohlben.qits.maintenance.adoption.ReleaseLedger;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>The releases that happened before the ledger existed, read at boot.</b>
 *
 * <p>{@code adoption/ReleaseLedger} is filled by {@code bus/ScmEventListener}, one row per release
 * as it is announced — which makes the adoption view correct from the next release wave onwards and
 * wrong until then. On a frontend that is weeks. So the first boot after this deploys fills in what
 * the estate has already released, and the view is right at the deploy instead.
 *
 * <p><b>The source is {@code mt_latest}'s GITLINK rows, and it is the only source there is.</b>
 * Nothing else on this platform writes down "this repository released this version, at this commit"
 * — a registry knows versions of a PACKAGE and not of a repository, and a frontend publishes no
 * package at all. That listener recorded a gitlink latest for every release from the day it landed,
 * version and tag sha together, so the newest release of every repository the bus had announced was
 * sitting there waiting to be re-read at its tag. Since qits-1257 the row is the release a
 * repository's {@code main} head carries rather than the newest tag cut — still a release, version
 * and commit together, so this reads it unchanged.
 *
 * <p><b>ONE release per repository, and that is the whole of what a backfill can honestly be.</b>
 * {@code mt_latest} keeps the newest, so the history before it is not recoverable from anything
 * here. That is enough for the question this serves: a journey asks who is carrying a release NOW,
 * and a consumer's newest release is the one that answers it.
 *
 * <h2>Where it runs, and why not in the boot thread</h2>
 *
 * <p>Queued on {@link WorkQueue}, the single writer, exactly as every scan and every bump is — so
 * it is behind whatever the boot already queued, it cannot interleave with a scan rewriting the
 * inventory it reads, and the git host sees one caller. And it does not hold the boot: the catalog
 * is tens of rows and one hop each, but each hop is somebody else's HTTP, and a service that is not
 * there at all is worse than one whose adoption view fills in a minute later.
 *
 * <p><b>Skipping, per row, and none of the three skips is a failure.</b> A latest with no version,
 * a {@code source_url} that yields no sha (a row written before the sha was recorded, or by another
 * writer), a release the ledger already holds, and a repository the inventory has no row for — the
 * last is the only one worth a word: a ledger read needs a PROJECT to address the git host with,
 * that value lives on {@code mt_repository}, and a gitlink whose submodule name is not a catalog
 * name has no such row. It is a DEBUG rather than a WARN because it is the ordinary state of an
 * estate whose gitlinks outnumber its catalog.
 *
 * <p><b>A failure is one repository's, never the run's</b> — the standing rule of every loop in
 * this service that reads a peer per row. One unreachable git host costs one ledger row, and the
 * next boot asks again.
 */
@ApplicationScoped
public class ReleaseLedgerBackfill {

  private static final Logger LOG = Logger.getLogger(ReleaseLedgerBackfill.class);

  @Inject MaintenanceStore store;

  @Inject ReleaseLedger ledger;

  @Inject WorkQueue work;

  void onStart(@Observes StartupEvent event) {
    try {
      work.submit("the release ledger backfill", this::run);
    } catch (RuntimeException e) {
      // It never stops the boot, RestartRecovery's rule: a store that will not answer at startup is
      // a readiness question the deployer already health-gates.
      LOG.error("The release ledger backfill could not be queued.", e);
    }
  }

  /**
   * Reads every gitlink latest and records the release it names, where the ledger lacks it.
   *
   * <p>Public so a test can drive it without a second boot — the same seam {@code BumpService.sweep}
   * offers {@code MaintenanceApiTest}.
   *
   * @return how many releases were recorded
   */
  public int run() {
    int recorded = 0;
    int skipped = 0;
    for (MtLatest row : store.latestOf(Ecosystem.GITLINK)) {
      String repository = row.name;
      String version = row.latest;
      if (repository == null || version == null || version.isBlank()) {
        skipped++;
        continue;
      }
      Optional<String> sha = GitlinkSha.read(row.sourceUrl);
      if (sha.isEmpty()) {
        LOG.debugf(
            "The gitlink latest of %s at %s records no commit, so there is no release to fill in",
            repository, version);
        skipped++;
        continue;
      }
      if (store.releaseRecorded(repository, version)) {
        continue;
      }
      Optional<MtRepository> known = store.repository(repository);
      String project = known.map(found -> found.project).orElse(null);
      if (project == null || project.isBlank()) {
        LOG.debugf(
            "%s is a gitlink this inventory has no repository row for; its release %s is not"
                + " backfilled",
            repository, version);
        skipped++;
        continue;
      }
      try {
        // occurredAt is the latest row's own checked_at — the closest honest moment a backfill has.
        // The publisher's is on a frame that was handled before this table existed.
        if (ledger.record(project, repository, version, sha.get(), row.checkedAt)) {
          recorded++;
        }
      } catch (RuntimeException e) {
        LOG.warnf(
            e, "The release %s %s could not be read for the ledger; it is left to the next boot",
            repository, version);
      }
    }
    if (recorded > 0 || skipped > 0) {
      LOG.infof(
          "The release ledger backfill recorded %d release(s) and skipped %d gitlink latest row(s).",
          recorded, skipped);
    }
    return recorded;
  }
}

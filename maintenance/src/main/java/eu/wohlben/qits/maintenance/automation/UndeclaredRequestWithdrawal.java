package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.manifest.DependencyBumps;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>The requests this service opened on a repository its project no longer declares, withdrawn</b>
 * (qits-1695).
 *
 * <p>A retired repository — {@code qits-ci-runner-daemon}, {@code
 * qits-projects-desk-runner-daemon}, {@code qits-workspaces-runner-daemon} — stays a qits-projects
 * row after its wrapper entry went, and until this existed it kept receiving {@code
 * bump(dependencies)} requests that went red for ever. A full scan marks such a row {@link
 * RepositoryStatus#UNDECLARED}, which stops new ones; this clears the ones already open.
 *
 * <p><b>The guard is {@code AutomationService.withdrawIfNothingToBump}'s</b>: a request is withdrawn
 * only when the store remembers this service opening it as {@link MtReleaseRequest#MAIN_ONLY} AND
 * qits-projects reports main, plus this service's own automation branches, as its only sources
 * ({@link AutomationService#carriesOnlyMain}). Anything else carries somebody's work and is left
 * alone — including a request whose sources could not be read.
 *
 * <p><b>It runs on every full scan while a row stays UNDECLARED</b>, not only on the scan that
 * marked it, so a request opened in between — or a withdrawal qits-projects did not take — is caught
 * on the next one. <b>Nothing here throws</b>: an unreadable listing or a refused withdrawal is a
 * WARN, and the next scan retries it. The scan that called this must never fail on it.
 *
 * <p><b>The same withdrawal serves a row whose bumps are OFF</b> (qits-1784) — archived, or {@code
 * dependency-bumps: false}. That row stays OK, so the dispatcher's status filter does not keep it
 * away; its own {@code mt_repository.dependency_bumps} does, and {@link #sweepBumpsOff(String)}
 * clears the requests opened before the scan that read the switch. Same guard, its own reason
 * sentence. A full scan sweeps every such row; a scan of one repository — a push to its main —
 * sweeps that one, so archiving a repository withdraws its open bump within minutes rather than at
 * the next night's scan.
 */
@ApplicationScoped
public class UndeclaredRequestWithdrawal {

  private static final Logger LOG = Logger.getLogger(UndeclaredRequestWithdrawal.class);

  @Inject MaintenanceStore store;

  @Inject ReleaseRequestClient releases;

  /**
   * One pass over every UNDECLARED row.
   *
   * @return {@code <repository> <request>} for every request withdrawn this pass
   */
  public List<String> sweep() {
    List<String> withdrawn = new ArrayList<>();
    List<MtRepository> undeclared;
    try {
      undeclared =
          store.repositories().stream()
              .filter(row -> RepositoryStatus.UNDECLARED.name().equals(row.status))
              .toList();
    } catch (RuntimeException e) {
      LOG.warnf("The undeclared repositories could not be read; the next scan retries: %s",
          e.toString());
      return withdrawn;
    }
    for (MtRepository repository : undeclared) {
      String reason =
          "qits-maintenance opened this request for a dependency bump, and " + repository.name
              + " is no longer declared by its project, so it is not maintained (qits-1695)";
      settle(repository, "undeclared", reason, withdrawn);
    }
    return withdrawn;
  }

  /**
   * One pass over every OK row whose dependency bumps are off (qits-1784) when {@code repository} is
   * null — what a full scan runs — or over that ONE repository, which a scan of one runs once it
   * has written the row. A row whose bumps are on, or that is not OK, is left alone: not OK is the
   * undeclared sweep's or nobody's business.
   *
   * @return {@code <repository> <request>} for every request withdrawn this pass
   */
  public List<String> sweepBumpsOff(String repository) {
    List<String> withdrawn = new ArrayList<>();
    List<MtRepository> off;
    try {
      List<MtRepository> rows =
          repository == null
              ? store.repositories()
              : store.repository(repository).map(List::of).orElse(List.of());
      off =
          rows.stream()
              .filter(row -> RepositoryStatus.OK.name().equals(row.status))
              .filter(row -> !row.dependencyBumps)
              .toList();
    } catch (RuntimeException e) {
      LOG.warnf("The repositories whose bumps are off could not be read; the next scan retries: %s",
          e.toString());
      return withdrawn;
    }
    for (MtRepository row : off) {
      DependencyBumps bumps = DependencyBumps.stored(row.dependencyBumps, row.dependencyBumpsReason);
      String reason =
          "qits-maintenance opened this request for a dependency bump, and " + row.name
              + (DependencyBumps.ARCHIVED.equals(bumps.reason())
                  ? " is archived (repository.archived in .config/qits/repository.yml)"
                  : " switched its bumps off (dependency-bumps: false in"
                      + " .config/qits/maintenance.yml)")
              + ", so it is owed none (qits-1784)";
      settle(row, "bumps-off", reason, withdrawn);
    }
    return withdrawn;
  }

  /** One row's withdrawal, with its failure kept to that row. */
  private void settle(MtRepository repository, String what, String reason, List<String> withdrawn) {
    try {
      withdraw(repository, what, reason, withdrawn);
    } catch (RuntimeException e) {
      LOG.warnf("The requests of %s %s could not be settled; the next scan retries: %s", what,
          repository.name, e.toString());
    }
  }

  private void withdraw(
      MtRepository repository, String what, String reason, List<String> withdrawn) {
    if (repository.catalogId == null || repository.catalogId.isBlank()) {
      // Every request is addressed by the catalog id; a row that never carried one has none.
      return;
    }
    ReleaseRequestClient.Listing listing = releases.openRequests(repository.catalogId);
    if (!listing.readable()) {
      LOG.warnf("The open requests of %s %s could not be read; the next scan retries: %s", what,
          repository.name, listing.error());
      return;
    }
    for (ReleaseRequestClient.Listed listed : listing.requests()) {
      Optional<MtReleaseRequest> memo = store.releaseRequest(listed.id());
      if (memo.isEmpty()
          || !memo.get().opened
          || !MtReleaseRequest.MAIN_ONLY.equals(memo.get().purpose)) {
        continue;
      }
      if (!AutomationService.carriesOnlyMain(
          repository, releases.state(repository.catalogId, listed.id()))) {
        LOG.infof("Kept the release request %s of %s %s: it carries more than %s",
            listed.id(), what, repository.name, repository.mainBranchOrDefault());
        continue;
      }
      ReleaseRequestClient.RequestResult result =
          releases.withdraw(repository.catalogId, listed.id(), reason);
      switch (result.outcome()) {
        case REQUESTED, CONVERGED -> {
          store.requestWithdrawn(listed.id(), reason, Instant.now());
          LOG.infof("Withdrew the release request %s of %s %s: %s", listed.id(), what,
              repository.name, result.message());
          withdrawn.add(repository.name + " " + listed.id());
        }
        default ->
            LOG.warnf(
                "Could not withdraw the release request %s of %s %s; the next scan retries: %s",
                listed.id(), what, repository.name, result.message());
      }
    }
  }
}

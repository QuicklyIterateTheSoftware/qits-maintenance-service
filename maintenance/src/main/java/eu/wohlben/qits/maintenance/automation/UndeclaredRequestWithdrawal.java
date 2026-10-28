package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.entity.MtRepository;
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
      try {
        withdraw(repository, withdrawn);
      } catch (RuntimeException e) {
        LOG.warnf("The requests of undeclared %s could not be settled; the next scan retries: %s",
            repository.name, e.toString());
      }
    }
    return withdrawn;
  }

  private void withdraw(MtRepository repository, List<String> withdrawn) {
    if (repository.catalogId == null || repository.catalogId.isBlank()) {
      // Every request is addressed by the catalog id; a row that never carried one has none.
      return;
    }
    ReleaseRequestClient.Listing listing = releases.openRequests(repository.catalogId);
    if (!listing.readable()) {
      LOG.warnf("The open requests of undeclared %s could not be read; the next scan retries: %s",
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
        LOG.infof("Kept the release request %s of undeclared %s: it carries more than %s",
            listed.id(), repository.name, repository.mainBranchOrDefault());
        continue;
      }
      String reason =
          "qits-maintenance opened this request for a dependency bump, and " + repository.name
              + " is no longer declared by its project, so it is not maintained (qits-1695)";
      ReleaseRequestClient.RequestResult result =
          releases.withdraw(repository.catalogId, listed.id(), reason);
      switch (result.outcome()) {
        case REQUESTED, CONVERGED -> {
          store.requestWithdrawn(listed.id(), reason, Instant.now());
          LOG.infof("Withdrew the release request %s of undeclared %s: %s", listed.id(),
              repository.name, result.message());
          withdrawn.add(repository.name + " " + listed.id());
        }
        default ->
            LOG.warnf(
                "Could not withdraw the release request %s of undeclared %s; the next scan"
                    + " retries: %s",
                listed.id(), repository.name, result.message());
      }
    }
  }
}

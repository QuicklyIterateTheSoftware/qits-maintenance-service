package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.GitHostRefs;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>Deletes this service's own automation branches once their release request is closed.</b>
 *
 * <p>An OWN_BRANCH automation pushes {@code maintenance/automations/<kind>/<requestId>} and joins it
 * to the request. A request that releases deletes its named sources itself; one that ends any other
 * way — WITHDRAWN, OBSOLETE, or FINALIZED without the branch ever having been joined — leaves it
 * behind, and nothing else may delete it: an agent's credential cannot push to the namespace, and
 * this service owns it.
 *
 * <p><b>Why a sweep and not an event.</b> qits-projects announces a FOLD ({@code
 * ReleaseRequestChanged}) and a release ({@code SCMRelease}), but a withdrawal or an obsolescence
 * is a state change on a row over there and publishes nothing. So the trigger is a pass over every
 * repository with a catalog id, listing its branches and asking qits-projects about each request
 * one of ours names — hourly and shortly after boot, which is also what cleans the leftovers from
 * before this existed.
 *
 * <p><b>The closed-state rule is qits-projects' own {@code ENDED} set</b> — FINALIZED, WITHDRAWN,
 * OBSOLETE, the three states a request never leaves. Everything else is OPEN and the branch is
 * kept: PENDING, READY, RELEASED (mid-pipeline), and REJECTED, FAILED and CONFLICTED, which a new
 * fold re-arms to PENDING. A request qits-projects answers 404 for is deleted only once this
 * service's last row on the branch is older than {@link #UNKNOWN_AFTER} (or there is none); any
 * other unreadable answer keeps it, because a peer that could not be asked says nothing.
 *
 * <p><b>A branch with a REQUESTED or RUNNING automation row is never touched</b> — its run ends
 * first, and the next pass sees it. A failed delete is logged and the branch is simply seen again
 * next pass; a delete of a branch already gone is success. Nothing is recorded: the git host's ref
 * list is the state.
 */
@ApplicationScoped
public class AutomationBranchSweep {

  private static final Logger LOG = Logger.getLogger(AutomationBranchSweep.class);

  /** qits-projects' {@code ReleaseRequestRepository.ENDED}: the states a request never leaves. */
  static final Set<String> CLOSED = Set.of("FINALIZED", "WITHDRAWN", "OBSOLETE");

  /** How long a branch whose request qits-projects does not know is left alone. */
  public static final Duration UNKNOWN_AFTER = Duration.ofDays(7);

  private static final Set<String> ACTIVE =
      Set.of(BumpStatus.REQUESTED.name(), BumpStatus.RUNNING.name());

  @Inject MaintenanceStore store;

  @Inject GitHostRefs refs;

  @Inject ReleaseRequestClient releases;

  /** What one pass did, for the log line and the tests. */
  public record Result(List<String> deleted, List<String> failed) {}

  /** One pass over every repository the catalog gave an id. */
  public Result sweep() {
    return sweep(Instant.now());
  }

  /** One pass, as of {@code now} — the age rule reads it. */
  public Result sweep(Instant now) {
    List<String> deleted = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (MtRepository repository : store.repositories()) {
      if (repository.catalogId == null || repository.catalogId.isBlank()) {
        continue;
      }
      GitHostRefs.Branches branches = refs.branches(repository.catalogId);
      if (!branches.ok()) {
        LOG.debugf("automation-branch sweep skipped %s: %s", repository.name, branches.error());
        continue;
      }
      for (String branch : branches.branches()) {
        String requestId = requestOf(branch);
        if (requestId == null) {
          continue;
        }
        String reason = closedReason(repository, branch, requestId, now);
        if (reason == null) {
          continue;
        }
        GitHostRefs.Deletion deletion =
            refs.delete(repository.catalogId, repository.project, repository.name, branch);
        String where = repository.name + " " + branch;
        switch (deletion.outcome()) {
          case DELETED -> {
            LOG.infof("Deleted automation branch %s: %s", where, reason);
            deleted.add(where);
          }
          case ALREADY_GONE -> LOG.debugf("Automation branch %s was already gone", where);
          case FAILED -> {
            LOG.warnf(
                "Could not delete automation branch %s (%s); the next sweep retries: %s",
                where, reason, deletion.message());
            failed.add(where);
          }
        }
      }
    }
    if (!deleted.isEmpty() || !failed.isEmpty()) {
      LOG.infof(
          "automation-branch sweep: %d deleted, %d failed", deleted.size(), failed.size());
    }
    return new Result(deleted, failed);
  }

  /**
   * Why this branch may go, or null to keep it.
   *
   * <p>Order matters: an active row keeps the branch before anything is asked over the wire.
   */
  private String closedReason(
      MtRepository repository, String branch, String requestId, Instant now) {
    List<MtBump> rows = store.automationsOnBranch(repository.name, branch);
    if (rows.stream().anyMatch(row -> ACTIVE.contains(row.status))) {
      return null;
    }
    ReleaseRequestClient.ReleaseState state = releases.state(repository.catalogId, requestId);
    if (state.readable()) {
      return CLOSED.contains(state.state())
          ? "release request " + state.name(requestId) + " is " + state.state()
          : null;
    }
    if (!state.unknown()) {
      return null;
    }
    Instant last =
        rows.stream()
            .map(row -> row.finishedAt != null ? row.finishedAt : row.startedAt)
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(null);
    if (last != null && last.isAfter(now.minus(UNKNOWN_AFTER))) {
      return null;
    }
    return "release request "
        + requestId
        + " is unknown to qits-projects and "
        + (last == null ? "this service has no row on the branch" : "the branch was last touched " + last);
  }

  /**
   * The request id an automation branch names — {@code maintenance/automations/<kind>/<requestId>},
   * exactly one segment each — or null for any other branch.
   */
  public static String requestOf(String branch) {
    if (branch == null || !branch.startsWith(AutomationService.BRANCH_PREFIX)) {
      return null;
    }
    String[] rest = branch.substring(AutomationService.BRANCH_PREFIX.length()).split("/", -1);
    if (rest.length != 2 || rest[0].isBlank() || rest[1].isBlank()) {
      return null;
    }
    return rest[1];
  }
}

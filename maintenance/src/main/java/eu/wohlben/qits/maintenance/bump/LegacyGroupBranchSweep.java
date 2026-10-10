package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.automation.AutomationService;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.GitHostRefs;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>The cutover sweep (qits-1133 R2): every {@code maintenance/<group>} branch still standing is
 * retired.</b>
 *
 * <p>Nothing writes a group branch any more — R2 switched the path off and R5 removed it; the {@code
 * dependency-bump} release-request automation writes a repository's pending pins at the fold of the
 * request they belong to. What the old path left behind is swept here, once per branch, and the
 * sweep is kept after R5 because it is idempotent and cheap: a branch somebody pushes under the old
 * name by hand is still cleaned up.
 *
 * <ol>
 *   <li><b>A bump-only request standing on it is WITHDRAWN</b>, with a reason naming qits-1133. A
 *       request is bump-only when every BRANCH source it names is the repository's main branch or a
 *       {@code maintenance/} branch (its tags are released versions, which are not sources anybody
 *       wrote). Its pins are re-planned by the new path: the dispatcher opens a main-only {@code
 *       LOWEST} request whose pre-run writes them.
 *   <li><b>A PERSON'S request is left open</b> — only the branch goes. qits-projects re-folds the
 *       request without it on the deletion, and that request's own {@code dependency-bump} pre-run
 *       re-plans the pins the branch carried.
 *   <li><b>The branch is deleted</b>, the way {@code AutomationBranchSweep} deletes an automation
 *       branch ({@link GitHostRefs#delete}, {@code qits:system}); an already-gone branch is success.
 *   <li><b>The {@code mt_branch} row is RETIRED</b> ({@link MaintenanceStore#retireBranch}), a state
 *       nothing rewrites, and the group's bumps still owed a release ask are closed — so nothing
 *       resurrects either.
 * </ol>
 *
 * <p><b>Idempotent by construction, and one-shot per branch.</b> The git host's ref list is the
 * input: a deleted branch is not listed on the next pass, and a row whose branch is not listed is
 * retired without a word to anybody. A second pass after a clean first one withdraws nothing,
 * deletes nothing and writes nothing.
 *
 * <p><b>It waits rather than guesses.</b> A branch a RELEASED request names is mid-pipeline and that
 * release's landing deletes it; and an unreadable listing, an unreadable request or a withdrawal
 * qits-projects did not take keeps the branch until the next pass, because deleting under a
 * bump-only request first would re-fold it into a main-only one nobody asked for. Every action is
 * logged.
 *
 * <p>On the worker, with the automation-branch sweep: a few minutes after boot, then hourly.
 */
@ApplicationScoped
public class LegacyGroupBranchSweep {

  private static final Logger LOG = Logger.getLogger(LegacyGroupBranchSweep.class);

  /** The reason every withdrawal carries — what a person reading the request sees. */
  public static final String WITHDRAW_REASON =
      "qits-1133: maintenance/<group> branches are retired. This bump-only request is withdrawn;"
          + " its pins are re-planned by the dependency-bump release-request automation";

  /** The states in which a request still takes a branch — qits-projects' re-armable set. */
  private static final Set<String> OPEN = Set.of("PENDING", "READY", "REJECTED", "FAILED", "CONFLICTED");

  /** Released and not yet landed: its sources are deleted by the landing. */
  private static final String RELEASED = "RELEASED";

  @Inject MaintenanceStore store;

  @Inject GitHostRefs refs;

  @Inject ReleaseRequestClient releases;

  /**
   * What one pass did.
   *
   * @param withdrawn {@code <repository> <request>} for every bump-only request withdrawn
   * @param deleted {@code <repository> <branch>} for every branch deleted (or found already gone)
   * @param keptOpen {@code <repository> <request>} for every person's request left open
   * @param retired {@code <repository> <branch>} for every row retired this pass
   * @param waiting {@code <repository> <branch>} for every branch left for a later pass, with why
   */
  public record Result(
      List<String> withdrawn,
      List<String> deleted,
      List<String> keptOpen,
      List<String> retired,
      List<String> waiting) {

    public boolean idle() {
      return withdrawn.isEmpty() && deleted.isEmpty() && keptOpen.isEmpty() && retired.isEmpty();
    }
  }

  /** One pass over every repository the catalog gave an id. */
  public Result sweep() {
    Result result =
        new Result(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>());
    for (MtRepository repository : store.repositories()) {
      if (repository.catalogId == null || repository.catalogId.isBlank()) {
        continue;
      }
      sweep(repository, result);
    }
    if (!result.idle() || !result.waiting().isEmpty()) {
      LOG.infof(
          "legacy group-branch sweep (qits-1133): %d request(s) withdrawn, %d branch(es) deleted,"
              + " %d person's request(s) left open, %d row(s) retired, %d branch(es) waiting",
          result.withdrawn().size(), result.deleted().size(), result.keptOpen().size(),
          result.retired().size(), result.waiting().size());
    }
    return result;
  }

  private void sweep(MtRepository repository, Result result) {
    GitHostRefs.Branches listed = refs.branches(repository.catalogId);
    if (!listed.ok()) {
      LOG.debugf("legacy group-branch sweep skipped %s: %s", repository.name, listed.error());
      return;
    }
    Set<String> standing = new HashSet<>();
    for (String branch : listed.branches()) {
      if (groupOf(branch) != null) {
        standing.add(branch);
      }
    }
    Instant now = Instant.now();

    // A ROW WHOSE BRANCH IS GONE is retired without a word to anybody — the release that landed it
    // deleted it, or a person did.
    for (MtBranch row : store.branches(repository.name)) {
      if (BranchState.RETIRED.name().equals(row.state) || standing.contains(row.branch)) {
        continue;
      }
      if (store.retireBranch(
          repository.name, row.groupName, row.branch, retiredNote(row.branch), now)) {
        LOG.infof("Retired the branch row of %s %s: the branch is gone", repository.name,
            row.branch);
        result.retired().add(repository.name + " " + row.branch);
      }
    }
    if (standing.isEmpty()) {
      return;
    }

    Requests requests = null;
    for (String branch : standing.stream().sorted().toList()) {
      String group = groupOf(branch);
      String where = repository.name + " " + branch;
      if (requests == null) {
        requests = requests(repository);
      }
      if (requests.error() != null) {
        LOG.infof("Left %s for the next pass: %s", where, requests.error());
        result.waiting().add(where + ": " + requests.error());
        continue;
      }
      String why = settleRequests(repository, branch, requests, result);
      if (why != null) {
        LOG.infof("Left %s for the next pass: %s", where, why);
        result.waiting().add(where + ": " + why);
        continue;
      }
      GitHostRefs.Deletion deletion =
          refs.delete(repository.catalogId, repository.project, repository.name, branch);
      switch (deletion.outcome()) {
        case DELETED, ALREADY_GONE -> {
          LOG.infof(
              "Deleted the retired group branch %s (qits-1133)%s", where,
              deletion.outcome() == GitHostRefs.Deleted.ALREADY_GONE ? "; it was already gone" : "");
          result.deleted().add(where);
          if (store.retireBranch(repository.name, group, branch, retiredNote(branch), now)) {
            result.retired().add(where);
          }
        }
        case FAILED -> {
          LOG.warnf("Could not delete the retired group branch %s; the next pass retries: %s",
              where, deletion.message());
          result.waiting().add(where + ": " + deletion.message());
        }
      }
    }
  }

  /**
   * Withdraws every bump-only open request naming the branch and leaves a person's alone.
   *
   * @return null when the branch may go now, otherwise why it waits
   */
  private String settleRequests(
      MtRepository repository, String branch, Requests requests, Result result) {
    String main = repository.mainBranchOrDefault();
    for (Map.Entry<String, Request> entry : requests.byId().entrySet()) {
      Request request = entry.getValue();
      if (!request.branches().contains(branch)) {
        continue;
      }
      String id = entry.getKey();
      if (RELEASED.equals(request.state())) {
        return "release request " + id + " is RELEASED, and its landing deletes the branch";
      }
      if (!OPEN.contains(request.state())) {
        continue;
      }
      if (!bumpOnly(request.branches(), main)) {
        LOG.infof(
            "Left release request %s of %s open: it is a person's (sources %s); deleting %s"
                + " re-folds it without the branch, and its dependency-bump pre-run re-plans the"
                + " pins",
            id, repository.name, request.branches(), branch);
        result.keptOpen().add(repository.name + " " + id);
        continue;
      }
      ReleaseRequestClient.RequestResult withdrawal =
          releases.withdraw(repository.catalogId, id, WITHDRAW_REASON);
      switch (withdrawal.outcome()) {
        case REQUESTED, CONVERGED -> {
          store.requestWithdrawn(id, WITHDRAW_REASON, Instant.now());
          requests.byId().put(id, new Request("WITHDRAWN", request.branches()));
          LOG.infof("Withdrew the bump-only release request %s of %s (on %s): %s", id,
              repository.name, branch, withdrawal.message());
          result.withdrawn().add(repository.name + " " + id);
        }
        default -> {
          return "the withdrawal of bump-only request " + id + " was not taken: "
              + withdrawal.message();
        }
      }
    }
    return null;
  }

  /** Every BRANCH source is the main branch or a maintenance branch. */
  static boolean bumpOnly(List<String> branches, String main) {
    for (String source : branches) {
      if (!source.equals(main) && !source.startsWith(BumpService.BRANCH_PREFIX)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The group a legacy branch carries — {@code maintenance/<group>}, exactly one segment after the
   * prefix — or null for any other branch, the automations' {@code maintenance/automations/…} and
   * the retired {@code maintenance/baselines/…} included.
   */
  public static String groupOf(String branch) {
    if (branch == null
        || !branch.startsWith(BumpService.BRANCH_PREFIX)
        || branch.startsWith(AutomationService.BRANCH_PREFIX)) {
      return null;
    }
    String group = branch.substring(BumpService.BRANCH_PREFIX.length());
    return group.isBlank() || group.contains("/") ? null : group;
  }

  private static String retiredNote(String branch) {
    return "group bumps are retired (qits-1133); " + branch + " was swept, not released";
  }

  /** A request as far as the sweep reads it. */
  private record Request(String state, List<String> branches) {}

  /** A repository's requests that can still carry a branch, with their sources; or why not. */
  private record Requests(Map<String, Request> byId, String error) {}

  /**
   * The repository's listing, and the sources of every request in it that is open or released —
   * read once per repository per pass, and only for a repository with a group branch standing.
   */
  private Requests requests(MtRepository repository) {
    ReleaseRequestClient.Listing listing = releases.requests(repository.catalogId);
    if (!listing.readable()) {
      return new Requests(Map.of(), listing.error());
    }
    Map<String, Request> byId = new LinkedHashMap<>();
    for (ReleaseRequestClient.Listed listed : listing.requests()) {
      if (!OPEN.contains(listed.state()) && !RELEASED.equals(listed.state())) {
        continue;
      }
      ReleaseRequestClient.ReleaseState state =
          releases.state(repository.catalogId, listed.id());
      if (!state.readable()) {
        return new Requests(Map.of(), Optional.ofNullable(state.error())
            .orElse("release request " + listed.id() + " could not be read"));
      }
      byId.put(listed.id(), new Request(state.state(), state.branches()));
    }
    return new Requests(byId, null);
  }
}

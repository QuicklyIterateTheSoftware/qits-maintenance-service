package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * <b>What a group bump's branch is cut from, and whether the branch has to be rebuilt</b> (qits-1081).
 *
 * <h2>Why main is not always the base</h2>
 *
 * <p>qits-projects folds every released-but-unmerged tag of a repository into each of its release
 * requests. A release that is cut and stuck before its merge to main — a deployment that has not
 * succeeded, a merge that will not apply — is therefore in every fold, and a {@code
 * maintenance/<group>} branch cut from main that edits the same pin line that tag already moved (a
 * Dockerfile {@code ARG BASE=…:<ver>}, a property in a pom) conflicts with it in every fold, for as
 * long as the tag stays unmerged. Cut from the TAG, the branch already carries what the tag
 * changed, and the fold has nothing left to conflict on.
 *
 * <h2>The decision</h2>
 *
 * <ol>
 *   <li>The newest released-but-unmerged tag, from qits-projects' listing ({@link
 *       ReleaseRequestClient#unmergedReleases}). None: the base is main, as it always was.
 *   <li>Whether main already contains it — the git host's ancestry door, asked at main's head. A
 *       tag main contains is not "unmerged" in any sense that matters, whatever the listing says.
 *       Contained: main. Not contained: {@code refs/tags/<version>}.
 * </ol>
 *
 * <h2>Every bump rebuilds the branch (user decision 2026-10-09)</h2>
 *
 * <p>A {@code maintenance/<group>} branch is always exactly ONE commit on its base. Whenever the
 * branch exists, its head travels as {@code replaceHead}, on main and on a tag alike, and the step
 * rebuilds the branch from the base with every pending change of the group under {@code
 * --force-with-lease}. The pins already on the branch are not lost: the changes are computed
 * against the scanned base, so every pin the branch moved is still pending there and is in the
 * payload again. That replaced "ff-only, never force", which stacked one commit per bump.
 *
 * <p><b>Every unreadable answer is main, said once at WARN, and never a failed bump.</b> The base is
 * an improvement on the bump, not a precondition of it: main is exactly what every bump sent before
 * the tag base existed, so falling back to it costs the conflict this avoids and nothing else.
 *
 * <p><b>A STALE branch is never rebuilt.</b> STALE is a hand-written commit — somebody owns that
 * branch — so it gets no {@code replaceHead}. The step makes its own check as well: it rebuilds only
 * over commits authored {@code maintenance@qits.local} on top of the base, and exits {@link
 * #NOT_OURS_EXIT} otherwise.
 */
@ApplicationScoped
public class BumpBase {

  private static final Logger LOG = Logger.getLogger(BumpBase.class);

  /**
   * The step's exit when the branch carries a commit it did not write, or moved after it was read:
   * nothing was pushed, and the branch is somebody else's now — STALE.
   */
  public static final int NOT_OURS_EXIT = 42;

  /** A tag base is sent as a full ref; the step fetches a {@code refs/…} base as written. */
  public static final String TAG_PREFIX = "refs/tags/";

  @Inject ReleaseRequestClient releases;

  @Inject GitHostReader gitHost;

  @Inject MaintenanceStore store;

  /**
   * The base a bump is sent with.
   *
   * @param baseRef the repository's main branch, or {@code refs/tags/<version>}
   * @param tag the unmerged release the base is cut from, or null when it is main
   * @param replaceHead the branch head the step rebuilds over, or null when there is no branch
   */
  public record Choice(String baseRef, ReleaseRequestClient.Unmerged tag, String replaceHead) {

    static Choice main(MtRepository repository) {
      return new Choice(mainBranch(repository), null, null);
    }

    /** Whether the branch is to be rebuilt rather than continued. */
    public boolean rebuild() {
      return replaceHead != null;
    }
  }

  /** The repository's main branch, as every bump was cut from before qits-1081. */
  public static String mainBranch(MtRepository repository) {
    return repository.mainBranch == null || repository.mainBranch.isBlank()
        ? "main"
        : repository.mainBranch;
  }

  /** The base and the replace head for one group's branch. */
  public Choice choose(MtRepository repository, String group, String branch) {
    return choose(repository, group, branch, newestUnmerged(repository));
  }

  /**
   * The repository's newest released tag that the listing says has not reached main, or null — for
   * no tag, and for a listing that could not be read (WARN).
   */
  public ReleaseRequestClient.Unmerged newestUnmerged(MtRepository repository) {
    if (repository.catalogId == null || repository.catalogId.isBlank()) {
      LOG.warnf(
          "%s has no catalog id, so its unmerged releases cannot be read; the bump is cut from %s",
          repository.name, mainBranch(repository));
      return null;
    }
    ReleaseRequestClient.UnmergedReleases answer = releases.unmergedReleases(repository.catalogId);
    if (!answer.readable()) {
      LOG.warnf("%s; the bump of %s is cut from %s", answer.error(), repository.name,
          mainBranch(repository));
      return null;
    }
    return answer.newest();
  }

  /** {@link #choose(MtRepository, String, String)} with the listing already read. */
  public Choice choose(
      MtRepository repository, String group, String branch, ReleaseRequestClient.Unmerged tag) {
    String base = baseRef(repository, tag);
    return new Choice(
        base, base.startsWith(TAG_PREFIX) ? tag : null, replaceHead(repository, group, branch));
  }

  /** The base alone: main, or {@code refs/tags/<version>} when main does not contain the tag. */
  private String baseRef(MtRepository repository, ReleaseRequestClient.Unmerged tag) {
    if (tag == null) {
      return mainBranch(repository);
    }
    String main = mainBranch(repository);
    TreeLookup mainHead = gitHost.head(repository.project, repository.name, main);
    if (!mainHead.found()) {
      LOG.warnf(
          "%s's %s could not be read, so whether it contains the release %s is unknown; the bump"
              + " is cut from %s",
          repository.name, main, tag.version(), main);
      return main;
    }
    GitHostReader.Containment onMain =
        gitHost.contains(repository.catalogId, tag.releasedSha(), mainHead.headSha());
    if (!onMain.readable()) {
      LOG.warnf("%s; the bump of %s is cut from %s", onMain.error(), repository.name, main);
      return main;
    }
    if (onMain.contains()) {
      return main;
    }
    return TAG_PREFIX + tag.version();
  }

  /** The branch head to rebuild over: the head of a branch that exists and is not STALE. */
  private String replaceHead(MtRepository repository, String group, String branch) {
    if (stale(repository, group)) {
      return null;
    }
    TreeLookup head = gitHost.head(repository.project, repository.name, branch);
    // No branch: the step starts it. An unreadable one is the same answer for the payload — the
    // step makes its own read, and its ownership check is what licenses the rebuild.
    return head.found() ? head.headSha() : null;
  }

  /** Whether somebody wrote the group's branch by hand, so this service leaves it alone. */
  public boolean stale(MtRepository repository, String group) {
    return store.branch(repository.name, group)
        .map(row -> BranchState.STALE.name().equals(row.state))
        .orElse(false);
  }

  /**
   * Whether the group's branch exists and does NOT contain the tag — the dispatcher's test for
   * rebuilding a CONFLICTED request on its newest unmerged release (qits-1081). Unreadable answers,
   * a STALE branch and no branch are all "no".
   */
  public boolean lacksTag(
      MtRepository repository, String group, String branch, ReleaseRequestClient.Unmerged tag) {
    if (tag == null || stale(repository, group)) {
      return false;
    }
    TreeLookup head = gitHost.head(repository.project, repository.name, branch);
    if (!head.found()) {
      return false;
    }
    GitHostReader.Containment onBranch =
        gitHost.contains(repository.catalogId, tag.releasedSha(), head.headSha());
    if (!onBranch.readable()) {
      LOG.warnf(
          "%s; whether %s of %s carries %s is unknown, so it is not rebuilt on it",
          onBranch.error(), branch, repository.name, tag.version());
      return false;
    }
    return !onBranch.contains();
  }
}

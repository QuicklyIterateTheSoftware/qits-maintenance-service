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
 *   <li>Only on a tag base: whether {@code maintenance/<group>} exists and does NOT contain the tag.
 *       Then its head travels as {@code replaceHead}, and the step REBUILDS the branch on the tag
 *       under {@code --force-with-lease} on that head. A branch that already contains the tag is
 *       continued; no branch at all is started from the tag.
 * </ol>
 *
 * <p><b>Every unreadable answer is main, said once at WARN, and never a failed bump.</b> The base is
 * an improvement on the bump, not a precondition of it: main is exactly what every bump sent before
 * this existed, so falling back to it costs the conflict this avoids and nothing else. An unreadable
 * BRANCH containment is the same reading one level down — no {@code replaceHead}, so the branch is
 * continued and nothing is force-pushed on a guess.
 *
 * <p><b>A STALE branch is never rebuilt.</b> STALE is a hand-written commit the ff-only push refused
 * to overwrite — somebody owns that branch — and {@code replaceHead} is the one field that would
 * let the step throw it away.
 */
@ApplicationScoped
public class BumpBase {

  private static final Logger LOG = Logger.getLogger(BumpBase.class);

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
   * @param replaceHead the branch head the step rebuilds over, or null to continue (or start) it
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
    if (tag == null) {
      return Choice.main(repository);
    }
    String main = mainBranch(repository);
    TreeLookup mainHead = gitHost.head(repository.project, repository.name, main);
    if (!mainHead.found()) {
      LOG.warnf(
          "%s's %s could not be read, so whether it contains the release %s is unknown; the bump"
              + " is cut from %s",
          repository.name, main, tag.version(), main);
      return Choice.main(repository);
    }
    GitHostReader.Containment onMain =
        gitHost.contains(repository.catalogId, tag.releasedSha(), mainHead.headSha());
    if (!onMain.readable()) {
      LOG.warnf("%s; the bump of %s is cut from %s", onMain.error(), repository.name, main);
      return Choice.main(repository);
    }
    if (onMain.contains()) {
      return Choice.main(repository);
    }
    String baseRef = TAG_PREFIX + tag.version();
    return new Choice(baseRef, tag, replaceHead(repository, group, branch, tag));
  }

  /** The branch head to rebuild over, or null to continue the branch (or start it). */
  private String replaceHead(
      MtRepository repository, String group, String branch, ReleaseRequestClient.Unmerged tag) {
    TreeLookup head = gitHost.head(repository.project, repository.name, branch);
    if (!head.found()) {
      // No branch: the step starts it from the tag. An unreadable one is the same answer for the
      // payload — the step makes its own read, and with no replaceHead it never force-pushes.
      return null;
    }
    boolean stale =
        store.branch(repository.name, group)
            .map(row -> BranchState.STALE.name().equals(row.state))
            .orElse(false);
    if (stale) {
      return null;
    }
    GitHostReader.Containment onBranch =
        gitHost.contains(repository.catalogId, tag.releasedSha(), head.headSha());
    if (!onBranch.readable()) {
      LOG.warnf(
          "%s; %s of %s is continued rather than rebuilt on %s",
          onBranch.error(), branch, repository.name, tag.version());
      return null;
    }
    return onBranch.contains() ? null : head.headSha();
  }
}

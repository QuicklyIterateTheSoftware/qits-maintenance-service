package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;

/**
 * One repository's tree at one FOLD, read through {@link GitHostReader} — the thin view a kind's
 * {@code applicability} and {@code plan} read the fold through.
 *
 * <p><b>Addressed by the sha, never by {@code release/<request>}.</b> The fold branch moves the
 * moment the request re-folds, and an applicability decided against whatever it pointed at by then
 * would be an answer about a fold this outcome is not for. Every read here names {@link #sha()}, so
 * every answer is about one commit. The git host's five outcomes are passed through unchanged:
 * ABSENT is "this fold does not carry it", everything but FOUND and ABSENT is a read that failed.
 */
public final class FoldReader {

  private final GitHostReader gitHost;

  private final String project;

  private final String repository;

  private final String sha;

  public FoldReader(GitHostReader gitHost, String project, String repository, String sha) {
    this.gitHost = gitHost;
    this.project = project;
    this.repository = repository;
    this.sha = sha;
  }

  /** The commit every read names. */
  public String sha() {
    return sha;
  }

  /** One file at the fold. */
  public FileLookup file(String path) {
    return gitHost.blob(project, repository, sha, path);
  }

  /** One directory listing at the fold; an empty path is the root. */
  public TreeLookup tree(String path) {
    return gitHost.tree(project, repository, sha, path == null ? "" : path);
  }
}

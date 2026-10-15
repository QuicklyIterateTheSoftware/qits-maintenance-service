package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One repository's tree at one FOLD, read through {@link GitHostReader} — the thin view a kind's
 * {@code applicability} and {@code plan} read the fold through.
 *
 * <p><b>Addressed by the sha, never by {@code release/<request>}.</b> The fold branch moves the
 * moment the request re-folds, and an applicability decided against whatever it pointed at by then
 * would be an answer about a fold this outcome is not for. Every read here names {@link #sha()}, so
 * every answer is about one commit. The git host's five outcomes are passed through unchanged:
 * ABSENT is "this fold does not carry it", everything but FOUND and ABSENT is a read that failed.
 *
 * <p><b>A file is read once per reader</b> (qits-1258). One settle asks several kinds about one
 * fold, and more than one of them reads the same file — {@code estate-pins} reads the fold's
 * {@code .gitmodules} for its committable paths, its inputs and its plan. A commit never changes, so
 * an answer that is a fact about it (FOUND, ABSENT, GONE) is kept for the life of the reader, which
 * is one settle; a read that failed is not a fact and is asked again.
 */
public final class FoldReader {

  private final GitHostReader gitHost;

  private final String project;

  private final String repository;

  private final String sha;

  /** The files already read at {@link #sha}, by path — facts only, never a failed read. */
  private final Map<String, FileLookup> files = new ConcurrentHashMap<>();

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
    FileLookup known = files.get(path);
    if (known != null) {
      return known;
    }
    FileLookup read = gitHost.blob(project, repository, sha, path);
    if (read.status() == FileLookup.Status.FOUND
        || read.status() == FileLookup.Status.ABSENT
        || read.status() == FileLookup.Status.GONE) {
      files.put(path, read);
    }
    return read;
  }

  /** One directory listing at the fold; an empty path is the root. */
  public TreeLookup tree(String path) {
    return gitHost.tree(project, repository, sha, path == null ? "" : path);
  }

  /**
   * Every entry under the fold's root in one call (qits-1258); see {@link
   * GitHostReader#recursiveTree}. Not recursive when the git host predates the parameter — the
   * caller reads {@link TreeLookup#recursive()} and falls back to {@link #tree}.
   */
  public TreeLookup recursiveTree() {
    return gitHost.recursiveTree(project, repository, sha);
  }
}

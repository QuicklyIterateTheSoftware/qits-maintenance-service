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
 *
 * <p><b>So is a listing</b> (qits-1697). A settle that answered nothing it could store used to be
 * recomputed on every ask, about ten sequential git-host reads, and the same root and recursive
 * listings were among them more than once. They are kept by the same rule: FOUND, ABSENT and GONE
 * are facts about the commit; UNREACHABLE is not. A recursive ask a git host answered with its plain
 * listing ({@link TreeLookup#recursive()} false) is kept too — that the host ignores the parameter
 * is as much a fact for this sha as the listing itself, and the caller's fallback reads it the same
 * way the second time.
 */
public final class FoldReader {

  private final GitHostReader gitHost;

  private final String project;

  private final String repository;

  private final String sha;

  /** The files already read at {@link #sha}, by path — facts only, never a failed read. */
  private final Map<String, FileLookup> files = new ConcurrentHashMap<>();

  /** The directories already listed at {@link #sha}, by path — facts only, as {@link #files}. */
  private final Map<String, TreeLookup> trees = new ConcurrentHashMap<>();

  /** The recursive listing of the root, once it is a fact; null until then. */
  private volatile TreeLookup recursive;

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
    if (fact(read.status())) {
      files.put(path, read);
    }
    return read;
  }

  /** Whether an answer is about the commit rather than about the read: anything but a failure. */
  private static boolean fact(FileLookup.Status status) {
    return status == FileLookup.Status.FOUND
        || status == FileLookup.Status.ABSENT
        || status == FileLookup.Status.GONE;
  }

  /** One directory listing at the fold; an empty path is the root. */
  public TreeLookup tree(String path) {
    String directory = path == null ? "" : path;
    TreeLookup known = trees.get(directory);
    if (known != null) {
      return known;
    }
    TreeLookup read = gitHost.tree(project, repository, sha, directory);
    if (fact(read.status())) {
      trees.put(directory, read);
    }
    return read;
  }

  /**
   * Every entry under the fold's root in one call (qits-1258); see {@link
   * GitHostReader#recursiveTree}. Not recursive when the git host predates the parameter — the
   * caller reads {@link TreeLookup#recursive()} and falls back to {@link #tree}.
   */
  public TreeLookup recursiveTree() {
    TreeLookup known = recursive;
    if (known != null) {
      return known;
    }
    TreeLookup read = gitHost.recursiveTree(project, repository, sha);
    if (fact(read.status())) {
      recursive = read;
    }
    return read;
  }
}

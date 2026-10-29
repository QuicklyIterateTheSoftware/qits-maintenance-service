package eu.wohlben.qits.maintenance.model;

/**
 * What the last scan of one repository found.
 *
 * <p>Everything except {@link #OK} carries a sentence in {@code mt_repository.message}, because the
 * UI shows the reason rather than the word.
 */
public enum RepositoryStatus {
  /** Scanned; the pins in {@code mt_pin} are this repository's, at {@code head_sha}. */
  OK,

  /**
   * There is nothing to scan, for one of two reasons, and the row's message says which.
   *
   * <ul>
   *   <li>the catalog names it and the git host has no such repository, or it has no {@code main};
   *   <li><b>the catalog no longer names it at all</b> — a rename or a removal, found by a full
   *       scan reconciling the inventory against the listing. The row is KEPT so the name still
   *       answers honestly and so {@code catalog_id} survives for the graph rows that translate
   *       through it, but its pins and its groups are gone: a repository the catalog dropped has no
   *       line anybody can edit and contributes no pending change.
   * </ul>
   *
   * <p>Either way the pins are gone, and the clock never bumps a row that is not {@link #OK}.
   */
  ABSENT,

  /** The git host could not be asked. The previous scan's pins are left standing — an unreachable
   * peer is not evidence that a repository stopped pinning anything. */
  UNREACHABLE,

  /** {@code .config/qits/maintenance.yml} does not parse. Nothing is bumped for this repository
   * until it does: a broken grouping would put changes on a branch nobody asked for. */
  CONFIG_ERROR,

  /**
   * qits-projects lists the row, but no wrapper entry names it (its {@code declared} is false), so
   * it is not part of its project and is not maintained (qits-1695). A retired repository —
   * {@code qits-ci-runner-daemon} and its kind — stays a catalog row after its project dropped the
   * submodule, and maintaining it opened {@code bump(dependencies)} requests that went red for
   * ever.
   *
   * <p>Only a FULL scan writes it, and it is handled like {@link #ABSENT}: the row is kept, its pins
   * and its groups are gone, and its repository is never read. A single-repository scan — an event's
   * or a person's — skips such a row rather than rescanning it back to {@link #OK}; the full scan that
   * next finds it declared is the only way back. While it stays UNDECLARED, every full scan also
   * withdraws the main-only requests this service opened on it.
   */
  UNDECLARED;

  /**
   * Whether a stored status word is {@link #ABSENT} or {@link #UNDECLARED}: a row kept for its name
   * and its {@code catalog_id} whose repository this service no longer maintains (qits-1735).
   *
   * <p>A rename keeps qits-projects' row id, so the ABSENT old row and the live new one share a
   * {@code catalog_id}, and every reader that resolves an id or a candidate through the inventory
   * has to prefer the live one — or a release gets filed under a name nothing will ever scan again,
   * and the keep-set holds it for ever. {@link #UNREACHABLE} and {@link #CONFIG_ERROR} are not
   * retired: those repositories are still maintained, merely not readable right now.
   */
  public static boolean retired(String status) {
    return ABSENT.name().equals(status) || UNDECLARED.name().equals(status);
  }
}

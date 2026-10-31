package eu.wohlben.qits.maintenance.manifest;

/**
 * <b>Whether a repository is owed dependency bumps at all</b> (qits-1784), and why not when it is
 * not.
 *
 * <p>Two files decide it, both the repository's own and both read at the same commit: {@code
 * dependency-bumps} in {@link GroupConfig#PATH} and {@code repository.archived} in {@link
 * RepositoryConfig#PATH}. <b>The switch, when present, decides</b>; absent, the bumps are on unless
 * the repository is archived. So {@code dependency-bumps: true} keeps an archived repository's
 * bumps coming, and {@code dependency-bumps: false} stops a live one's.
 *
 * <p><b>Off is not a status.</b> The repository row stays OK and its pins are still read, stored and
 * served — the artifact GC's keep-set and the dependency page need them whether or not anything is
 * bumped. What it changes is that the dispatcher skips the row, the {@code dependency-bump}
 * automation plans nothing at its folds, and a main-only request this service already opened for it
 * is withdrawn.
 *
 * @param on whether bumps are dispatched and planned for this repository
 * @param reason {@link #ARCHIVED} or {@link #SWITCH} when off, null when on — the short word stored
 *     on {@code mt_repository.dependency_bumps_reason}
 */
public record DependencyBumps(boolean on, String reason) {

  /** Off because {@code repository.archived} is true and no switch said otherwise. */
  public static final String ARCHIVED = "archived";

  /** Off because {@code dependency-bumps: false} says so. */
  public static final String SWITCH = "switch";

  /** What a repository that says nothing gets — and what an unusable read carries. */
  public static final DependencyBumps ON = new DependencyBumps(true, null);

  /**
   * The effective rule.
   *
   * @param dependencyBumps the {@code dependency-bumps} switch, or null when it is not set
   * @param archived {@code repository.archived}
   */
  public static DependencyBumps of(Boolean dependencyBumps, boolean archived) {
    if (dependencyBumps != null) {
      return dependencyBumps ? ON : new DependencyBumps(false, SWITCH);
    }
    return archived ? new DependencyBumps(false, ARCHIVED) : ON;
  }

  /** The decision a row stored, read back. A null reason on an off row reads as the switch. */
  public static DependencyBumps stored(boolean on, String reason) {
    if (on) {
      return ON;
    }
    return new DependencyBumps(false, ARCHIVED.equals(reason) ? ARCHIVED : SWITCH);
  }

  /** Why the bumps are off, as a sentence for a log line or a withdrawal; null when they are on. */
  public String sentence() {
    if (on) {
      return null;
    }
    return ARCHIVED.equals(reason)
        ? RepositoryConfig.PATH + " says the repository is archived, and "
            + GroupConfig.PATH + " does not set `dependency-bumps`"
        : GroupConfig.PATH + " sets `dependency-bumps: false`";
  }
}

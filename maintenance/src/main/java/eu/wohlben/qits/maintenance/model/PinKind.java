package eu.wohlben.qits.maintenance.model;

/**
 * What can be done with a pin, which is not the same question as who published it.
 *
 * <p><b>Two of the four are "nothing".</b> {@link #REACTOR} and {@link #UNRESOLVED} are pins with no
 * line to edit; they are recorded because they are real dependencies a person looking at a
 * repository expects to see, and they are excluded from every registry lookup, from pending and
 * from a bump payload.
 */
public enum PinKind {
  /** Published by this platform, into qits-artifacts. */
  INTERNAL,

  /** Everybody else's, read through qits-mirror. */
  EXTERNAL,

  /**
   * THE REPOSITORY'S OWN ARTIFACT, by either of the two ways a pom says so: its version comes from
   * one of maven's built-in coordinates ({@code ${project.version}} and its spellings), or its
   * {@code groupId:artifactId} is a module of this same reactor.
   *
   * <p>Either way it moves with this repository's own release train and there is no line anywhere
   * to bump. Nearly every multi-module pom on the platform has several, and offering an upgrade for
   * one would be offering to overwrite what a release stamps.
   */
  REACTOR,

  /**
   * An expression this service could not resolve. The pin is recorded with the expression still in
   * it, so a person can see what a repository actually wrote — and nothing is asked of a registry,
   * because the first live scan died turning one of these into a URL.
   */
  UNRESOLVED;

  /** Whether a pin of this kind has a version this service can compare and a line it can bump. */
  public boolean actionable() {
    return this == INTERNAL || this == EXTERNAL;
  }
}

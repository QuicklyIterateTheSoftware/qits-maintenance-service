package eu.wohlben.qits.maintenance.model;

/**
 * The four worlds a manifest can pin in.
 *
 * <p>It decides three things at once: which parser reads a manifest, which registry answers "what
 * is the latest", and which of the bump pipeline's two steps can edit the line. A new ecosystem
 * arrives as a parser, a resolver and a step together.
 *
 * <p>The wire spelling is lower case — it is a label the UI groups by, not a constant a client
 * branches on.
 */
public enum Ecosystem {
  /** {@code pom.xml}, named {@code groupId:artifactId}. */
  MAVEN,

  /** {@code package.json} plus its lock, named {@code @scope/name} or {@code name}. */
  NPM,

  /** {@code Dockerfile} {@code FROM} lines, named by the image without its tag. */
  DOCKER,

  /**
   * A {@code .gitmodules} submodule, named by the repository its url points at.
   *
   * <p><b>The one ecosystem with no registry.</b> Nothing publishes a gitlink, so no lookup can ask
   * what its newest version is — the fact arrives as a {@code SCMRelease} off the bus instead, and
   * the daily scan neither refreshes it nor clears it. It is also the one whose PIN is not a
   * version: a gitlink records a commit sha, which nothing orders, so pending is a difference
   * rather than a comparison. See {@code pending/PendingChanges} and {@code latest/GitlinkSha}.
   */
  GITLINK;

  /**
   * <b>{@code daemon} — a released artifact word that is deliberately NOT a constant above, and the
   * one place that is written down.</b>
   *
   * <p>qits-ci publishes a {@code SoftwareRelease} of type {@code daemon} for every binary that
   * lands in the platform's {@code daemons} store, and since the qits CLI's version became a pom pin
   * — qits-ci pins {@code eu.wohlben.qits:qits-platform-access-cli-binary}, whose version IS the
   * store coordinate of the binary the same release published — those releases have to leave an
   * {@code mt_artifact} row, or the GC's keep-set has the hole {@code control/CarriedDaemons}
   * exists to close.
   *
   * <p><b>A row, and still not an ecosystem.</b> The enum above prices a fifth value at "a parser, a
   * resolver and a step together" and a daemon binary has none of the three: no manifest declares
   * one as a dependency, no registry answers what its newest version is, and no bump step can edit a
   * line that does not exist. So {@link #of} keeps answering EMPTY for it, and everything that
   * branches on an {@code Ecosystem} — the latest column and the pending rule — keeps passing it
   * by. What a daemon row carries is a fact about a RELEASE, which is exactly the half of this
   * schema that is keyed by the stored string rather than by this enum — and that includes the SBOM
   * fetch, whose route is keyed by the released artifact's type: {@code SbomClient} addresses a
   * daemon row's document by this word, and its components join the graph like any other's.
   *
   * <p>Compared as the stored string wherever it is needed: the listener that writes the row, {@code
   * SbomClient.TYPES} that addresses its document, {@code ArtifactGraph.daemonsReleasedWith} that
   * reads it back, and the {@code ecosystem} the pin source serves the derived row under — a spelling qits-artifacts' {@code
   * MaintenanceHttpDependencyPins} matches exactly.
   */
  public static final String DAEMON_WIRE_NAME = "daemon";

  /** The stored and served spelling. */
  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  /** The ecosystem for a wire name, or empty. */
  public static java.util.Optional<Ecosystem> of(String wireName) {
    for (Ecosystem value : values()) {
      if (value.wireName().equalsIgnoreCase(wireName)) {
        return java.util.Optional.of(value);
      }
    }
    return java.util.Optional.empty();
  }
}

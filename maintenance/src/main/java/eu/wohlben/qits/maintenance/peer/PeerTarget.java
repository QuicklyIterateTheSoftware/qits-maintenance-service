package eu.wohlben.qits.maintenance.peer;

/**
 * Every address this service reads or writes.
 *
 * <p><b>Nine targets, one credential.</b> A target is an ADDRESS — a base url a path is appended
 * to; configured for eight of them, derived in code for {@link #NPM_MIRROR}. There used to be five oidc clients, one per peer SERVICE, because a token used to be
 * cut FOR one service's own audience; service-client-identity-plan.md's C4 replaced all five with
 * one named client, {@code qits}, addressed to the one platform audience every receiver now accepts.
 * {@link PeerTokens} mints through it for every target below — the three registry targets on
 * qits-artifacts already shared one client because they are one service behind three path prefixes,
 * and now every target does.
 *
 * <p><b>There was a tenth, qits-configuration, and it went with the release trains.</b> It answered
 * "who runs which image version" for a train end that was POLLED because no event covers it —
 * re-read inside a human GET, every ten minutes, for a fact qits-configuration owns and shows one
 * click away. The ad-hoc adoption routes ask nothing over the wire at all.
 */
public enum PeerTarget {

  /**
   * qits-projects — the catalog, the name-addressed coordinate every other read uses, and since the
   * release door was retired the RELEASE ASK as well.
   *
   * <p>A bump that ends SUCCEEDED opens a release request here on the branch it just pushed:
   * {@code POST /projects/api/repositories/<repoId>/release-requests}, addressed by the catalog row's
   * own id. See {@link eu.wohlben.qits.maintenance.bump.ReleaseRequestClient}. It is the same
   * credential every catalog read already mints — the route admits {@code qits:system}, which is what
   * {@link PeerClient} presents — so the write cost this service nothing a read did not already have.
   */
  PROJECTS("qits.maintenance.targets.projects-url"),

  /** qits-githost — the manifests, read at one revision per repository. */
  GITHOST("qits.maintenance.targets.githost-url"),

  /** qits-ci — the trigger that applies a bump, and the run it names. */
  CI("qits.maintenance.targets.ci-url"),

  /** qits-artifacts' hosted maven repository: {@code maven-metadata.xml} for internal artifacts. */
  MAVEN_REGISTRY("qits.maintenance.registries.maven-url"),

  /** qits-artifacts' hosted npm repository: the packument's {@code dist-tags.latest}. */
  NPM_REGISTRY("qits.maintenance.registries.npm-url"),

  /** qits-artifacts' OCI registry: {@code /<name>/tags/list}. */
  OCI_REGISTRY("qits.maintenance.registries.oci-url"),

  /**
   * qits-artifacts' SBOM store: {@code /artifacts/sboms/<type>/<name>/-/<version>}.
   *
   * <p><b>A fourth address on the same service, and it carries no path prefix.</b> The three
   * registry keys above each name a MOUNT — {@code /artifacts/maven/maven} is one repository row
   * and moving it is a deployment's decision — while the SBOM route is qits-artifacts' own API and
   * its whole path belongs to the caller. So the key is a bare host and the prefix is in the code.
   */
  ARTIFACTS_SBOM("qits.maintenance.targets.artifacts-url"),

  /** qits-platform-mirror's Maven Central pull-through. */
  MAVEN_MIRROR("qits.maintenance.mirror.maven-url"),

  /**
   * qits-platform-mirror's npmjs pull-through — and the one target that is NOT configuration.
   *
   * <p><b>No key, on purpose (qits-472).</b> There used to be {@code qits.maintenance.mirror.npm-url},
   * and all it ever held was {@code http://${QITS_ENVIRONMENT:dev}-qits-platform-mirror:8080/npm/npmjs}
   * — a fact this process can work out and no deployment decides. So the address is derived here, in
   * {@link #derivedBase}, and a deployment row still setting {@code QITS_MAINTENANCE_MIRROR_NPM_URL}
   * is dead: nothing reads it.
   *
   * <p><b>The internal alias, not the public edge.</b> The platform is moving callers onto
   * {@code https://mirror.qits.<QITS_DOMAIN>}, but the edge admits HTTP Basic with a client pair or a
   * {@code qits_tok_} token, and every call from here carries the {@code qits} client's minted bearer
   * (see {@link PeerClient#send}). Sending this one target a different credential is new plumbing,
   * so it stays on the wire alias until that exists.
   */
  NPM_MIRROR(null);

  /** Where qits-platform-mirror serves its npmjs cache, at the root of its host. */
  static final String NPM_MIRROR_PATH = "/npm/npmjs";

  /** The environment a container that was told none is in — the shipped config's own default. */
  static final String DEFAULT_ENVIRONMENT = "dev";

  private final String urlKey;

  PeerTarget(String urlKey) {
    this.urlKey = urlKey;
  }

  /** The config key holding this target's base url, or null for a target derived in code. */
  public String urlKey() {
    return urlKey;
  }

  /**
   * The base url of a target that has no key, for the environment this process runs in.
   *
   * @param environment {@code QITS_ENVIRONMENT} as qits-deployments injected it, or null/blank for
   *     none — which means {@code dev}, as it does everywhere else in the shipped config
   */
  public String derivedBase(String environment) {
    if (this != NPM_MIRROR) {
      throw new IllegalStateException(this + " is configured, not derived: " + urlKey);
    }
    String env =
        environment == null || environment.isBlank() ? DEFAULT_ENVIRONMENT : environment.trim();
    return "http://" + env + "-qits-platform-mirror:8080" + NPM_MIRROR_PATH;
  }
}

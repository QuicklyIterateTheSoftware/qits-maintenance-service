package eu.wohlben.qits.maintenance.peer;

/**
 * Every address this service reads or writes.
 *
 * <p><b>Eleven targets, one credential.</b> A target is an ADDRESS — a base url a path is appended
 * to; all eleven are configured, ten on wire aliases and {@link #NPM_MIRROR} on the public edge.
 * Two of them share one key ({@link #ARTIFACTS_SBOM} and {@link #ARTIFACTS_DOCS}, both
 * qits-artifacts' own API). There used to be five oidc clients, one per peer SERVICE, because a
 * token used to be cut FOR one service's own audience; the epic qits-540 dossier's 'Plan (as of
 * 2026-09-13)', C4, replaced all five with one named client, {@code qits}, addressed to the one
 * platform audience every receiver now accepts. {@link PeerTokens} mints through it for every
 * target below — the three registry targets on qits-artifacts already shared one client because
 * they are one service behind three path prefixes, and now every target does.
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

  /**
   * qits-deployments — {@code GET /deployments/api/pins}, the versions that serve and the ones a
   * rollback restores. {@code GET /pins} keeps what those releases declared (qits-1172).
   */
  DEPLOYMENTS("qits.maintenance.targets.deployments-url"),

  /**
   * qits-system — {@code GET /system/api/targets}, every target's slots, reservations and leases in
   * use, which is where the dispatcher reads how much CI room the estate has (qits-1769). qits-ci
   * holds no runner since epic qits-1481, so the free-slot count is read where the leases are
   * granted. See {@link eu.wohlben.qits.maintenance.bump.SystemClient}.
   */
  SYSTEM("qits.maintenance.targets.system-url"),

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

  /**
   * qits-artifacts' docs store: {@code /artifacts/docs/docs/@changelog/<repository>}, the listing of
   * every changelog a repository's releases published (epic qits-893).
   *
   * <p><b>The same key as {@link #ARTIFACTS_SBOM}, on purpose.</b> It is the same service and, like
   * the SBOM route, the docs route is qits-artifacts' own API rather than a mount a deployment may
   * move — so the key stays a bare host and the prefix is in {@link
   * eu.wohlben.qits.maintenance.bump.changelog.ChangelogClient}. A second key would be a second
   * place to point at the one qits-artifacts.
   */
  ARTIFACTS_DOCS("qits.maintenance.targets.artifacts-url"),

  /** qits-mirror's Maven Central pull-through. */
  MAVEN_MIRROR("qits.maintenance.mirror.maven-url"),

  /**
   * qits-mirror's npmjs pull-through.
   *
   * <p><b>Configured like {@link #MAVEN_MIRROR} again.</b> From qits-472 to qits-533 it carried no
   * key: all {@code qits.maintenance.mirror.npm-url} ever held was
   * {@code http://${QITS_ENVIRONMENT:dev}-qits-mirror:8080/npm/npmjs} — a fact this process could
   * work out and no deployment decided — so the address was derived in code instead. That bought
   * nothing: every other line in the shipped mirror and registry block derives from
   * {@code QITS_ENVIRONMENT} the same way and is configuration anyway, so the one exception was a
   * special case this target did not need.
   *
   * <p><b>Through the public edge, the one target that is.</b> The shipped default is
   * {@code https://mirror.qits.${QITS_DOMAIN}/npm/npmjs}, by the owner's rule that the platform is
   * reached through its edge, and the same host lockfiles and the bump pipeline name for npmjs.
   * {@code QITS_DOMAIN} is injected into every container by qits-deployments. It needs no new
   * credential: qits-edge validates any non-{@code qits_tok_} Bearer JWT offline (issuer
   * {@code https://idp.qits.<domain>}, audience {@code qits-platform}, idp's JWKS), and that is the
   * {@code qits} client's minted bearer {@link PeerClient#send} already attaches. The edge strips
   * inbound {@code X-Qits-*} headers, which is harmless here: the mirror's npm route is gated only
   * at the edge. Every other target stays on its wire alias.
   */
  NPM_MIRROR("qits.maintenance.mirror.npm-url");

  private final String urlKey;

  PeerTarget(String urlKey) {
    this.urlKey = urlKey;
  }

  /** The config key holding this target's base url. Never null: every target is configured. */
  public String urlKey() {
    return urlKey;
  }
}

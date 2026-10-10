package eu.wohlben.qits.maintenance.adoption;

import eu.wohlben.qits.maintenance.adoption.DownstreamResolver.Closure;
import eu.wohlben.qits.maintenance.adoption.DownstreamResolver.Downstream;
import eu.wohlben.qits.maintenance.adoption.ReleaseCoordinates.Coordinate;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtRelease;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>HOW FAR ONE RELEASE ACTUALLY GOT — answered from the graph at query time, over the closure
 * {@link DownstreamResolver} traced.</b>
 *
 * <p>The closure says who COULD adopt. This says who DID, and it says it by reading what a RELEASE
 * of the consumer is evidence of — never what its working tree happens to say today.
 *
 * <h2>TWO EVIDENCE KINDS, and both are about a release</h2>
 *
 * <p>A repository has adopted a release when one of its OWN releases proves it is carrying the
 * released coordinate at a version at least the released one. Two different things prove that, and
 * they answer two different questions about the same release:
 *
 * <ul>
 *   <li><b>what the release CONTAINS</b> — a component in its bill of materials ({@code
 *       mt_artifact_component}, {@link MaintenanceStore#dependents}). An SBOM sees TRANSITIVES,
 *       which no manifest anywhere names, and it is immutable because a published version is.
 *   <li><b>what the release DECLARED</b> — a pin in the tree at its own tag ({@code
 *       mt_release_pin}, {@link MaintenanceStore#releasePinCarriers}). It sees what nothing ever
 *       published: a frontend's release is a git tag and no registry artifact at all, and a
 *       service's docker-image SBOM lists maven components only, because a compiled Angular dist
 *       carries no npm metadata.
 * </ul>
 *
 * <p><b>Why the second one is not "a pin", which this class was right to refuse.</b> The objection
 * to a pin was never that it is a pin: it is that a pin read at {@code main} is a fact about
 * somebody's WORKING TREE — nothing polls it, it moves under you, it is revertible, and a verdict
 * computed from one would flicker. A pin read at {@code refs/tags/<version>} is a different fact. A
 * tag is immutable and it is tied to the consumer's own released version, which is exactly what
 * this answer REPORTS. So the rule stands as it always did — the evidence is about a release, not a
 * working tree — and the ledger is what makes it obtainable for a repository that publishes nothing.
 *
 * <p><b>Measured, not theoretical.</b> On 2026-09-08 the journey of {@code
 * qits-ui-components-jslib 2026.906.164412} named fifteen frontends at depth 1 and fifteen services
 * at depth 2 and reported every one of them PENDING, while every one of them had picked the release
 * up weeks earlier: {@code dependents(npm, @qits/ui-components)} was empty and {@code
 * qits-ci-service}'s newest document held 239 maven components and no npm ones. Neither hop had a
 * document that could ever have named the coordinate. See {@link ReleaseLedger}.
 *
 * <p><b>At least, not exactly.</b> The comparison is inclusive on purpose: a consumer that took the
 * released version has adopted it, and one that skipped straight past it to the release after has
 * adopted it too. A component BELOW it is the consumer still carrying the old copy, which is
 * precisely the state this question exists to show.
 *
 * <p><b>A component with no ecosystem never matches</b> — {@code pkg:golang/…},
 * {@code pkg:generic/…}, a document with no purl. Same rule as the rest of the graph: a name in a
 * world this platform does not inventory cannot be compared with anything here. It falls out of the
 * shape below rather than needing a guard, because every lookup is keyed by an {@link Ecosystem}.
 *
 * <h2>{@code adoptedVersion} IS THE ADOPTER'S OWN RELEASE, never the dependency version it took</h2>
 *
 * <p>The matched component's version is what PROVES the adoption and is deliberately not what is
 * reported. What comes out is the adopter's own released version, because that is what the value is
 * read by: it is half of the address of the release request that adoption opened, and that resolver
 * matches the ADOPTER's own releases on version. A dependency version there resolves to nothing, or
 * worse to an unrelated release that happens to share a number.
 *
 * <p><b>The EARLIEST matching release wins</b>, by {@code occurred_at}. A library released in March
 * that the frontend took in April and has shipped in twenty releases since was adopted in April —
 * the newest release carrying it is not when it arrived.
 *
 * <h2>The chain, and why a PENDING parent stops it</h2>
 *
 * <p>A journey is walked over the closure's parent edges, nearest hop first. The root's requirement
 * is the released version itself. A downstream that adopted it at its own version <b>Vₑ</b> then
 * becomes the requirement for ITS children — they have to be carrying Vₑ or later of what it
 * published, which is the next link of the same chain.
 *
 * <p>So a child of a PENDING parent is PENDING, always, and that is not a shortcut: there is no
 * version of the parent to require yet. It is also the honest answer — a service cannot be shipping
 * a library through a frontend that has not shipped the library.
 *
 * <p><b>TWO STATES, and the third is gone with the log.</b> The train had PENDING → ADOPTED →
 * LANDED, where LANDED meant "and the adopting release itself arrived everywhere". That fold only
 * existed because the train was a stored graph of stations that had to be closed; here the whole
 * chain is recomputed on every read, so a repository's own state is exactly what its own evidence
 * says and every hop beyond it is another row in the same answer.
 *
 * <h2>The gitlink hop is provable now, and the ledger is what proves it</h2>
 *
 * <p>A frontend reaches its service through a GITLINK pin, which is a submodule and never a
 * registry coordinate. That used to be the end of the road: the edge was real, the closure had it,
 * and the evidence still had to ride a registry coordinate the embedder's document was never going
 * to name — so the honest answer was PENDING for ever, and it was recorded here as a gap in what is
 * published rather than a defect. <b>It is no longer a gap.</b>
 *
 * <p>A repository's own name IS a coordinate, in the GITLINK ecosystem — the synthetic pair {@code
 * DownstreamResolver.publishedBy} unions in on the pin side — so every requirement here carries it
 * beside whatever the release put into a registry, and the evaluator adds it itself because {@link
 * ReleaseCoordinates} answers about registries and rightly excludes it. The evidence for that
 * coordinate is the ledger and only the ledger: no component row is ever a gitlink, so the SBOM
 * read is skipped for it outright.
 *
 * <p><b>And the pin's version is a COMMIT SHA, so it is resolved rather than compared.</b> An
 * embedder pins a submodule at a commit; nothing ranks two of those. So the sha is looked up among
 * the PARENT repository's own releases ({@code releasesOf}, matched through {@code
 * GitlinkSha.same}, which is abbreviation-tolerant) to get the version that commit belongs to, and
 * THAT is what the comparison sees. <b>A sha the ledger cannot place is not a match</b>, and that
 * is the honest answer rather than a defensive one: the embedder pinned an off-release commit, or a
 * release older than anything recorded here, and neither is evidence of an adoption.
 */
@ApplicationScoped
public class AdoptionEvaluator {

  @Inject MaintenanceStore store;

  @Inject ReleaseCoordinates coordinates;

  @Inject DownstreamResolver downstream;

  /** Whether a downstream repository is carrying the release yet. Two states, and no third. */
  public enum State {
    /** Its own release contains the coordinate at or above the required version. */
    ADOPTED,
    /** It does not, or nothing upstream of it does yet. */
    PENDING
  }

  /**
   * One downstream repository and what became of the release at it.
   *
   * @param repositoryStatus the consumer's CURRENT inventory status, joined live — {@code ABSENT}
   *     beside a PENDING row says the journey is waiting on something the catalog no longer lists
   * @param adoptedVersion the adopter's OWN released version that carries it, null while PENDING
   * @param adoptedAt when that release was published — the publisher's moment off the artifact row,
   *     never a clock reading taken here
   */
  public record Adopter(
      String repository,
      String catalogId,
      String repositoryStatus,
      String archetype,
      int depth,
      List<String> via,
      State state,
      String adoptedVersion,
      Instant adoptedAt) {}

  /**
   * One release, and everywhere it did or did not get to.
   *
   * @param packages what the release put into a registry. Empty is ordinary — a {@code docs}-only
   *     release names no coordinate, nothing pins a daemon, and a release this service never heard
   *     of has no rows — and it means every downstream row below is PENDING, which is true.
   */
  public record Journey(
      String repository,
      String catalogId,
      String version,
      List<Coordinate> packages,
      List<Adopter> adopters) {}

  /**
   * The journey of one released {@code (repository, version)}.
   *
   * <p><b>There is no "no such release" here and that is deliberate.</b> The train answered 404 for
   * a release with no station, which was a fact about the log rather than about the release. An ad
   * hoc answer always exists: an unknown release publishes no coordinate anybody could be carrying,
   * so its closure is real and every row of it is PENDING.
   *
   * @param spelling the releasing repository by catalog name or by catalog id
   */
  public Journey of(String spelling, String version) {
    Closure closure = downstream.of(spelling);
    String root = closure.repository();
    Map<Coordinate, String> released = coordinates.versionsOf(root, version);

    Names names = names();
    // THE REQUIREMENT AT EACH HOP, keyed by the repository that carries it: what a child of that
    // repository has to be shipping. The root's is the release being asked about; a downstream's is
    // its own adopting release, filled in below as the walk decides it.
    Map<String, Requirement> requirements = new LinkedHashMap<>();
    requirements.put(root, new Requirement(addressedBy(root, released, version), version));

    List<Adopter> adopters = new ArrayList<>();
    // Depth ascending already, and `via` only ever names the level above — see DownstreamResolver —
    // so every parent of an entry has been decided by the time the entry is reached.
    for (Downstream entry : closure.downstream()) {
      Match match = adopted(entry.repository(), entry.via(), requirements, names);
      MtRepository row = names.row(entry.repository());
      if (match == null) {
        adopters.add(
            new Adopter(
                entry.repository(),
                entry.catalogId(),
                row == null ? null : row.status,
                entry.archetype(),
                entry.depth(),
                entry.via(),
                State.PENDING,
                null,
                null));
        continue;
      }
      // AND THIS IS WHAT THE NEXT HOP HAS TO CARRY. The chain is one requirement per link, and each
      // link's version is the adopter's own — which is the only version anything downstream of it
      // could possibly be shipping.
      requirements.put(
          entry.repository(),
          new Requirement(
              addressedBy(
                  entry.repository(),
                  coordinates.versionsOf(entry.repository(), match.version()),
                  match.version()),
              match.version()));
      adopters.add(
          new Adopter(
              entry.repository(),
              entry.catalogId(),
              row == null ? null : row.status,
              entry.archetype(),
              entry.depth(),
              entry.via(),
              State.ADOPTED,
              match.version(),
              match.occurredAt()));
    }

    return new Journey(
        root,
        closure.catalogId(),
        version,
        List.copyOf(released.keySet()),
        List.copyOf(adopters));
  }

  // --- one repository's verdict -----------------------------------------------------------------

  /**
   * What a child of one repository has to be carrying: each coordinate at its own version.
   *
   * <p>The coordinates are what the release carries in a registry PLUS the repository's own name in
   * the GITLINK ecosystem — see {@link #addressedBy}. Both halves are needed at every hop, because
   * a service can consume the same upstream twice: a maven pin on the library and a submodule of
   * the frontend that carries it.
   *
   * <p><b>Each coordinate has its own version</b> (publish-if-changed): the release's own version
   * for what it published and for the gitlink, the "unchanged since" version for what it left
   * alone. See {@link ReleaseCoordinates#versionsOf}.
   *
   * @param coordinates every coordinate, with the version a child has to carry at least
   * @param version the release's own version, null for a PENDING parent
   */
  private record Requirement(Map<Coordinate, String> coordinates, String version) {}

  /** The adopting release, as it is reported: the ADOPTER's own version and its moment. */
  private record Match(String version, Instant occurredAt) {}

  /**
   * The earliest release of this repository that carries any of its parents' requirements.
   *
   * <p><b>Every parent is tried and the earliest match across all of them wins.</b> Two upstreams
   * can both lead here — a service that pins a library directly AND submodules a frontend carrying
   * it — and the question "when did this repository start shipping it" has one answer, not one per
   * path.
   *
   * <p>Null when nothing matches, which covers every honest kind of "not yet": a parent that is
   * itself PENDING contributes no requirement at all, and a repository that has released nothing
   * this service has heard of has neither a document nor a ledger row to be found in. Note what is
   * NOT on that list any more — a repository whose documents were never ingested is no longer
   * silently unprovable, because its released tree speaks for it.
   */
  private Match adopted(
      String repository, List<String> via, Map<String, Requirement> requirements, Names names) {
    Match earliest = null;
    for (String parent : via) {
      Requirement requirement = requirements.get(parent);
      if (requirement == null || requirement.version() == null) {
        // A PENDING parent. There is no version of it to require, so this path says nothing — and if
        // every path says nothing, this repository is PENDING too.
        continue;
      }
      for (Map.Entry<Coordinate, String> coordinate : requirement.coordinates().entrySet()) {
        earliest =
            earlier(
                earliest, carrying(repository, coordinate.getKey(), coordinate.getValue(), names));
      }
    }
    return earliest;
  }

  /**
   * Every coordinate a consumer could be addressing this repository by: what its release put into a
   * registry, and <b>its own NAME in the GITLINK ecosystem</b>.
   *
   * <p>The second is synthetic and it is the mirror of {@code DownstreamResolver.publishedBy}'s —
   * the closure walks the edge, so the evidence has to be able to. It is added HERE rather than in
   * {@link ReleaseCoordinates} because that class answers "what did this release put into a
   * registry", which a submodule is not and never will be; it excludes GITLINK on purpose and keeps
   * doing so.
   */
  private static Map<Coordinate, String> addressedBy(
      String repository, Map<Coordinate, String> published, String version) {
    Map<Coordinate, String> addressed = new LinkedHashMap<>(published);
    if (repository != null && !repository.isBlank()) {
      // A gitlink names the release itself: its commit resolves to the release's own version.
      addressed.put(new Coordinate(Ecosystem.GITLINK, repository), version);
    }
    return addressed;
  }

  /**
   * The earliest release of this repository that is evidence of carrying the coordinate, by
   * whichever evidence kind can speak about it.
   *
   * <p><b>The split is the coordinate's ecosystem, and it is not an optimisation.</b> A GITLINK
   * coordinate skips the SBOM read entirely because no {@code mt_artifact_component} row is ever a
   * gitlink — V3's table holds the three registry ecosystems and says so — so that read could only
   * ever answer nothing. A registry coordinate takes the UNION of both kinds, because a release can
   * be proved by either and the two see different things: the document sees the transitive nobody
   * declared, and the released tree sees the direct dependency nobody's document lists.
   */
  private Match carrying(
      String repository, Coordinate coordinate, String requiredVersion, Names names) {
    if (coordinate.ecosystem() == Ecosystem.GITLINK) {
      return embedding(repository, coordinate.name(), requiredVersion);
    }
    return earlier(
        containing(repository, coordinate, requiredVersion, names),
        declaring(repository, coordinate, requiredVersion));
  }

  /**
   * <b>The SBOM evidence.</b> The earliest ingested release of this repository whose document names
   * the coordinate at or above the required version.
   *
   * <p><b>One {@code dependents(…, false)} read per coordinate — the ARCHAEOLOGY view, not the
   * default one.</b> The default answers the newest release of each dependent, which is precisely
   * the wrong row here: "when did they take it" is a question about the FIRST release that carried
   * it, and the newest one buries that. It is also the cost ceiling of this whole answer — one
   * indexed read per coordinate per hop — which is fine at the size of this estate and is the thing
   * to look at first if it ever is not.
   */
  private Match containing(
      String repository, Coordinate coordinate, String requiredVersion, Names names) {
    Match earliest = null;
    for (MaintenanceStore.Dependent dependent :
        store.dependents(coordinate.ecosystem(), coordinate.name(), false)) {
      MtArtifact artifact = dependent.artifact();
      if (artifact.occurredAt == null || SbomStatus.of(artifact.sbomStatus) != SbomStatus.INGESTED) {
        // A row that is PENDING, MISSING or FAILED holds no components, so it is evidence of
        // nothing — including of a NON-adoption.
        continue;
      }
      // mt_artifact.repository holds whatever the release announced — qits-projects' row id on
      // everything written before the listener learned to resolve it (V5) — so it is translated.
      if (!repository.equals(names.of(artifact.repository))) {
        continue;
      }
      if (!atLeast(coordinate.ecosystem(), dependent.component().version, requiredVersion)) {
        continue;
      }
      earliest = earlier(earliest, new Match(artifact.version, artifact.occurredAt));
    }
    return earliest;
  }

  /**
   * <b>The release-pin evidence, for a registry coordinate.</b> The earliest release of this
   * repository whose own tree declared the coordinate at or above the required version.
   *
   * <p>The comparison is the same one the SBOM half makes, over the same order, and the version
   * REPORTED is the same thing too — the adopter's own released version, off the release row rather
   * than off the artifact row.
   */
  private Match declaring(String repository, Coordinate coordinate, String requiredVersion) {
    Match earliest = null;
    for (MaintenanceStore.ReleaseCarrier carrier :
        store.releasePinCarriers(coordinate.ecosystem(), coordinate.name())) {
      if (!ours(repository, carrier)) {
        continue;
      }
      if (!atLeast(coordinate.ecosystem(), carrier.pin().version, requiredVersion)) {
        continue;
      }
      earliest = earlier(earliest, new Match(carrier.release().version, carrier.release().occurredAt));
    }
    return earliest;
  }

  /**
   * <b>The release-pin evidence, for the GITLINK coordinate — the frontend→service hop.</b>
   *
   * <p>The pin's version is a COMMIT SHA, which no order ranks, so it is RESOLVED before it is
   * compared: the sha is looked up among the submodule repository's own releases and answers the
   * version that commit belongs to, and that version is what the inclusive comparison sees.
   * {@code GitlinkSha.same} does the matching and is abbreviation-tolerant, because a pin read out
   * of a tree is whatever git recorded while everything written here is a full object name.
   *
   * <p><b>A sha the ledger cannot place is no match.</b> The embedder pinned an off-release commit,
   * or a release older than anything this ledger knows, and neither is evidence of an adoption. One
   * {@code releasesOf} read for the whole hop, ahead of the loop — the parent is the same
   * repository for every carrier.
   */
  private Match embedding(String repository, String parent, String requiredVersion) {
    List<MtRelease> parentReleases = store.releasesOf(parent);
    if (parentReleases.isEmpty()) {
      return null;
    }
    Match earliest = null;
    for (MaintenanceStore.ReleaseCarrier carrier :
        store.releasePinCarriers(Ecosystem.GITLINK, parent)) {
      if (!ours(repository, carrier)) {
        continue;
      }
      String embedded = versionAt(parentReleases, carrier.pin().version);
      if (!atLeast(Ecosystem.GITLINK, embedded, requiredVersion)) {
        continue;
      }
      earliest = earlier(earliest, new Match(carrier.release().version, carrier.release().occurredAt));
    }
    return earliest;
  }

  /**
   * Whether this release is the one this repository made.
   *
   * <p><b>A direct string comparison, and that is a property of the column rather than an
   * assumption.</b> {@code mt_release.repository} is the CATALOG NAME under both writers — the
   * listener writes {@code SCMRelease.repositoryName} and the backfill writes a gitlink {@code
   * mt_latest.name}, which is a repository name by construction — so it needs none of the id↔name
   * translation {@code mt_artifact.repository} does above.
   */
  private static boolean ours(String repository, MaintenanceStore.ReleaseCarrier carrier) {
    return repository != null
        && carrier.release().occurredAt != null
        && repository.equals(carrier.release().repository);
  }

  /** Which release of the submodule repository this commit is, or null when it is none of them. */
  private static String versionAt(List<MtRelease> releases, String sha) {
    if (sha == null || sha.isBlank()) {
      return null;
    }
    for (MtRelease release : releases) {
      if (GitlinkSha.same(release.sha, sha)) {
        return release.version;
      }
    }
    return null;
  }

  /**
   * The earlier of two matches, either of which may be absent.
   *
   * <p><b>The earliest wins across every evidence kind and every parent</b>, which is one rule
   * applied at three levels: two documents of one repository, a document against a released tree,
   * and two upstreams that both lead here. "When did this repository start shipping it" has one
   * answer, not one per path and not one per kind of proof.
   */
  private static Match earlier(Match left, Match right) {
    if (right == null) {
      return left;
    }
    if (left == null) {
      return right;
    }
    return right.occurredAt().isBefore(left.occurredAt()) ? right : left;
  }

  /**
   * The inclusive comparison, in the ecosystem's own order — {@code VersionOrder.comparator}, the
   * same one the pending rule and the forward-only latest guard make.
   *
   * <p>Anything missing is NOT a match: a component with no version proves nothing, and a
   * requirement with no version is not a requirement.
   */
  private static boolean atLeast(Ecosystem ecosystem, String embedded, String required) {
    if (embedded == null || embedded.isBlank() || required == null || required.isBlank()) {
      return false;
    }
    return VersionOrder.comparator(ecosystem).compare(embedded, required) >= 0;
  }

  /**
   * {@code mt_repository} in the two shapes this answer reads it in, in ONE query.
   *
   * <p><b>Never {@code store.repositoryName} per row.</b> A journey walks the archaeology view of
   * every coordinate at every hop — hundreds of rows — and each of those carries a spelling that has
   * to be resolved. One catalog read of tens of rows answers all of them.
   */
  private Names names() {
    Map<String, String> nameByCatalogId = new LinkedHashMap<>();
    Map<String, MtRepository> byName = new LinkedHashMap<>();
    for (MtRepository row : store.repositories()) {
      if (row.name == null) {
        continue;
      }
      byName.put(row.name, row);
      if (row.catalogId != null && !row.catalogId.isBlank()) {
        nameByCatalogId.put(row.catalogId, row.name);
      }
    }
    return new Names(nameByCatalogId, byName);
  }

  /**
   * The inventory, keyed both ways, with the same total-function stance every other translation in
   * this service takes: an unknown spelling passes through untouched, and an unknown name has no
   * row rather than no answer.
   */
  private record Names(Map<String, String> nameByCatalogId, Map<String, MtRepository> byName) {

    /** A stored {@code mt_artifact.repository}, as a repository is CALLED. */
    String of(String stored) {
      if (stored == null) {
        return null;
      }
      return nameByCatalogId.getOrDefault(stored, stored);
    }

    /** One repository's inventory row, or null when nothing here knows the name. */
    MtRepository row(String name) {
      return name == null ? null : byName.get(name);
    }
  }
}

package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.maintenance.dto.ArtifactDto;
import eu.wohlben.qits.maintenance.dto.DependentDto;
import eu.wohlben.qits.maintenance.dto.DependentsDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDependentsDto;
import eu.wohlben.qits.maintenance.dto.TransitiveDto;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtArtifactComponent;
import eu.wohlben.qits.maintenance.entity.MtArtifactEdge;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The read side of what our releases CONTAIN, beside {@link Inventory}'s read side of what our
 * manifests DECLARE.
 *
 * <p><b>Two facts, one join key, and they never merge.</b> An SBOM says what a released artifact
 * contains; {@code mt_pin} says what a bump edits. They are related by {@code (ecosystem, name)} and
 * by nothing else — an SBOM cannot name a pom property, and a pin cannot see a transitive. Every
 * method here reaches across that join and none of them writes through it.
 *
 * <p><b>The default view is the NEWEST released version of each dependent.</b> A library released
 * fifty times would otherwise answer "who ships me" fifty times over, and forty-nine of those
 * answers are about versions nobody can do anything about any more. {@code all=true} is the
 * archaeology.
 *
 * <p><b>{@code mt_artifact.repository} is not always a name, and {@link RepositoryNames} is the one
 * place that is dealt with.</b> The release event spells a repository as qits-projects' row id, so
 * rows written before {@code SoftwareReleaseListener} learned to resolve it carry a uuid where
 * every read here joins a name. Both directions of the translation live in that one helper.
 *
 * <p><b>Nothing here is stored.</b> Dependent counts, "behind" verdicts and the {@code via} paths
 * are computed on every read, for the reason pending is: they are a join of two halves that move on
 * different schedules, and a stored copy would be stale between the release that moved one and the
 * lookup that moved the other.
 */
@ApplicationScoped
public class ArtifactGraph {

  @Inject MaintenanceStore store;

  // --- who ships a copy of this ----------------------------------------------------------------

  /**
   * Every artifact of ours whose bill of materials names the dependency.
   *
   * @param all every ingested version rather than the newest of each dependent
   */
  public DependentsDto dependents(Ecosystem ecosystem, String name, boolean all) {
    RepositoryNames names = names();
    List<DependentDto> dependents =
        store.dependents(ecosystem, name, !all).stream()
            .map(dependent -> dependent(dependent, names))
            .toList();
    String latest = store.latest(ecosystem, name).map(row -> row.latest).orElse(null);
    return new DependentsDto(ecosystem.wireName(), name, latest, dependents);
  }

  /**
   * The same list for every artifact one repository produced, grouped by which artifact.
   *
   * <p>"Who is affected if I release this" is a question about the repository, and a repository
   * publishing a jar, an npm package and an image out of one reactor is the ordinary shape here.
   */
  public RepositoryDependentsDto repositoryDependents(String repository) {
    RepositoryNames names = names();
    List<RepositoryDependentsDto.ArtifactDependentsDto> artifacts = new ArrayList<>();
    for (MtArtifact artifact :
        newestPerName(store.artifactsOfRepository(names.spellings(repository)), false)) {
      Optional<Ecosystem> ecosystem = Ecosystem.of(artifact.ecosystem);
      if (ecosystem.isEmpty()) {
        continue;
      }
      artifacts.add(
          new RepositoryDependentsDto.ArtifactDependentsDto(
              artifact.ecosystem,
              artifact.name,
              store.latest(ecosystem.get(), artifact.name).map(row -> row.latest).orElse(null),
              store.dependents(ecosystem.get(), artifact.name, true).stream()
                  .map(dependent -> dependent(dependent, names))
                  .toList()));
    }
    return new RepositoryDependentsDto(repository, List.copyOf(artifacts));
  }

  // --- what this platform publishes -------------------------------------------------------------

  /**
   * Every distinct artifact this platform has released, with how far its reach goes and how much of
   * that reach is stale.
   *
   * <p><b>Straightforward reads rather than one clever query.</b> This is a page of tens of rows,
   * not thousands, and the two counts are computed from exactly the same newest-per-dependent view
   * the dependents route serves — so a figure here and a list there can never disagree.
   */
  public List<ArtifactDto> artifacts() {
    RepositoryNames names = names();
    List<ArtifactDto> listing = new ArrayList<>();
    for (MtArtifact artifact : store.newestArtifactPerName()) {
      Optional<Ecosystem> ecosystem = Ecosystem.of(artifact.ecosystem);
      String latest =
          ecosystem
              .flatMap(value -> store.latest(value, artifact.name))
              .map(row -> row.latest)
              .orElse(null);
      int dependentCount = 0;
      int behindCount = 0;
      if (ecosystem.isPresent()) {
        List<MaintenanceStore.Dependent> dependents =
            store.dependents(ecosystem.get(), artifact.name, true);
        dependentCount = dependents.size();
        for (MaintenanceStore.Dependent dependent : dependents) {
          if (behind(ecosystem.get(), dependent.component().version, latest)) {
            behindCount++;
          }
        }
      }
      listing.add(
          new ArtifactDto(
              artifact.ecosystem,
              artifact.name,
              names.of(artifact.repository),
              latest,
              artifact.version,
              artifact.occurredAt,
              artifact.sbomStatus,
              dependentCount,
              behindCount));
    }
    return List.copyOf(listing);
  }

  // --- what one repository's releases contain ---------------------------------------------------

  /**
   * The transitive half of a repository's detail page: what its released artifacts contain that no
   * manifest of theirs names.
   *
   * <p><b>Anything that is also a pin is removed.</b> A component the repository declares is
   * already on the page with a version, a latest and a verdict; repeating it as a transitive would
   * say the opposite of what is true about it.
   *
   * <p><b>Empty is "we do not know", not "there are none".</b> A repository whose artifacts have no
   * stored SBOM — the ordinary state during the rollout — has nothing here, and so does one whose
   * releases genuinely contain nothing.
   */
  public List<TransitiveDto> transitives(String repository, List<MtPin> pins) {
    Set<String> pinned = new HashSet<>();
    for (MtPin pin : pins) {
      pinned.add(PendingChanges.key(pin.ecosystem, pin.name));
    }

    // Keyed so the same transitive reached through two of the repository's own artifacts is one
    // row. The first reading wins, which the ordering below makes deterministic.
    Map<String, TransitiveDto> found = new LinkedHashMap<>();
    for (MtArtifact artifact :
        newestPerName(store.artifactsOfRepository(names().spellings(repository)), true)) {
      for (TransitiveDto transitive : transitivesOf(artifact)) {
        if (pinned.contains(PendingChanges.key(transitive.ecosystem(), transitive.name()))) {
          continue;
        }
        found.putIfAbsent(
            transitive.ecosystem() + " " + transitive.name() + " " + transitive.version(),
            transitive);
      }
    }
    List<TransitiveDto> listing = new ArrayList<>(found.values());
    listing.sort(
        Comparator.comparing(TransitiveDto::name, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                TransitiveDto::version, Comparator.nullsLast(Comparator.naturalOrder())));
    return List.copyOf(listing);
  }

  /** One artifact's transitives, each with the direct component whose subtree pulled it in. */
  private List<TransitiveDto> transitivesOf(MtArtifact artifact) {
    List<MtArtifactComponent> components = store.components(artifact.id);
    if (components.isEmpty()) {
      return List.of();
    }
    Map<UUID, MtArtifactComponent> byId = new LinkedHashMap<>();
    for (MtArtifactComponent component : components) {
      byId.put(component.id, component);
    }
    List<MtArtifactEdge> edges = store.edges(artifact.id);
    Map<UUID, List<UUID>> children = new LinkedHashMap<>();
    Set<UUID> rootChildren = new LinkedHashSet<>();
    for (MtArtifactEdge edge : edges) {
      if (edge.parentComponentId == null) {
        rootChildren.add(edge.childComponentId);
      } else {
        children.computeIfAbsent(edge.parentComponentId, key -> new ArrayList<>())
            .add(edge.childComponentId);
      }
    }

    Map<UUID, String> via = viaByComponent(components, children, byId);

    List<TransitiveDto> listing = new ArrayList<>();
    for (MtArtifactComponent component : components) {
      if (component.direct) {
        continue;
      }
      // A component the ROOT itself names is nobody's transitive dependency even when the document
      // did not list it as direct — there is no `via` to report, because nothing pulled it in.
      String path = rootChildren.contains(component.id) ? null : via.get(component.id);
      Optional<Ecosystem> ecosystem = Ecosystem.of(component.ecosystem);
      boolean behind =
          ecosystem
              .map(
                  value ->
                      behind(
                          value,
                          component.version,
                          store.latest(value, component.name).map(row -> row.latest).orElse(null)))
              .orElse(false);
      listing.add(
          new TransitiveDto(component.ecosystem, component.name, component.version, path, behind));
    }
    return listing;
  }

  /**
   * The direct component whose subtree reaches each component.
   *
   * <p><b>The first by name where several do, and a forward walk is what makes that cheap.</b> A
   * graph has many paths to one node and a page needs one; walking down from each direct in name
   * order and claiming whatever is not claimed yet gives the same answer every time, with no
   * ordering left to the database.
   */
  private static Map<UUID, String> viaByComponent(
      List<MtArtifactComponent> components,
      Map<UUID, List<UUID>> children,
      Map<UUID, MtArtifactComponent> byId) {
    List<MtArtifactComponent> directs =
        components.stream()
            .filter(component -> component.direct)
            .sorted(
                Comparator.comparing(
                    component -> component.name, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

    Map<UUID, String> via = new LinkedHashMap<>();
    for (MtArtifactComponent direct : directs) {
      Deque<UUID> queue = new ArrayDeque<>(children.getOrDefault(direct.id, List.of()));
      // Per-direct, so a cycle in somebody's document cannot spin here — and cycles do occur.
      Set<UUID> seen = new HashSet<>();
      seen.add(direct.id);
      while (!queue.isEmpty()) {
        UUID next = queue.poll();
        if (!seen.add(next) || !byId.containsKey(next)) {
          continue;
        }
        via.putIfAbsent(next, direct.name);
        queue.addAll(children.getOrDefault(next, List.of()));
      }
    }
    return via;
  }

  // --- the shared readings ----------------------------------------------------------------------

  /**
   * The newest row per {@code (ecosystem, name)} out of a list already ordered newest-first within
   * each pair.
   *
   * @param ingestedOnly the newest row whose document was actually READ, which is a different row
   *     from the newest release whenever the last one has no SBOM stored
   */
  private static List<MtArtifact> newestPerName(List<MtArtifact> ordered, boolean ingestedOnly) {
    List<MtArtifact> newest = new ArrayList<>();
    Set<String> taken = new LinkedHashSet<>();
    for (MtArtifact row : ordered) {
      if (ingestedOnly && SbomStatus.of(row.sbomStatus) != SbomStatus.INGESTED) {
        continue;
      }
      if (taken.add(row.ecosystem + " " + row.name)) {
        newest.add(row);
      }
    }
    return newest;
  }

  /**
   * Whether an embedded version is older than the newest published one.
   *
   * <p><b>False whenever anything is unknown</b>, which is the same stance the pending rule takes: a
   * lookup that never ran must not read like a green tick OR like an arrow. It is the {@code
   * latestError} column that says "we could not find out", and it is on the dependency, not here.
   */
  private static boolean behind(Ecosystem ecosystem, String embedded, String latest) {
    if (embedded == null || latest == null) {
      return false;
    }
    return VersionOrder.newer(ecosystem, embedded, latest);
  }

  private static DependentDto dependent(
      MaintenanceStore.Dependent dependent, RepositoryNames names) {
    MtArtifact artifact = dependent.artifact();
    return new DependentDto(
        artifact.ecosystem,
        artifact.name,
        artifact.version,
        names.of(artifact.repository),
        dependent.component().version,
        dependent.component().direct,
        artifact.occurredAt,
        artifact.sbomStatus);
  }

  /**
   * <b>WHO PUBLISHES WHAT</b> — every artifact coordinate this platform has released, mapped to the
   * repository that produced it.
   *
   * <p>The inverse of {@link #dependents}: that answers "who ships me" from a coordinate, this
   * answers "whose release is this" for every coordinate at once. It exists for the bump
   * dispatcher, which has a list of pending changes and needs to know which of them are another
   * candidate's release — a per-coordinate lookup would be one query per pending change per tick.
   *
   * <p>The key is {@code (ecosystem, name)} spelled by {@link #producerKey}, which is the same join
   * key {@code mt_pin} and {@code mt_artifact} are related by and the only one they share. The
   * newest row per coordinate decides, and the translation of {@code mt_artifact.repository} is
   * {@link RepositoryNames}' as everywhere else here.
   */
  public Map<String, String> producers() {
    RepositoryNames names = names();
    Map<String, String> byCoordinate = new LinkedHashMap<>();
    for (MtArtifact artifact : store.newestArtifactPerName()) {
      String repository = names.of(artifact.repository);
      if (repository == null || repository.isBlank() || artifact.name == null) {
        continue;
      }
      byCoordinate.putIfAbsent(producerKey(artifact.ecosystem, artifact.name), repository);
    }
    return Map.copyOf(byCoordinate);
  }

  /**
   * <b>WHICH REPOSITORY A DEPLOYED APPLICATION IS BUILT FROM</b> — application name to the catalog
   * names of the repositories whose newest image of that name this platform released (qits-1172).
   *
   * <p>qits-deployments keys its pins by application name, and the ledger by repository: the
   * {@code qits-ci} application is built from {@code qits-ci-service}. No table maps one onto the
   * other, and none should — the mapping is the RELEASE's own assertion, read the way the deployer
   * reads it. qits-deployments derives the application from the docker package a {@code
   * SoftwareRelease} names, taking its LAST path segment ({@code qits/qits-ci} deploys as {@code
   * qits-ci}; its {@code PackageNames.applicationOf}), and the same frame filled the docker rows of
   * {@code mt_artifact} here with that package name and the repository that released it. So this is
   * {@link #producers} over the docker rows, keyed by that last segment.
   *
   * <p><b>A set, not a single name</b>, because the last segment is not unique by construction:
   * {@code qits/build-images/ci-base} and a {@code qits/ci-base} would both answer {@code ci-base}.
   * A caller asking "is this release recorded" accepts any of them.
   *
   * <p><b>What it cannot see, and why that is safe for its one caller.</b> A repository whose
   * deployment spec overrides {@code application:} deploys under a name no package carries, and a
   * repository that has never had an image release announced has no row at all. Both come back
   * unmapped, and {@code Inventory.pins} skips an unmapped application with a WARN rather than
   * refusing — see there.
   */
  public Map<String, Set<String>> applicationProducers() {
    RepositoryNames names = names();
    Map<String, Set<String>> byApplication = new java.util.TreeMap<>();
    for (MtArtifact artifact : store.newestArtifactPerName()) {
      if (Ecosystem.of(artifact.ecosystem).orElse(null) != Ecosystem.DOCKER) {
        continue;
      }
      String application = applicationOf(artifact.name);
      String repository = names.of(artifact.repository);
      if (application == null || repository == null || repository.isBlank()) {
        continue;
      }
      byApplication.computeIfAbsent(application, key -> new java.util.TreeSet<>()).add(repository);
    }
    return byApplication;
  }

  /**
   * The application a docker package deploys as: its last path segment, or null for none. The
   * deployer's rule verbatim (qits-deployments' {@code PackageNames.applicationOf}); a second rule
   * here would be the one that disagreed.
   */
  public static String applicationOf(String packageName) {
    if (packageName == null) {
      return null;
    }
    String trimmed = packageName.strip();
    String segment = trimmed.substring(trimmed.lastIndexOf('/') + 1);
    return segment.isEmpty() ? null : segment;
  }

  /** The {@code (ecosystem, name)} spelling {@link #producers} is keyed by. */
  public static String producerKey(String ecosystem, String name) {
    return ecosystem + " " + name;
  }

  /**
   * <b>WHAT ELSE ONE RELEASE PUBLISHED</b> — the container images each named {@code (repository,
   * version)} release carried, beside whatever registry artifact the caller already knows about.
   *
   * <p>A sibling of {@link #producers}, and the second half of the same question. That one answers
   * "whose release is this coordinate"; this one answers "and what images did that same release
   * put in the registry". Together they turn a maven or npm version somebody's manifest pins into
   * the image tag of the same calver — see {@link CarriedImages}, which is the only caller.
   *
   * <p><b>DOCKER only, and the same release only.</b> A repository publishing a jar and an image out
   * of one reactor stamps both with the release's version, and that co-stamping is the whole of the
   * evidence here: it is what {@code .config/qits/release.yml} declared and what the {@code
   * SoftwareRelease} frame announced, so nothing has to remember a mapping. A version the repository
   * released no image at is simply absent, which is the truth about it.
   *
   * <p>One read per repository asked about, over {@link MaintenanceStore#artifactsOfReleases} —
   * narrow in both terms, because the caller asks about the handful of versions that are pinned
   * right now rather than about anybody's history. The walk itself is {@link #releasedWith}, shared
   * with {@link #daemonsReleasedWith}, which asks the identical question about a daemon binary.
   *
   * @param versionsByRepository catalog repository name to the released versions asked about
   * @return repository to version to the docker artifact names that release published, with an
   *     absent entry wherever there were none
   */
  public Map<String, Map<String, List<String>>> imagesReleasedWith(
      Map<String, Set<String>> versionsByRepository) {
    return releasedWith(
        versionsByRepository,
        artifact -> Ecosystem.of(artifact.ecosystem).orElse(null) == Ecosystem.DOCKER);
  }

  /**
   * <b>THE SAME QUESTION, ONE ARTIFACT TYPE OVER</b> — the daemon binaries each named {@code
   * (repository, version)} release put in the platform's {@code daemons} store.
   *
   * <p>A sibling of {@link #imagesReleasedWith} in every respect, and it exists for the same hole in
   * the keep-set: the qits CLI's version is a pom pin now — qits-ci pins {@code
   * eu.wohlben.qits:qits-platform-access-cli-binary}, whose version IS the store coordinate of the
   * binary the same release published — so a repository pinning that coordinate references a binary
   * it never spells out. See {@link CarriedDaemons}, which is the only caller.
   *
   * <p><b>The filter compares the STORED STRING, and that is the one difference.</b> {@code daemon}
   * is not an {@link Ecosystem} and must not become one — {@code Ecosystem#DAEMON_WIRE_NAME} prices
   * the fifth constant at a parser, a resolver and a bump step, none of which a daemon binary has —
   * so {@code Ecosystem.of} answers empty for these rows and a comparison through it would filter
   * out exactly what is being asked for.
   *
   * @param versionsByRepository catalog repository name to the released versions asked about
   * @return repository to version to the daemon artifact names that release published, with an
   *     absent entry wherever there were none
   */
  public Map<String, Map<String, List<String>>> daemonsReleasedWith(
      Map<String, Set<String>> versionsByRepository) {
    return releasedWith(
        versionsByRepository,
        artifact -> Ecosystem.DAEMON_WIRE_NAME.equalsIgnoreCase(artifact.ecosystem));
  }

  /**
   * The shared read behind both: one narrow query per repository asked about, kept by the caller's
   * own test of what the release carried.
   */
  private Map<String, Map<String, List<String>>> releasedWith(
      Map<String, Set<String>> versionsByRepository, Predicate<MtArtifact> carried) {
    if (versionsByRepository == null || versionsByRepository.isEmpty()) {
      return Map.of();
    }
    RepositoryNames names = names();
    Map<String, Map<String, List<String>>> byRepository = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> asked : versionsByRepository.entrySet()) {
      Map<String, List<String>> byVersion = new LinkedHashMap<>();
      for (MtArtifact artifact :
          store.artifactsOfReleases(names.spellings(asked.getKey()), asked.getValue())) {
        if (artifact.name == null || !carried.test(artifact)) {
          continue;
        }
        byVersion
            .computeIfAbsent(artifact.version, version -> new ArrayList<>())
            .add(artifact.name);
      }
      if (!byVersion.isEmpty()) {
        byRepository.put(asked.getKey(), byVersion);
      }
    }
    return Map.copyOf(byRepository);
  }

  // --- the one translation ----------------------------------------------------------------------

  /**
   * The catalog read every answer on this side starts from: {@code mt_repository}, both ways round.
   *
   * <p><b>ONE READ per answer, never one per row.</b> The catalog is tens of rows, a dependents
   * listing can be hundreds, and every row of it names one of those few repositories.
   *
   * <p><b>A catalog id two rows share answers with the live one</b> (qits-1735). A rename keeps
   * qits-projects' row id, so the ABSENT old row and the new one carry the same {@code catalog_id};
   * a plain put let whichever came last in name order win, and an application's producer became a
   * repository nobody scans. The same preference as {@code MaintenanceStore.repositoryName}: a
   * row that is not {@link RepositoryStatus#retired retired} first, then the lowest name.
   */
  private RepositoryNames names() {
    Map<String, String> byCatalogId = new LinkedHashMap<>();
    Map<String, String> byName = new LinkedHashMap<>();
    List<MtRepository> rows = new ArrayList<>(store.repositories());
    rows.sort(
        Comparator.comparing((MtRepository row) -> RepositoryStatus.retired(row.status))
            .thenComparing(row -> row.name, Comparator.nullsLast(Comparator.naturalOrder())));
    for (MtRepository row : rows) {
      if (row.catalogId != null && !row.catalogId.isBlank() && row.name != null) {
        byCatalogId.putIfAbsent(row.catalogId, row.name);
        byName.put(row.name, row.catalogId);
      }
    }
    return new RepositoryNames(byCatalogId, byName);
  }

  /**
   * <b>THE ONE PLACE {@code mt_artifact.repository} IS TRANSLATED, and it is a read-side belt for
   * rows written before the write side learned to do it.</b>
   *
   * <p>qits-ci's {@code SoftwareRelease} names the repository by qits-projects' ROW ID rather than
   * by the catalog name — measured live 2026-09-02 — so every artifact row written before that fix
   * carries a uuid in a column the whole read side joins on a name.
   * {@code SoftwareReleaseListener} resolves it at the write now; those rows are immutable history
   * and are still there, so both directions are covered here and nowhere else:
   *
   * <ul>
   *   <li>{@link #of} reads a stored value OUT as a name — what {@code DependentDto.repository} and
   *       {@code ArtifactDto.repository} carry, which is the client's link to the repository page;
   *   <li>{@link #spellings} reads a name IN as every string that repository's rows may hold — what
   *       "who depends on this repository" and the detail page's transitives are looked up by,
   *       which answered nothing at all for a repository whose releases predate the fix.
   * </ul>
   *
   * <p><b>An unknown value passes through untouched in both directions.</b> A raw uuid with no
   * catalog row behind it is still what a release said, and a repository the catalog does not carry
   * an id for is looked up by its name alone — which is exactly right, because nothing ever wrote
   * anything else for it.
   */
  private record RepositoryNames(
      Map<String, String> nameByCatalogId, Map<String, String> catalogIdByName) {

    /** A stored {@code mt_artifact.repository}, as a repository is CALLED. */
    String of(String stored) {
      if (stored == null) {
        return null;
      }
      return nameByCatalogId.getOrDefault(stored, stored);
    }

    /** Every string this repository's artifact rows may carry: its name, and its catalog id. */
    List<String> spellings(String repository) {
      if (repository == null) {
        return List.of();
      }
      String catalogId = catalogIdByName.get(repository);
      return catalogId == null ? List.of(repository) : List.of(repository, catalogId);
    }
  }
}

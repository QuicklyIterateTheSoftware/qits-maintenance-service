package eu.wohlben.qits.maintenance.adoption;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.maintenance.adoption.ReleaseCoordinates.Coordinate;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>WHO IS DOWNSTREAM OF A REPOSITORY, TRACED TO THE END — computed on every read, stored
 * nowhere.</b>
 *
 * <p>This replaces the persisted release train, and the reason it replaces it is a modelling
 * mistake rather than a bug: a train wrote its membership down ONCE, at the release, with a single
 * one-hop pass. A library release therefore named the frontend that pins it and never the service
 * that consumes that frontend — the journey "cut off at frontend", every time, and no amount of
 * evaluation afterwards could add a hop the membership never had. Everything needed to answer the
 * question properly is already here: {@code mt_pin} says what a manifest DECLARES and {@code
 * mt_artifact_component} says what a release CONTAINS, and both are refreshed by mechanisms that
 * already run. So the closure is a query, taken to the very end, and there is no table.
 *
 * <h2>Two sides, one union, and the gitlink is what makes the second hop exist</h2>
 *
 * <ul>
 *   <li><b>The DECLARED side</b> — every repository holding an INTERNAL {@code mt_pin} on one of the
 *       coordinates. A pin is a line somebody wrote and somebody can edit, which is what makes an
 *       adoption possible at all.
 *   <li><b>The EVIDENCE side</b> — every repository whose newest released artifact's bill of
 *       materials names one of them ({@code MaintenanceStore.dependents}). An SBOM sees transitives,
 *       which no manifest names.
 * </ul>
 *
 * <p><b>And a repository's own NAME is a coordinate here, in the GITLINK ecosystem.</b> That
 * synthetic pair is the whole frontend→service hop: a frontend is a service's {@code
 * service/src/main/webui} submodule, {@code GitmodulesParser} records the submodule's repository
 * name as the pin's {@code name}, and {@code MaintenanceConfig.kindOf} hardcodes GITLINK→INTERNAL.
 * So "who submodules this repository" is already an indexed answer over {@code (ecosystem, name)},
 * and unioning it in is what stops the closure at a frontend from being the end of the road. The
 * old train excluded GITLINK on purpose — because the WRAPPER gitlink-pins every submodule on the
 * platform — and that exclusion is kept, in the shape it should always have had: the wrapper is
 * dropped by its ARCHETYPE, not by pretending the ecosystem does not exist.
 *
 * <h2>What terminates it</h2>
 *
 * <p>The estate's dependency graph is not guaranteed acyclic and this runs inside a human GET, so
 * there are three belts and each is a different failure: the <b>visited set</b> makes a cycle a
 * finite walk, {@link #MAX_DEPTH} bounds a chain longer than any estate this platform will have, and
 * {@link #MAX_REPOSITORIES} bounds an answer nobody could read anyway. Hitting either bound is a
 * WARN and a truncated answer, never an exception: an announce path on the other side of this route
 * must not break because a graph got strange.
 *
 * <p><b>Pure reads, no HTTP, no writes.</b> Everything here is one pass over {@code repositories()}
 * and {@code allPins()} held in memory, plus one {@code dependents} read per coordinate per level.
 */
@ApplicationScoped
public class DownstreamResolver {

  private static final Logger LOG = Logger.getLogger(DownstreamResolver.class);

  /**
   * How many hops out the closure walks.
   *
   * <p>A library → a component library → a frontend → a service → the wrapper is four, and the
   * wrapper is excluded, so ten is roughly twice the deepest chain this estate can currently
   * produce. It is the belt for a graph that grew a shape nobody expected rather than a tuning knob.
   */
  public static final int MAX_DEPTH = 10;

  /**
   * How many downstream repositories one answer may name.
   *
   * <p>The whole catalog is tens of rows, so this can only be reached by a bug — and the honest
   * failure for a bug is a truncated list with a WARN, not a request that never returns.
   */
  public static final int MAX_REPOSITORIES = 500;

  @Inject MaintenanceStore store;

  @Inject ReleaseCoordinates coordinates;

  /**
   * One repository downstream of the root.
   *
   * @param repository the CATALOG NAME, always — the caller's spelling is resolved before anything
   *     is walked
   * @param catalogId qits-projects' row id for it, or null when this inventory has no row (an
   *     unscanned repository, or a gitlink whose submodule name is not a catalog name)
   * @param archetype what the catalog says it IS, verbatim and unvalidated
   * @param depth hops from the root — 1 is a direct consumer
   * @param via the repositories at {@code depth - 1} this one was reached through, name-ascending.
   *     Several, when two upstreams both lead here.
   */
  public record Downstream(
      String repository, String catalogId, String archetype, int depth, List<String> via) {}

  /**
   * The whole answer: the root as this inventory keys it, and everything downstream of it.
   *
   * <p>Ordered <b>depth ascending, then name ascending</b> — the order a consumer wants, because
   * "upstream first" is exactly what a build queue has to know. An unknown root answers an EMPTY
   * list rather than a refusal: qits-projects asks this on its announce path, and a repository this
   * inventory has never scanned must cost that announce nothing.
   */
  public record Closure(String repository, String catalogId, List<Downstream> downstream) {}

  /**
   * Everything downstream of one repository, addressed by catalog name or by catalog id.
   *
   * @param spelling the repository, as the caller happens to hold it — qits-projects addresses it by
   *     its own repository row id, which IS this inventory's {@code catalog_id}
   */
  public Closure of(String spelling) {
    Catalog catalog = catalog();
    String root = catalog.name(spelling);
    if (root == null || root.isBlank()) {
      return new Closure(spelling, null, List.of());
    }

    Map<Coordinate, Set<String>> consumersByCoordinate = pinIndex();

    // The root is visited before anything else: a repository does not adopt its own release — its
    // pin on its own artifact is REACTOR and its bill of materials names itself.
    Set<String> visited = new LinkedHashSet<>();
    visited.add(root);

    Map<String, Downstream> found = new LinkedHashMap<>();
    List<String> frontier = List.of(root);
    boolean truncated = false;

    for (int depth = 1; depth <= MAX_DEPTH && !frontier.isEmpty(); depth++) {
      // ONE LEVEL AT A TIME, so `via` can only ever name a repository one hop nearer the root. That
      // is what keeps the parent edges acyclic — which is what lets AdoptionEvaluator walk them in
      // depth order and be sure a parent was decided before its children.
      Map<String, Set<String>> candidates = new LinkedHashMap<>();
      for (String upstream : frontier) {
        for (String consumer : consumersOf(upstream, consumersByCoordinate, catalog)) {
          if (consumer == null || consumer.isBlank() || visited.contains(consumer)) {
            continue;
          }
          if (catalog.wrapper(consumer)) {
            // THE WRAPPER, AND IT IS ONE DOCUMENTED FILTER RATHER THAN A RULE ABOUT GITLINKS. The
            // home repository gitlink-pins every submodule on this platform, so including it would
            // put it on every answer this route ever gives — and it is the estate rather than a
            // member of it. It is not expanded either: everything it "consumes" is everything.
            continue;
          }
          candidates.computeIfAbsent(consumer, key -> new LinkedHashSet<>()).add(upstream);
        }
      }

      List<String> next = new ArrayList<>();
      for (Map.Entry<String, Set<String>> candidate : candidates.entrySet()) {
        if (found.size() >= MAX_REPOSITORIES) {
          truncated = true;
          break;
        }
        String consumer = candidate.getKey();
        visited.add(consumer);
        MtRepository row = catalog.row(consumer);
        List<String> via = new ArrayList<>(candidate.getValue());
        via.sort(Comparator.naturalOrder());
        found.put(
            consumer,
            new Downstream(
                consumer,
                row == null ? null : row.catalogId,
                row == null ? null : row.archetype,
                depth,
                List.copyOf(via)));
        next.add(consumer);
      }
      if (truncated) {
        break;
      }
      if (depth == MAX_DEPTH && !next.isEmpty()) {
        LOG.warnf(
            "The downstream closure of %s reached the depth bound of %d and was stopped; the answer"
                + " names %d repositories and may be missing deeper ones.",
            root, MAX_DEPTH, found.size());
      }
      frontier = List.copyOf(next);
    }
    if (truncated) {
      LOG.warnf(
          "The downstream closure of %s reached the bound of %d repositories and was truncated.",
          root, MAX_REPOSITORIES);
    }

    List<Downstream> ordered = new ArrayList<>(found.values());
    ordered.sort(
        Comparator.comparingInt(Downstream::depth).thenComparing(Downstream::repository));
    MtRepository rootRow = catalog.row(root);
    return new Closure(
        root, rootRow == null ? null : rootRow.catalogId, List.copyOf(ordered));
  }

  // --- one hop ------------------------------------------------------------------------------

  /**
   * Who consumes what this repository publishes, by both readings of "consumes".
   *
   * <p>The coordinates are every {@code (ecosystem, name)} this repository has EVER released — not
   * one version's — because the closure is a question about the repository rather than about a
   * release: a service pinning last year's version of a library is still downstream of it.
   */
  private Set<String> consumersOf(
      String repository, Map<Coordinate, Set<String>> consumersByCoordinate, Catalog catalog) {
    Set<String> consumers = new LinkedHashSet<>();
    for (Coordinate coordinate : publishedBy(repository)) {
      consumers.addAll(consumersByCoordinate.getOrDefault(coordinate, Set.of()));
      if (coordinate.ecosystem() == Ecosystem.GITLINK) {
        // Nothing releases a gitlink into a registry, so there is no evidence side for it — the
        // submodule pin IS the whole edge. See the class comment.
        continue;
      }
      for (MaintenanceStore.Dependent dependent :
          store.dependents(coordinate.ecosystem(), coordinate.name(), true)) {
        // mt_artifact.repository may still hold another context's spelling on rows written before
        // the listener learned to resolve it; the catalog reads it back as the name this joins on.
        consumers.add(catalog.name(dependent.artifact().repository));
      }
    }
    return consumers;
  }

  /**
   * Every coordinate this repository is addressed by: what its releases put into a registry, and
   * <b>its own name in the GITLINK ecosystem</b>.
   *
   * <p>The second is synthetic and it is the point — see the class comment. It costs nothing when
   * nobody submodules the repository, because the pin index simply has no entry for it.
   */
  private Set<Coordinate> publishedBy(String repository) {
    Set<Coordinate> published = new LinkedHashSet<>();
    published.add(new Coordinate(Ecosystem.GITLINK, repository));
    for (MtArtifact artifact : store.artifactsOfRepository(coordinates.spellings(repository))) {
      Ecosystem.of(artifact.ecosystem)
          .filter(ecosystem -> ecosystem != Ecosystem.GITLINK)
          .ifPresent(ecosystem -> published.add(new Coordinate(ecosystem, artifact.name)));
    }
    return published;
  }

  // --- the two setup reads --------------------------------------------------------------------

  /**
   * <b>EVERY INTERNAL PIN ON THE PLATFORM, ONCE, AS AN IN-MEMORY INDEX.</b>
   *
   * <p>The alternative — the one the release trains took — is a per-coordinate indexed read, which
   * is dozens of round trips per answer once the walk goes more than one hop. One {@code allPins()}
   * is a table of a few thousand rows, and every level of every walk is then a map lookup.
   *
   * <p><b>INTERNAL only, and the kind filter is the meaning rather than an optimisation.</b>
   * EXTERNAL is somebody else's package and nothing of ours releasing it makes anyone downstream;
   * REACTOR and UNRESOLVED have no line to edit at all.
   */
  private Map<Coordinate, Set<String>> pinIndex() {
    Map<Coordinate, Set<String>> index = new LinkedHashMap<>();
    for (MtPin pin : store.allPins()) {
      if (!PinKind.INTERNAL.name().equals(pin.kind)) {
        continue;
      }
      Ecosystem.of(pin.ecosystem)
          .ifPresent(
              ecosystem ->
                  index
                      .computeIfAbsent(
                          new Coordinate(ecosystem, pin.name), key -> new LinkedHashSet<>())
                      .add(pin.repository));
    }
    return index;
  }

  /** {@code mt_repository} in the three shapes a walk reads it in, in ONE query. */
  private Catalog catalog() {
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
    return new Catalog(nameByCatalogId, byName);
  }

  /**
   * The inventory as a walk reads it.
   *
   * <p><b>An unknown spelling passes through untouched</b>, the stance every translation in this
   * service takes: a repository this inventory has never scanned still released something, and a
   * gitlink whose submodule name is not a catalog name is still a real edge. Such an entry answers
   * with a null {@code catalogId} and a null archetype rather than being dropped — the alias column
   * that would fix the mismatch properly is a later decision.
   */
  private record Catalog(Map<String, String> nameByCatalogId, Map<String, MtRepository> byName) {

    /** A caller's or a row's spelling of a repository, as this inventory keys one. */
    String name(String spelling) {
      if (spelling == null) {
        return null;
      }
      return nameByCatalogId.getOrDefault(spelling, spelling);
    }

    /** One repository's inventory row, or null when nothing here knows the name. */
    MtRepository row(String name) {
      return name == null ? null : byName.get(name);
    }

    /** Whether this is the home repository — the estate rather than a member of it. */
    boolean wrapper(String name) {
      MtRepository row = row(name);
      return row != null
          && RepositoryArchetype.of(row.archetype)
              .filter(archetype -> archetype == RepositoryArchetype.PROJECT)
              .isPresent();
    }
  }
}

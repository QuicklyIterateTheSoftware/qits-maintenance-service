package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.bootstrap.graph.Coordinate;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.Pin;
import eu.wohlben.qits.bootstrap.graph.ProjectSource;
import eu.wohlben.qits.bootstrap.graph.manifest.Deployment;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ONE TICK'S OWED SET AS A PROJECT (qits-1880): the candidates are the nodes, each candidate's
 * pending changes are its pins, and the released-artifact ledger says who produces what — so the
 * library's {@code ProjectGraph} draws exactly the edges {@link BumpOrder#owedUpstreams} reads off
 * the changes.
 *
 * <ul>
 *   <li>{@link #repositories()} — the candidates' names in the caller's listing order, which is the
 *       order every tie is broken by (least-recently-bumped first; see {@link BumpOrder}).
 *   <li>{@link #consumes} — each change as a {@link Pin} of {@code (ecosystem, name)}, its {@code to}
 *       as the version. Only the candidates are nodes, so a coordinate somebody not owed publishes
 *       makes no edge, as it never blocked anything.
 *   <li>{@link #produces} — the ledger ({@link ArtifactGraph#producers()}, keyed by {@link
 *       ArtifactGraph#producerKey}) inverted. A submodule needs no row: the graph implies every
 *       node's own GITLINK coordinate, which is {@code BumpOrder}'s match-by-name.
 *   <li>{@link #deployment} — empty: bump order is ARTIFACT edges only.
 * </ul>
 *
 * <p><b>Spelled so the edges are {@code producerOf}'s to the letter.</b> {@code producerOf} looks a
 * change up by the exact key string, so a change or a ledger key whose ecosystem is not exactly a
 * wire name — {@code daemon}, an upper-case spelling {@link Ecosystem#of} would otherwise forgive —
 * is skipped here: no pin, no coordinate. Pending changes are read off pins, whose ecosystem is
 * always a wire name, so that is a rule about hand-built input rather than a tick's. And a change the
 * ledger names a producer for that is NOT a candidate is skipped too: {@code producerOf} answers
 * that producer and nothing else, so the implied gitlink of a same-named candidate must not stand in
 * for it.
 */
public final class CandidateSource implements ProjectSource {

  private final List<String> repositories = new ArrayList<>();
  private final Map<String, List<Pin>> consumes = new LinkedHashMap<>();
  private final Map<String, Set<Coordinate>> produces = new LinkedHashMap<>();

  /**
   * @param candidates the tick's candidates, held ones included, each repository once
   * @param producers {@link ArtifactGraph#producers()} — {@code "<ecosystem> <name>"} to the
   *     publishing repository
   */
  public CandidateSource(List<BumpOrder.Candidate> candidates, Map<String, String> producers) {
    Set<String> owed = new LinkedHashSet<>();
    for (BumpOrder.Candidate candidate : candidates) {
      owed.add(candidate.repository());
    }
    for (BumpOrder.Candidate candidate : candidates) {
      repositories.add(candidate.repository());
      List<Pin> pins = consumes.computeIfAbsent(candidate.repository(), key -> new ArrayList<>());
      for (Change change : candidate.changes()) {
        if (change == null || change.name() == null) {
          continue;
        }
        Optional<Ecosystem> ecosystem = wireName(change.ecosystem());
        if (ecosystem.isEmpty()) {
          continue;
        }
        String published = producers.get(ArtifactGraph.producerKey(change.ecosystem(), change.name()));
        if (published != null && !owed.contains(published)) {
          continue;
        }
        pins.add(
            new Pin(
                new Coordinate(ecosystem.get(), change.name()),
                change.to(),
                change.manifestPath(),
                change.location()));
      }
    }
    for (Map.Entry<String, String> entry : producers.entrySet()) {
      String key = entry.getKey();
      String repository = entry.getValue();
      int space = key == null ? -1 : key.indexOf(' ');
      if (space < 0 || repository == null || !owed.contains(repository)) {
        continue;
      }
      Optional<Ecosystem> ecosystem = wireName(key.substring(0, space));
      if (ecosystem.isEmpty()) {
        continue;
      }
      produces
          .computeIfAbsent(repository, name -> new LinkedHashSet<>())
          .add(new Coordinate(ecosystem.get(), key.substring(space + 1)));
    }
  }

  /** The ecosystem whose wire name is exactly {@code spelling}, or empty. */
  private static Optional<Ecosystem> wireName(String spelling) {
    return Ecosystem.of(spelling).filter(value -> value.wireName().equals(spelling));
  }

  @Override
  public List<String> repositories() {
    return List.copyOf(repositories);
  }

  @Override
  public List<Pin> consumes(String repository) {
    return List.copyOf(consumes.getOrDefault(repository, List.of()));
  }

  @Override
  public Set<Coordinate> produces(String repository) {
    return Set.copyOf(produces.getOrDefault(repository, Set.of()));
  }

  @Override
  public Optional<Deployment> deployment(String repository) {
    return Optional.empty();
  }
}

package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.maintenance.dto.PinSourceDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * <b>THE ONE RULE BOTH DERIVATIONS ARE:</b> for a stored pin at {@code (ecosystem, name, version)}
 * that is internal maven or npm, the repository that RELEASED that coordinate, and everything of a
 * given kind that same repository released at that same version.
 *
 * <p><b>Why this is one algorithm and not two copies.</b> {@link CarriedImages} wrote it for
 * container images; {@link CarriedDaemons} needed it verbatim for daemon binaries, differing in two
 * tokens — which artifact rows count, and what the derived row's {@code ecosystem} says. The parts
 * that are easy to get subtly wrong are all in the shared half: the de-duplication by (carried name,
 * version, PINNING repository, manifest path) so one release reached through two carriers is one
 * row; the {@code via} that keeps a derived row from ever being read as a line somebody wrote; and
 * the rule that a manifest which already writes the coordinate out wins, because its row is the one
 * a bump can edit. Two copies of those would be two places to fix the day one of them is wrong.
 *
 * <p>What is NOT shared is the reasoning, and that is deliberate: each caller's javadoc says which
 * hole in the keep-set it closes and what was measured, because those are different facts about
 * different stores with different retention.
 *
 * <p><b>No artifact row, no keep.</b> A version this service never saw released resolves to nothing
 * at all, rather than to a name invented for it — the same honesty either caller would want on its
 * own. The window that matters is the freshly released version, whose row {@code
 * SoftwareReleaseListener} writes off the frame.
 */
final class CarriedArtifacts {

  private CarriedArtifacts() {}

  /**
   * The derived rows to serve BESIDE the stored pins.
   *
   * @param pins the stored pin rows, already filtered to INTERNAL and to a known ecosystem — which
   *     is what makes the producer lookup meaningful: an external coordinate is somebody else's
   *     release and carries nothing of ours
   * @param graph the read side of what our releases contain, for {@link ArtifactGraph#producers}
   * @param carriedEcosystem what the derived rows are served AS — a wire spelling the consumer
   *     matches exactly, so it is a contract rather than a label
   * @param releasedWith the caller's half: what each named release carried, keyed repository to
   *     version to artifact name
   * @return the derived rows, in no particular order; the caller sorts them into the one total order
   *     the answer is served in
   */
  static List<PinSourceDto.ArtifactPinDto> resolve(
      List<PinSourceDto.ArtifactPinDto> pins,
      ArtifactGraph graph,
      String carriedEcosystem,
      Function<Map<String, Set<String>>, Map<String, Map<String, List<String>>>> releasedWith) {
    if (pins == null || pins.isEmpty()) {
      return List.of();
    }
    // Which repository released each coordinate anybody pins, read once. A carrier that names no
    // release of ours — a coordinate this platform has never published — falls out here.
    Map<String, String> producers = graph.producers();
    Map<String, Set<String>> versionsByRepository = new LinkedHashMap<>();
    for (PinSourceDto.ArtifactPinDto pin : pins) {
      String repository = carrierOf(producers, pin);
      if (repository != null) {
        versionsByRepository
            .computeIfAbsent(repository, name -> new LinkedHashSet<>())
            .add(pin.version());
      }
    }

    Map<String, Map<String, List<String>>> carried = releasedWith.apply(versionsByRepository);
    if (carried.isEmpty()) {
      return List.of();
    }

    // Keyed so one artifact reached through two carriers of the same release — a pom pinning both
    // halves of one repository's reactor — is one row rather than two saying the same thing.
    Map<String, PinSourceDto.ArtifactPinDto> derived = new LinkedHashMap<>();
    for (PinSourceDto.ArtifactPinDto pin : pins) {
      String repository = carrierOf(producers, pin);
      if (repository == null) {
        continue;
      }
      for (String name :
          carried.getOrDefault(repository, Map.of()).getOrDefault(pin.version(), List.of())) {
        derived.putIfAbsent(
            key(name, pin.version(), pin.repository(), pin.manifestPath()),
            new PinSourceDto.ArtifactPinDto(
                carriedEcosystem,
                name,
                pin.version(),
                // The PINNING repository and the manifest that holds the line, never the one that
                // released the artifact: the consumer's question the moment it decides not to delete
                // something is "who still holds this", and the answer is the pom with the property
                // in it. Where the NAME came from is `via`.
                pin.repository(),
                pin.manifestPath(),
                pin.ecosystem() + " " + pin.name()));
      }
    }

    // A manifest that also writes the coordinate out — a Dockerfile `FROM` at the same version — has
    // already said this, with a line a bump can edit. The stored row is the better one of the two.
    for (PinSourceDto.ArtifactPinDto pin : pins) {
      derived.remove(key(pin.name(), pin.version(), pin.repository(), pin.manifestPath()));
    }
    return List.copyOf(new ArrayList<>(derived.values()));
  }

  /**
   * The repository whose release published this pin's coordinate, or null when it carries nothing of
   * ours.
   *
   * <p>DOCKER and GITLINK are refused at the door rather than filtered later: an image is its own
   * carrier and resolving one would restate the row it came from, and a gitlink's version is a
   * commit sha, which no release ever stamped an artifact with.
   */
  private static String carrierOf(Map<String, String> producers, PinSourceDto.ArtifactPinDto pin) {
    Ecosystem ecosystem = Ecosystem.of(pin.ecosystem()).orElse(null);
    if (ecosystem != Ecosystem.MAVEN && ecosystem != Ecosystem.NPM) {
      return null;
    }
    String repository = producers.get(ArtifactGraph.producerKey(pin.ecosystem(), pin.name()));
    return repository == null || repository.isBlank() ? null : repository;
  }

  private static String key(String name, String version, String repository, String manifestPath) {
    return name + ":" + version + " " + repository + " " + manifestPath;
  }
}

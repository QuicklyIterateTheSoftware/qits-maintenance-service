package eu.wohlben.qits.maintenance.adoption;

import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>WHAT ONE RELEASE PUT INTO A REGISTRY</b> — the coordinates both halves of an adoption question
 * join on.
 *
 * <p>A release is a {@code (repository, version)}, and everything asked about one is asked against
 * the {@code (ecosystem, name)} pairs it published: the closure asks who PINS one of them ({@link
 * DownstreamResolver}), and the journey asks whose bill of materials CONTAINS one of them ({@link
 * AdoptionEvaluator}). Both need the same answer out of the same table, so it is read in one place
 * rather than derived twice with two chances of disagreeing.
 *
 * <p><b>GITLINK never appears here.</b> {@code mt_artifact} holds the three registry ecosystems only
 * (V3), and the filter below says so out loud rather than relying on it. The gitlink edge — a
 * frontend that is a service's {@code webui} submodule — is not a released coordinate at all: it is
 * a PIN, and {@link DownstreamResolver} unions it in on the pin side under the repository's own
 * name. Evidence for that edge still rides a registry coordinate; see {@link AdoptionEvaluator}.
 */
@ApplicationScoped
public class ReleaseCoordinates {

  @Inject MaintenanceStore store;

  /** One released package, as both the pin side and the SBOM side name it. */
  public record Coordinate(Ecosystem ecosystem, String name) {}

  /**
   * Every registry coordinate this release carries, as far as anything here knows — the keys of
   * {@link #versionsOf}.
   */
  public Set<Coordinate> of(String repository, String version) {
    return new LinkedHashSet<>(versionsOf(repository, version).keySet());
  }

  /**
   * <b>Every registry coordinate this release carries, each at the version it carries it at.</b>
   *
   * <p><b>Release version is not artifact version</b> (publish-if-changed, 2026-10-09). A release
   * {@code V} publishes only the artifacts whose content changed; an unchanged one stays at the
   * version {@code U} it was last published at ("unchanged since U"), and {@code X@V} does not
   * exist. So each coordinate's version is the newest one this repository published at or before
   * {@code V}, in the ecosystem's own order: {@code V} itself for an artifact the release
   * published, {@code U} for one it left unchanged. A consumer carrying {@code U} or later carries
   * what {@code V} shipped of it. Reading only artifacts at exactly {@code V} dropped the unchanged
   * ones, and their consumers read PENDING for ever.
   *
   * <p><b>Only for a release this service knows</b>: one with an {@code mt_release} row, or one that
   * published something at exactly {@code V}. Any other version names no release, carries nothing,
   * and answers empty, as before.
   *
   * <p>Empty is an ordinary answer rather than a gap: a {@code docs}-only release names no
   * coordinate at all, a {@code daemon} release names nothing any manifest pins, and a release whose
   * {@code mt_artifact} rows have not been written yet is one whose sibling consumer has simply not
   * run. Every caller here treats an empty map as "nothing to match on", which is PENDING rather
   * than a refusal.
   */
  public Map<Coordinate, String> versionsOf(String repository, String version) {
    Map<Coordinate, String> versions = new LinkedHashMap<>();
    if (repository == null || version == null) {
      return versions;
    }
    List<MtArtifact> artifacts = store.artifactsOfRepository(spellings(repository));
    boolean known =
        artifacts.stream().anyMatch(artifact -> version.equals(artifact.version))
            || store.releasesOf(repository).stream()
                .anyMatch(release -> version.equals(release.version));
    if (!known) {
      return versions;
    }
    for (MtArtifact artifact : artifacts) {
      Optional<Ecosystem> ecosystem =
          Ecosystem.of(artifact.ecosystem).filter(found -> found != Ecosystem.GITLINK);
      if (ecosystem.isEmpty() || artifact.version == null || artifact.version.isBlank()) {
        continue;
      }
      Comparator<String> order = VersionOrder.comparator(ecosystem.get());
      if (order.compare(artifact.version, version) > 0) {
        // Published after this release: not what it carried.
        continue;
      }
      versions.merge(
          new Coordinate(ecosystem.get(), artifact.name),
          artifact.version,
          (held, candidate) -> order.compare(candidate, held) > 0 ? candidate : held);
    }
    return versions;
  }

  /**
   * Every string this repository's artifact rows may carry: its catalog name, and the catalog id
   * that rows written before the listener learned to resolve it still hold.
   */
  public List<String> spellings(String repository) {
    Optional<MtRepository> row = store.repository(repository);
    String catalogId = row.map(found -> found.catalogId).orElse(null);
    return catalogId == null || catalogId.isBlank()
        ? List.of(repository)
        : List.of(repository, catalogId);
  }
}

package eu.wohlben.qits.maintenance.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ArtifactGraph}'s one translation of a stored catalog id — and of a stored name a rename
 * retired (qits-1840) — against a real PostgreSQL.
 */
@QuarkusTest
class ArtifactGraphNamesTest {

  @Inject MaintenanceStore store;

  @Inject ArtifactGraph graph;

  /**
   * <b>A rename keeps the catalog id, so two rows carry it</b> (qits-1735). The ABSENT old row is
   * named to sort LAST, which is the row a plain put over the name-ordered catalog let win: the
   * application's producer became a repository nobody scans, and {@code Inventory.pins} asked the
   * ledger heal to record a release under it.
   */
  @Test
  void anImageReleasedUnderASharedCatalogIdIsProducedByTheLiveRow() {
    String catalogId = UUID.randomUUID().toString();
    String suffix = UUID.randomUUID().toString();
    String live = "a-live-" + suffix;
    String renamedAway = "z-renamed-" + suffix;
    String undeclared = "y-undeclared-" + suffix;
    store.markRepository(
        live, "qits", catalogId, null, RepositoryStatus.OK, null, Instant.now());
    store.markRepository(
        renamedAway,
        "qits",
        catalogId,
        null,
        RepositoryStatus.ABSENT,
        "dropped from the catalog",
        Instant.now());
    store.markRepository(
        undeclared,
        "qits",
        catalogId,
        null,
        RepositoryStatus.UNDECLARED,
        "not declared by its project",
        Instant.now());
    String application = "app-" + suffix;
    // qits-ci's SoftwareRelease spells the repository by its catalog id.
    store.upsertArtifact(
        Ecosystem.DOCKER, "qits/" + application, "2026.1029.1", catalogId, Instant.now());

    assertEquals(Set.of(live), graph.applicationProducers().get(application));
  }

  /**
   * <b>An artifact stored under the OLD NAME is produced by the new one</b> (qits-1840). The live
   * case: {@code qits-platform-access-cli} renamed to {@code qits-platform-access-monorepo}, one
   * catalog id, and the binary's rows written under the old name. Those passed through the catalog-id
   * translation untouched, so the bump looked for {@code @changelog/qits-platform-access-cli}.
   */
  @Test
  void anArtifactStoredUnderARetiredNameIsProducedByTheLiveRowOnItsCatalogId() {
    String catalogId = UUID.randomUUID().toString();
    String suffix = UUID.randomUUID().toString();
    String old = "a-old-" + suffix;
    String current = "b-new-" + suffix;
    store.markRepository(
        old, "qits", catalogId, null, RepositoryStatus.ABSENT, "dropped from the catalog",
        Instant.now());
    store.markRepository(current, "qits", catalogId, null, RepositoryStatus.OK, null, Instant.now());
    String coordinate = "eu.wohlben.qits:binary-" + suffix;
    store.upsertArtifact(Ecosystem.MAVEN, coordinate, "2026.1101.1", old, Instant.now());

    assertEquals(
        current, graph.producers().get(ArtifactGraph.producerKey(Ecosystem.MAVEN.wireName(), coordinate)));
    // In, too: a name-keyed read of the live repository finds the history under the old name.
    assertEquals(List.of(current, catalogId, old), graph.spellings(current));
    assertEquals(
        Set.of(current),
        graph.artifacts().stream()
            .filter(artifact -> coordinate.equals(artifact.name()))
            .map(artifact -> artifact.repository())
            .collect(java.util.stream.Collectors.toSet()),
        "and a listing reads the old name out as the live one");
  }

  /**
   * <b>Two LIVE rows on one catalog id: the newer-scanned one produces</b> (qits-1840) — the window
   * between a rename and the scan that retires the old row, in which the lowest name used to win.
   */
  @Test
  void anArtifactUnderACatalogIdTwoLiveRowsShareIsProducedByTheNewerScannedOne() {
    String catalogId = UUID.randomUUID().toString();
    String suffix = UUID.randomUUID().toString();
    String old = "a-old-" + suffix;
    String current = "b-new-" + suffix;
    Instant renamedAt = Instant.parse("2026-11-01T10:00:00Z");
    store.markRepository(old, "qits", catalogId, null, RepositoryStatus.OK, null, renamedAt);
    store.markRepository(
        current, "qits", catalogId, null, RepositoryStatus.OK, null, renamedAt.plusSeconds(60));
    String coordinate = "eu.wohlben.qits:live-" + suffix;
    store.upsertArtifact(Ecosystem.MAVEN, coordinate, "2026.1101.1", catalogId, Instant.now());

    assertEquals(
        current, graph.producers().get(ArtifactGraph.producerKey(Ecosystem.MAVEN.wireName(), coordinate)));
    // A LIVE old name is not a rename yet — nothing retired it — so it is not translated.
    assertEquals(List.of(current, catalogId), graph.spellings(current));
  }
}

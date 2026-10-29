package eu.wohlben.qits.maintenance.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ArtifactGraph}'s one translation of a stored catalog id, against a real PostgreSQL.
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
}

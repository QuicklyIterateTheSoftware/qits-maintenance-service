package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.releaseRequestStateChangedPayload;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.softwareReleasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>FINALIZATION IS THE GREEN LIGHT, NOT THE PUBLISH</b> (qits-1472), through both real listeners
 * and a real database.
 *
 * <p>The reproduction: qits-workspaces 2026.1020.130544 was this service's latest for 71 s before
 * its request passed its deploy gate, and qits-pact-consumer 2026.1019.81701 was adopted although
 * its request had gone OBSOLETE a second before the announcement. Here an announcement alone moves
 * nothing, and either arrival order of the announcement and the finalization adopts.
 *
 * <p>The repository is qits-projects' ROW ID on both frames, as it is live: {@code
 * SoftwareRelease.repository} and {@code ReleaseRequestStateChanged.repoId} are the same uuid, and
 * the join takes both verbatim.
 */
@QuarkusTest
class FinalizedAdoptionTest {

  private static final String REPO_ID = "0afceb63-1c9a-4f8e-9a51-0b0d0e0f1472";

  private static final String OTHER_REPO_ID = "28e5cc48-1c9a-4f8e-9a51-0b0d0e0f1472";

  private static final String JAR = "eu.wohlben.qits:qits-pact-consumer";

  private static final String V1 = "2026.1019.81701";

  private static final String V2 = "2026.1020.130544";

  @Inject SoftwareReleaseListener releases;

  @Inject ReleaseFinalizedListener finalizations;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject WorkQueue queue;

  @Inject InventoryReset reset;

  @BeforeEach
  void anEmptyInventory() {
    queue.awaitIdle(Duration.ofSeconds(30));
    reset.clear();
    peers.reset();
  }

  private void published(String repoId, String name, String version) {
    releases.onFrame(
        frame("SoftwareRelease", softwareReleasePayload(repoId, "maven", name, version)));
    // The SBOM fetch the announcement queues runs on the worker; drain it so the next test's reset
    // does not land between its read and its write.
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  private void stateChanged(String repoId, String state, String version) {
    finalizations.onFrame(
        frame(
            "ReleaseRequestStateChanged",
            releaseRequestStateChangedPayload(repoId, state, version)));
  }

  private Optional<String> latest(String name) {
    return store.latest(Ecosystem.MAVEN, name).map(row -> row.latest);
  }

  @Test
  void aSoftwareReleaseAloneAdoptsNothingButStillWritesTheArtifactRow() {
    published(REPO_ID, JAR, V2);

    assertTrue(latest(JAR).isEmpty(), "published is not usable");
    assertTrue(
        store.artifact(Ecosystem.MAVEN, JAR, V2).isPresent(),
        "the mt_artifact row and its SBOM outbox are written exactly as before");
  }

  @Test
  void aSoftwareReleaseThenItsFinalizationAdopts() {
    published(REPO_ID, JAR, V2);
    stateChanged(REPO_ID, "FINALIZED", V2);

    assertEquals(Optional.of(V2), latest(JAR));
  }

  /** For a library both frames come out of one BuildSuccessful about 200 ms apart, either order. */
  @Test
  void aFinalizationThenItsSoftwareReleaseAdopts() {
    stateChanged(REPO_ID, "FINALIZED", V2);
    published(REPO_ID, JAR, V2);

    assertEquals(Optional.of(V2), latest(JAR));
  }

  /** 66 of 263 FINALIZED frames in one week came twice, under two event ids. */
  @Test
  void aFinalizationDeliveredTwiceIsIdempotent() {
    published(REPO_ID, JAR, V2);
    stateChanged(REPO_ID, "FINALIZED", V2);
    stateChanged(REPO_ID, "FINALIZED", V2);

    assertEquals(Optional.of(V2), latest(JAR));
    assertEquals(List.of(V2), store.finalizedReleases(REPO_ID), "one finalized row");
  }

  @Test
  void aFinalizationOfAnotherRepositoryOrOfALowerVersionAdoptsNothing() {
    published(REPO_ID, JAR, V2);
    stateChanged(OTHER_REPO_ID, "FINALIZED", V2);
    stateChanged(REPO_ID, "FINALIZED", V1);

    assertTrue(latest(JAR).isEmpty());
  }

  /**
   * <b>THE OBSOLETE CASE, and why the rule is "at or below".</b> v1 was published and its request
   * went OBSOLETE; v2's request folded it in, and qits-ci announces an {@code if-changed} artifact
   * only where it changed — so there is no SoftwareRelease of this jar at v2. Finalizing v2 is the
   * verdict on v1 as well, and v1 is adopted.
   */
  @Test
  void anArtifactPublishedAtAnObsoleteVersionIsAdoptedWhenALaterVersionFinalizes() {
    published(REPO_ID, JAR, V1);
    stateChanged(REPO_ID, "OBSOLETE", V1);
    assertTrue(latest(JAR).isEmpty(), "OBSOLETE is not a finalization");

    stateChanged(REPO_ID, "FINALIZED", V2);

    assertEquals(Optional.of(V1), latest(JAR));
  }

  /** Of everything waiting at or below the finalized version, the highest per package is taken. */
  @Test
  void aFinalizationAdoptsTheHighestWaitingVersion() {
    published(REPO_ID, JAR, V1);
    published(REPO_ID, JAR, V2);
    stateChanged(REPO_ID, "FINALIZED", V2);

    assertEquals(Optional.of(V2), latest(JAR));
  }

  @Test
  void aStateOtherThanFinalizedAdoptsNothing() {
    published(REPO_ID, JAR, V2);
    for (String state :
        List.of("PENDING", "READY", "RELEASED", "REJECTED", "FAILED", "CONFLICTED", "WITHDRAWN")) {
      stateChanged(REPO_ID, state, V2);
    }

    assertTrue(latest(JAR).isEmpty());
    assertTrue(store.finalizedReleases(REPO_ID).isEmpty());
  }
}

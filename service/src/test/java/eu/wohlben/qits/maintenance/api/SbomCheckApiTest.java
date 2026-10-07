package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.ReleaseOrigin;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SBOM check's two doors, through the real stack: report-only forced for the whole module by
 * {@code service/src/test/resources/application.properties} (the shipped config now files tickets;
 * {@link SbomCheckServiceTest} covers filing), the real presence probe against a scripted
 * qits-artifacts, the roles.
 */
@QuarkusTest
class SbomCheckApiTest {

  private static final String BASE = "/maintenance/api";
  private static final String NAME = "eu.wohlben.qits:qits-no-sbom";

  @Inject FakePeers peers;

  @Inject MaintenanceStore store;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void reset() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
  }

  private static RequestSpecification as(String role) {
    return given().header("X-Qits-User", "someone").header("X-Qits-Roles", role);
  }

  @Test
  void noRunYetIsAFourOhFourRatherThanACleanReport() {
    as("qits:agent").get(BASE + "/sbom-check").then().statusCode(404);
  }

  /**
   * A run is computed against qits-artifacts' metadata listing, stored, served — and, with the test
   * module's config forcing {@code file-tickets=false}, calls nothing at all in qits-projects.
   */
  @Test
  void aRunReportsTheVersionAndTheShippedConfigFilesNothing() {
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            NAME,
            "2026.1001.1",
            Fixture.REPOSITORY,
            Instant.now().minus(Duration.ofDays(2)),
            new ReleaseOrigin("qits", "artifacts", null));
    store.markArtifactMissing(id);
    peers.answer(
        PeerTarget.MAVEN_REGISTRY,
        "/eu/wohlben/qits/qits-no-sbom/maven-metadata.xml",
        FakePeers.Scripted.ok(
            "<metadata><groupId>eu.wohlben.qits</groupId><artifactId>qits-no-sbom</artifactId>"
                + "<versioning><versions><version>2026.1001.1</version></versions></versioning>"
                + "</metadata>"));

    as("qits:system")
        .post(BASE + "/sbom-check/runs")
        .then()
        .statusCode(202)
        .body("filed", equalTo(false))
        .body("entries.size()", equalTo(1))
        .body("entries[0].project", equalTo("qits"))
        .body("entries[0].repository", equalTo(Fixture.REPOSITORY))
        .body("entries[0].ecosystem", equalTo("maven"))
        .body("entries[0].name", equalTo(NAME))
        .body("entries[0].version", equalTo("2026.1001.1"))
        .body("entries[0].reason", equalTo("MISSING"));

    assertTrue(
        peers.calls.stream().noneMatch(call -> call.url().contains("/projects/api/work")),
        "report-only calls nothing in qits-projects: " + peers.calls);

    as("qits:agent")
        .get(BASE + "/sbom-check")
        .then()
        .statusCode(200)
        .body("ranAt", notNullValue())
        .body("entries[0].version", equalTo("2026.1001.1"));
  }

  /** A probe qits-artifacts cannot answer is a 502 with the sentence, never a guess. */
  @Test
  void aProbeFailureIsABadGatewayAndNothingIsStored() {
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            NAME,
            "2026.1001.1",
            Fixture.REPOSITORY,
            Instant.now().minus(Duration.ofDays(2)),
            new ReleaseOrigin("qits", null, null));
    store.markArtifactMissing(id);
    peers.answer(
        PeerTarget.MAVEN_REGISTRY,
        "/eu/wohlben/qits/qits-no-sbom/maven-metadata.xml",
        FakePeers.Scripted.status(503, "down"));

    as("qits:admin")
        .post(BASE + "/sbom-check/runs")
        .then()
        .statusCode(502)
        .body("message", containsString("503"));
    as("qits:admin").get(BASE + "/sbom-check").then().statusCode(404);
  }

  /** An agent reads the report and does not start a run. */
  @Test
  void anAgentReadsTheReportButCannotStartARun() {
    as("qits:agent").post(BASE + "/sbom-check/runs").then().statusCode(403);
    as("qits:reader").get(BASE + "/sbom-check").then().statusCode(403);
  }

  /**
   * qits-628 follow-up: an ADMIN workspace's coding agent's credential, carrying {@code
   * qits:admin-agent} and NOT {@code qits:admin}, presses this door exactly as {@code qits:admin}
   * and {@code qits:system} do — and plain {@code qits:agent}, as {@link
   * #anAgentReadsTheReportButCannotStartARun} just proved, still cannot.
   */
  @Test
  void anAdminAgentStartsARunAndPlainAgentStillCannot() {
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            NAME,
            "2026.1001.1",
            Fixture.REPOSITORY,
            Instant.now().minus(Duration.ofDays(2)),
            new ReleaseOrigin("qits", "artifacts", null));
    store.markArtifactMissing(id);
    peers.answer(
        PeerTarget.MAVEN_REGISTRY,
        "/eu/wohlben/qits/qits-no-sbom/maven-metadata.xml",
        FakePeers.Scripted.ok(
            "<metadata><groupId>eu.wohlben.qits</groupId><artifactId>qits-no-sbom</artifactId>"
                + "<versioning><versions><version>2026.1001.1</version></versions></versioning>"
                + "</metadata>"));

    as("qits:admin-agent").post(BASE + "/sbom-check/runs").then().statusCode(202);
    as("qits:agent").post(BASE + "/sbom-check/runs").then().statusCode(403);
  }
}

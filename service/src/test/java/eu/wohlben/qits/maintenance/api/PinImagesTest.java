package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code GET /pins/images} is qits-mirror's keep-set: complete, or a 503</b> (qits-2253).
 *
 * <p>The mirror evicts every mirrored tag the answer does not name. So the answer is the union of
 * each repository's last scan and what its main declared — EXTERNAL {@code docker} pins only, one
 * per (name, version) — and it refuses wherever {@code GET /pins} refuses, and when it would be
 * empty. And {@code GET /pins} itself must not start serving the EXTERNAL rows main now keeps.
 */
@QuarkusTest
class PinImagesTest {

  private static final String BASE = "/maintenance/api";

  /** The fixture Dockerfile's external base image, through the mirror host the scan strips. */
  private static final String MANDREL = "quay/quarkus/ubi9-quarkus-mandrel-builder-image";

  private static final String MANDREL_TAG = "jdk-25";

  /** The fixture Dockerfile's internal base image: never the mirror's. */
  private static final String MAVEN_BASE = "qits/build-images/maven-base";

  private static final String RELEASE = "2026.1010.192700";

  private static final String APPLICATION = "qits-ci-app";

  @Inject FakePeers peers;

  @Inject MaintenanceStore store;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void reset() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
  }

  // --- the set --------------------------------------------------------------------------------

  /**
   * A main scan stores the external image in the last scan AND in main's rows: one entry, and the
   * internal image, the external maven and npm pins beside it, are not the mirror's.
   */
  @Test
  void aMainScansExternalImageIsServedOnceAndNothingElseIs() {
    scanMain();
    assertTrue(
        store.allMainPins().stream()
            .anyMatch(
                pin ->
                    pin.name.equals(MANDREL) && PinKind.EXTERNAL.name().equals(pin.kind)),
        "main keeps its external image pin");
    assertTrue(
        store.pins(Fixture.REPOSITORY).stream().anyMatch(pin -> pin.name.equals(MANDREL)),
        "and so does the last scan");

    assertEquals(List.of(Map.of("name", MANDREL, "version", MANDREL_TAG)), images());
  }

  /**
   * A release scan replaces the last scan with a tag's pins: main's image is still served from
   * main's rows alone, the tag's from the last scan alone, a duplicate once, and INTERNAL and
   * non-docker EXTERNAL pins of either never — sorted by name, then version.
   */
  @Test
  void theUnionOfMainAndTheLastScanIsServedDedupedAndSorted() {
    scanMain();
    lastScanIs(
        docker("hub/library/postgres", "18.4", "docker/Dockerfile"),
        docker("hub/library/postgres", "18.4", "db/Dockerfile"),
        docker("node", "24-alpine", "frontend/Dockerfile"),
        docker("node", "22-alpine", "tools/Dockerfile"),
        docker(MAVEN_BASE, "2026.900.1", "docker/Dockerfile"),
        ParsedPin.of(
            Ecosystem.MAVEN,
            "pom.xml",
            "io.quarkus.platform:quarkus-bom",
            "3.34.6",
            null,
            "property:quarkus.platform.version"));

    assertEquals(
        List.of(
            Map.of("name", "hub/library/postgres", "version", "18.4"),
            Map.of("name", "node", "version", "22-alpine"),
            Map.of("name", "node", "version", "24-alpine"),
            Map.of("name", MANDREL, "version", MANDREL_TAG)),
        images());
  }

  /** Only main's rows name an image, and the last scan names none: main's are served. */
  @Test
  void anImageOnlyMainDeclaresIsServed() {
    lastScanIs(docker(MAVEN_BASE, "2026.813.1", "docker/Dockerfile"));
    store.replaceMainPins(
        Fixture.REPOSITORY,
        Fixture.HEAD_SHA,
        List.of(
            mainPin("hub/library/postgres", "18.4", PinKind.EXTERNAL),
            mainPin(MAVEN_BASE, "2026.813.1", PinKind.INTERNAL)));

    assertEquals(List.of(Map.of("name", "hub/library/postgres", "version", "18.4")), images());
  }

  // --- the refusals ---------------------------------------------------------------------------

  @Test
  void anEmptyInventoryIsARefusal() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins/images")
        .then()
        .statusCode(503)
        .body("message", containsString("holds no repository"));
  }

  @Test
  void aDeployerThatCannotAnswerIsARefusal() {
    scanMain();
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.unreachable("connection refused"));

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins/images")
        .then()
        .statusCode(503)
        .body("message", containsString("deployed versions could not be read"));
  }

  /** The same hole {@code GET /pins} refuses for: a deployed release the ledger never recorded. */
  @Test
  void aDeployedVersionTheLedgerNeverRecordedIsARefusal() {
    scanMain();
    store.upsertArtifact(
        Ecosystem.DOCKER,
        "qits/" + APPLICATION,
        RELEASE,
        Fixture.REPOSITORY,
        Instant.parse("2026-10-10T19:27:00Z"));
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.ok(
            "{\"pins\":[{\"applicationName\":\"" + APPLICATION + "\",\"shas\":[\"" + RELEASE
                + "\"]}]}"));

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins/images")
        .then()
        .statusCode(503)
        .body("message", containsString(APPLICATION + " " + RELEASE));
  }

  /** An inventory that names no external image would evict every mirrored tag: a refusal. */
  @Test
  void anEmptyKeepSetIsARefusal() {
    lastScanIs(docker(MAVEN_BASE, "2026.813.1", "docker/Dockerfile"));
    store.replaceMainPins(
        Fixture.REPOSITORY,
        Fixture.HEAD_SHA,
        List.of(mainPin(MAVEN_BASE, "2026.813.1", PinKind.INTERNAL)));

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins/images")
        .then()
        .statusCode(503)
        .body("message", containsString("no external image pin"));
  }

  // --- GET /pins is unchanged -----------------------------------------------------------------

  /** Main's EXTERNAL rows are the mirror's: the GC's pin source still serves INTERNAL ones only. */
  @Test
  void thePinSourceDoesNotServeMainsExternalRows() {
    scanMain();
    store.replaceMainPins(
        Fixture.REPOSITORY,
        Fixture.HEAD_SHA,
        List.of(
            mainPin("hub/library/postgres", "18.4", PinKind.EXTERNAL),
            mainPin(MAVEN_BASE, "2026.813.1", PinKind.INTERNAL)));

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.name", hasItem(MAVEN_BASE))
        .body("pins.name", not(hasItem("hub/library/postgres")))
        .body("pins.name", not(hasItem(MANDREL)))
        .body("pins.findAll { it.ecosystem == 'docker' && it.via == null }.name", not(empty()));
  }

  /** The door takes the same roles as {@code GET /pins}: an agent may read it. */
  @Test
  void anAgentMayReadIt() {
    scanMain();
    given()
        .header("Authorization", SuiteBearers.bearer("an-agent", "qits:agent"))
        .when()
        .get(BASE + "/pins/images")
        .then()
        .statusCode(200)
        .body("[0].name", equalTo(MANDREL));
  }

  // --- helpers --------------------------------------------------------------------------------

  private List<Map<String, String>> images() {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins/images")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("$");
  }

  private static ParsedPin docker(String name, String tag, String manifest) {
    return ParsedPin.of(Ecosystem.DOCKER, manifest, name, tag, null, "from:1");
  }

  private static MaintenanceStore.MainPin mainPin(String name, String tag, PinKind kind) {
    return new MaintenanceStore.MainPin(
        Ecosystem.DOCKER.wireName(), name, tag, "docker/Dockerfile", null, kind);
  }

  /** The fixture repository's last scan replaced with exactly these pins, as a tag scan does. */
  private void lastScanIs(ParsedPin... pins) {
    Set<String> internal = Set.of(MAVEN_BASE);
    store.replaceInventory(
        Fixture.REPOSITORY,
        Fixture.PROJECT,
        Fixture.CATALOG_ID,
        null,
        "main",
        RepositoryStatus.OK,
        Fixture.TAG_SHA,
        null,
        List.of(pins),
        List.of(),
        GroupSource.DEFAULT,
        pin -> internal.contains(pin.name()) ? PinKind.INTERNAL : PinKind.EXTERNAL,
        Instant.now());
  }

  /** A full scan at main, the way the client asks for one. */
  private void scanMain() {
    String id =
        given()
            .header("Authorization", SuiteBearers.platform())
            .contentType(ContentType.JSON)
            .body("{\"scope\":\"ALL\"}")
            .when()
            .post(BASE + "/scans")
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      String status =
          given()
              .header("Authorization", SuiteBearers.platform())
              .when()
              .get(BASE + "/scans/" + id)
              .then()
              .statusCode(200)
              .extract()
              .path("status");
      if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
        assertEquals("SUCCEEDED", status);
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
    throw new AssertionError("the scan never finished");
  }
}

package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code GET /pins} — the artifact GC's keep-set, and the images and daemon binaries inside it
 * that no manifest of anybody's spells out.</b>
 *
 * <p>One rule, asserted twice over two artifact types: the daemon block at the bottom is the image
 * block above it with {@code qits/eventstream-daemon} swapped for a binary in the platform's {@code
 * daemons} store, because that is exactly what the change was — {@code control/CarriedDaemons} is
 * {@code control/CarriedImages} over the other carried type, and both call one walk.
 *
 * <p>Container image versions are pom pins now. A repository that pins {@code
 * qits-workspace-daemon-protocol} at a version has, by that one line, named {@code qits/workspace}
 * at the same version — the release stamped both — and between the bump landing on main and that
 * repository deploying, nothing else on the platform names that image at all: the configuration
 * source that used to carry it is empty, and each launching service's own door answers for what it
 * is running rather than for what it will run next. So the assertions below are about ONE rule and
 * its edges: a keep is produced from the co-release, from nothing else, and never for a version the
 * release did not carry.
 *
 * <p>The graph is seeded through the store for the reason {@code SbomApiTest} gives — what this
 * route serves is a READ, and driving a real ingest would test somebody else's subject through six
 * more layers — while the pins come from a real scan of the fixture repository, because the whole
 * point is the join between the two.
 */
@QuarkusTest
class PinSourceApiTest {

  private static final String BASE = "/maintenance/api";

  /** An INTERNAL maven pin the fixture's root pom holds, at a property. */
  private static final String EVENTSTREAM = "eu.wohlben.qits:qits-eventstream";

  private static final String EVENTSTREAM_VERSION = "2026.811.1";

  /** The repository whose release published it — not the fixture's, which is the pinning one. */
  private static final String EVENTSTREAM_REPOSITORY = "qits-eventstream-javalib";

  /** The image that release carried, which the pom above names without writing it down. */
  private static final String DAEMON_IMAGE = "qits/eventstream-daemon";

  /** An INTERNAL npm pin the fixture's lock resolves, whose release carried no image at all. */
  private static final String UI_COMPONENTS = "@qits/ui-components";

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

  /** Fills the inventory the way the client does, so the pins this route serves are real. */
  private void scan() {
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

  /** The eventstream release as the {@code SoftwareRelease} frame left it: a jar and an image. */
  private void theCarrierRelease() {
    store.upsertArtifact(
        Ecosystem.MAVEN,
        EVENTSTREAM,
        EVENTSTREAM_VERSION,
        EVENTSTREAM_REPOSITORY,
        Instant.parse("2026-08-11T10:00:00Z"));
    store.upsertArtifact(
        Ecosystem.DOCKER,
        DAEMON_IMAGE,
        EVENTSTREAM_VERSION,
        EVENTSTREAM_REPOSITORY,
        Instant.parse("2026-08-11T10:00:00Z"));
  }

  /** The find, spelled as the route's rows are: {@code <name>:<version>}. */
  private static String coordinate(String name, String version) {
    return name + ":" + version;
  }

  // --- the rule -----------------------------------------------------------------------------------

  /**
   * THE WHOLE POINT. A pom pins a maven version; the release that published that version also
   * published an image stamped with it; the image is in the keep-set, named by the pom that holds
   * the line and by the coordinate it was resolved through.
   */
  @Test
  void anImageItsCarriersReleaseStampedIsKeptByTheManifestThatPinsTheCarrier() {
    theCarrierRelease();
    scan();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(
            "pins.findAll { it.ecosystem == 'docker' }.collect { it.name + ':' + it.version }",
            hasItem(coordinate(DAEMON_IMAGE, EVENTSTREAM_VERSION)))
        // The PINNING repository and its manifest, because "who still holds this" is what a
        // collector asks the moment it decides not to delete something.
        .body(
            "pins.find { it.name == '" + DAEMON_IMAGE + "' }.repository",
            equalTo(Fixture.REPOSITORY))
        .body("pins.find { it.name == '" + DAEMON_IMAGE + "' }.manifestPath", equalTo("pom.xml"))
        // …and `via` says where the NAME came from, which no other field can.
        .body(
            "pins.find { it.name == '" + DAEMON_IMAGE + "' }.via",
            equalTo("maven " + EVENTSTREAM));
  }

  /** A row a manifest really wrote carries no {@code via}, and the two must stay distinguishable. */
  @Test
  void aPinAManifestWroteOutCarriesNoVia() {
    theCarrierRelease();
    scan();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.find { it.name == '" + EVENTSTREAM + "' }.via", nullValue())
        // The fixture's Dockerfile names this one itself. It is a docker row like the derived one
        // and it is not derived, which is exactly what `via` is there to say.
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.via", nullValue());
  }

  /**
   * ONLY THE CO-RELEASED VERSION. A later release of the same repository is a version nobody pins,
   * and handing a collector a keep for it would be this service inventing a reference.
   */
  @Test
  void anImageTheSameRepositoryReleasedAtAnotherVersionIsNotKept() {
    theCarrierRelease();
    store.upsertArtifact(
        Ecosystem.DOCKER,
        DAEMON_IMAGE,
        "2026.899.9",
        EVENTSTREAM_REPOSITORY,
        Instant.parse("2026-08-29T10:00:00Z"));
    scan();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(
            "pins.collect { it.name + ':' + it.version }",
            hasItem(coordinate(DAEMON_IMAGE, EVENTSTREAM_VERSION)))
        .body(
            "pins.collect { it.name + ':' + it.version }",
            not(hasItem(coordinate(DAEMON_IMAGE, "2026.899.9"))));
  }

  /**
   * A CARRIER THAT CARRIED NOTHING. The npm pin's release published no image, and an answer that
   * guessed one would put a name in a keep-set no release ever produced.
   */
  @Test
  void aCarrierWhoseReleasePublishedNoImageAddsNothing() {
    store.upsertArtifact(
        Ecosystem.NPM, UI_COMPONENTS, "2026.8.1", "qits-ui-components-jslib", Instant.now());
    scan();

    int docker =
        given()
            .header("Authorization", SuiteBearers.platform())
            .when()
            .get(BASE + "/pins")
            .then()
            .statusCode(200)
            .extract()
            .path("pins.findAll { it.ecosystem == 'docker' }.size()");
    // Only the one the fixture's Dockerfile writes out.
    assertEquals(1, docker);
  }

  /**
   * NOTHING RELEASED, NOTHING RESOLVED. With no artifact rows at all the answer is the stored pins
   * and no more — which is also what this route answered before the co-release rule existed, and
   * what it answers for a coordinate this platform does not publish.
   */
  @Test
  void aPinWhoseCoordinateThisPlatformNeverReleasedResolvesToNothing() {
    scan();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.findAll { it.via != null }.size()", equalTo(0))
        .body(
            "pins.collect { it.name + ':' + it.version }",
            not(hasItem(coordinate(DAEMON_IMAGE, EVENTSTREAM_VERSION))));
  }

  /**
   * AN EMPTY INVENTORY STILL REFUSES. The derivation runs off the pins, so a store with none must
   * not turn "this service has never scanned anything" into a 200 with an empty keep-set.
   */
  @Test
  void anEmptyInventoryRefusesRatherThanAnsweringAnEmptyKeepSet() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(503);
  }

  // --- the same rule, one artifact type over: the daemon binary a pom pin names --------------------

  /** The daemon binary the same release put in the platform's binary store. */
  private static final String DAEMON_BINARY = "qits-platform-access-cli";

  /**
   * The eventstream release with a daemon binary beside the jar — the shape the qits CLI's own
   * release has: {@code {type: maven, …-binary}} and {@code {type: daemon, …}} at one version.
   */
  private void theCarrierReleaseWithADaemon() {
    theCarrierRelease();
    store.upsertDaemonArtifact(
        DAEMON_BINARY, EVENTSTREAM_VERSION, EVENTSTREAM_REPOSITORY,
        Instant.parse("2026-08-11T10:00:00Z"));
  }

  /**
   * THE OTHER HALF OF THE POINT. A pom pins a maven version; the release that published that version
   * also published a daemon binary stamped with it; the binary is in the keep-set, as a row whose
   * ecosystem is the literal {@code daemon} — the word qits-artifacts files it by — named by the pom
   * that holds the line and by the coordinate it was resolved through.
   */
  @Test
  void aDaemonItsCarriersReleaseStampedIsKeptByTheManifestThatPinsTheCarrier() {
    theCarrierReleaseWithADaemon();
    scan();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(
            "pins.findAll { it.ecosystem == 'daemon' }.collect { it.name + ':' + it.version }",
            hasItem(coordinate(DAEMON_BINARY, EVENTSTREAM_VERSION)))
        .body("pins.find { it.name == '" + DAEMON_BINARY + "' }.repository",
            equalTo(Fixture.REPOSITORY))
        .body("pins.find { it.name == '" + DAEMON_BINARY + "' }.manifestPath", equalTo("pom.xml"))
        // A derived row is never mistaken for a stored pin, and nothing writes a daemon line out.
        .body(
            "pins.find { it.name == '" + DAEMON_BINARY + "' }.via",
            equalTo("maven " + EVENTSTREAM));
  }

  /**
   * ONLY THE CO-RELEASED VERSION, for a daemon as for an image. A later binary of the same
   * repository is a version nobody pins, and keeping it would be this service inventing a reference
   * — which for a store collecting at {@code window=P0D} is exactly the mistake that costs nothing
   * and hides everything.
   */
  @Test
  void aDaemonTheSameRepositoryReleasedAtAnotherVersionIsNotKept() {
    theCarrierReleaseWithADaemon();
    store.upsertDaemonArtifact(
        DAEMON_BINARY, "2026.899.9", EVENTSTREAM_REPOSITORY,
        Instant.parse("2026-08-29T10:00:00Z"));
    scan();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(
            "pins.collect { it.name + ':' + it.version }",
            hasItem(coordinate(DAEMON_BINARY, EVENTSTREAM_VERSION)))
        .body(
            "pins.collect { it.name + ':' + it.version }",
            not(hasItem(coordinate(DAEMON_BINARY, "2026.899.9"))));
  }

  /**
   * NO ARTIFACT ROW, NO KEEP — the honesty the image rule has. A version this service never saw a
   * daemon released at resolves to nothing rather than to a name invented for it.
   */
  @Test
  void aVersionWithNoDaemonRowYieldsNoDaemonRowAtAll() {
    theCarrierRelease();
    scan();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.findAll { it.ecosystem == 'daemon' }.size()", equalTo(0));
  }

  /**
   * The two derivations run off the STORED pins and not off each other's output. A daemon row is
   * derived from the maven pin the manifest holds, never from the docker row this answer derived a
   * line earlier — which is why the snapshot in {@code Inventory.pins} is taken before either.
   */
  @Test
  void theImageAndTheDaemonAreBothDerivedFromTheStoredPinAndNotFromEachOther() {
    theCarrierReleaseWithADaemon();
    scan();

    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.find { it.name == '" + DAEMON_IMAGE + "' }.via", equalTo("maven " + EVENTSTREAM))
        .body(
            "pins.find { it.name == '" + DAEMON_BINARY + "' }.via", equalTo("maven " + EVENTSTREAM));

    // …and the derived rows are sorted INTO the one total order the answer is served in rather than
    // appended after it, which is what makes two reads over an unchanged store answer identically.
    List<String> ecosystems =
        given()
            .header("Authorization", SuiteBearers.platform())
            .when()
            .get(BASE + "/pins")
            .then()
            .statusCode(200)
            .extract()
            .path("pins.collect { it.ecosystem }");
    assertEquals(ecosystems.stream().sorted().toList(), ecosystems);
  }
}

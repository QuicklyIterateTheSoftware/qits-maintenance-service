package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code GET /pins} is a union, never a replace (qits-1172).</b>
 *
 * <p>On 2026-10-10 a release scan replaced a repository's pins with the TAG's, the tag ran ahead of
 * main and of the deployment, and the GC deleted the versions main and the deployed qits-ci still
 * used. Each case below is one rule of the fix: main's pins survive a tag scan; a release not on
 * main is kept; a release on main is dropped unless it serves or is a rollback target; the
 * deployer's silence is a 503; and a tag the git host does not hold wipes nothing.
 */
@QuarkusTest
class PinsUnionTest {

  private static final String BASE = "/maintenance/api";

  private static final String EVENTSTREAM = "eu.wohlben.qits:qits-eventstream";

  /** What the fixture's main pins. */
  private static final String MAIN_VERSION = "2026.811.1";

  /** What the release tag pins — ahead of main. */
  private static final String TAG_PIN_VERSION = "2026.821.3";

  private static final String RELEASE = "2026.1010.192700";

  @Inject FakePeers peers;

  @Inject MaintenanceStore store;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject ScanService scans;

  @BeforeEach
  void reset() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
  }

  // --- rule 1: main's pins survive a release scan of a tag ahead of main -------------------------

  @Test
  void aReleaseScanAheadOfMainKeepsWhatMainPins() {
    scanMain();
    releasedAheadOfMain();

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), hasItem(MAIN_VERSION))
        .body(eventstreamVersions(), hasItem(TAG_PIN_VERSION));
  }

  /** The git host cannot say whether the release is on main: it is kept. */
  @Test
  void aReleaseNobodyCanPlaceOnMainIsKept() {
    releasedAheadOfMain();
    scanMain();

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), hasItem(TAG_PIN_VERSION));
  }

  // --- rule 2: a release main has caught up with is main's to speak for -------------------------

  @Test
  void aReleaseOnMainIsNoLongerKeptByItself() {
    releasedAheadOfMain();
    Fixture.scriptContains(peers, Fixture.TAG_SHA, Fixture.HEAD_SHA, true);
    scanMain();

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), hasItem(MAIN_VERSION))
        .body(eventstreamVersions(), not(hasItem(TAG_PIN_VERSION)));
  }

  // --- rule 3: what serves, and what a rollback restores, is kept --------------------------------

  @Test
  void aDeployedReleaseIsKeptAfterMainMovedOn() {
    releasedAheadOfMain();
    Fixture.scriptContains(peers, Fixture.TAG_SHA, Fixture.HEAD_SHA, true);
    scanMain();
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.ok(
            "{\"pins\":[{\"applicationName\":\"qits-ci\",\"shas\":[\"2026.1009.1\",\""
                + RELEASE
                + "\"]}]}"));

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), hasItem(TAG_PIN_VERSION))
        .body(
            "pins.find { it.version == '" + TAG_PIN_VERSION + "' && it.via != null }.via",
            org.hamcrest.Matchers.equalTo("release " + RELEASE + " (deployed or rollback)"));
  }

  @Test
  void aDeployerThatCannotAnswerIsARefusalNotAnEmptySet() {
    scanMain();
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.unreachable("connection refused"));

    given().when().get(BASE + "/pins").then().statusCode(503);
  }

  // --- rule 4: a tag the git host does not hold wipes nothing ------------------------------------

  @Test
  void aTagTheGitHostDoesNotHoldKeepsThePins() {
    scanMain();
    int before = store.pins(Fixture.REPOSITORY).size();
    assertNotEquals(0, before);

    // The tag's tree is unscripted: a 404, which the scanner reads as ABSENT.
    UUID id =
        scans.request(
            ScanScope.INTERNAL, Fixture.REPOSITORY, ScanTrigger.EVENT, "refs/tags/2026.1010.9");
    awaitScan(id.toString());

    assertEquals(before, store.pins(Fixture.REPOSITORY).size());
    assertFalse(
        store
            .repository(Fixture.REPOSITORY)
            .map(row -> RepositoryStatus.ABSENT.name().equals(row.status))
            .orElse(true));
  }

  // --- helpers ------------------------------------------------------------------------------------

  /**
   * A release cut at {@link Fixture#TAG_SHA} pinning the newer version, and the release scan of its
   * tag replacing the repository's last-scan rows with exactly that — what the bus did live.
   */
  private void releasedAheadOfMain() {
    store.recordRelease(
        Fixture.REPOSITORY,
        RELEASE,
        Fixture.TAG_SHA,
        Instant.parse("2026-10-10T19:27:00Z"),
        List.of(new MaintenanceStore.ReleasePin(Ecosystem.MAVEN, EVENTSTREAM, TAG_PIN_VERSION)));
    store.replaceInventory(
        Fixture.REPOSITORY,
        Fixture.PROJECT,
        Fixture.CATALOG_ID,
        null,
        "main",
        RepositoryStatus.OK,
        Fixture.TAG_SHA,
        null,
        List.of(
            ParsedPin.of(
                Ecosystem.MAVEN,
                "pom.xml",
                EVENTSTREAM,
                TAG_PIN_VERSION,
                null,
                "property:qits.eventstream.version")),
        List.of(),
        GroupSource.DEFAULT,
        pin -> PinKind.INTERNAL,
        Instant.now());
  }

  private static String eventstreamVersions() {
    return "pins.findAll { it.name == '" + EVENTSTREAM + "' }.collect { it.version }";
  }

  /** A full scan at main, the way the client asks for one. */
  private void scanMain() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"scope\":\"ALL\"}")
            .when()
            .post(BASE + "/scans")
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    awaitScan(id);
  }

  private void awaitScan(String id) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      String status =
          given().when().get(BASE + "/scans/" + id).then().statusCode(200).extract().path("status");
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

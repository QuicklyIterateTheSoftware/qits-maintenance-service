package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.adoption.ReleaseLedgerHeal;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
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
 * deployer's silence is a 503, and so is a deployed release of ours the ledger never recorded; and
 * a tag the git host does not hold wipes nothing.
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

  @Inject ReleaseLedgerHeal heal;

  @BeforeEach
  void reset() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    heal.forget();
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

  // --- rule 3b: a deployed release the ledger never recorded is a refusal ------------------------

  /** The deployed application, as qits-deployments names it: the last segment of its image. */
  private static final String APPLICATION = "qits-ci-app";

  /**
   * <b>THE HOLE.</b> The deployer serves {@link #RELEASE} of an application the fixture repository
   * released the image of, the ledger has no row for that release — {@code ReleaseLedger} declined
   * it — and main is not at it. The ledger union used to match that version against nothing and
   * answer 200 without its pins; the GC believed it.
   */
  @Test
  void aDeployedVersionTheLedgerNeverRecordedIsARefusal() {
    scanMain();
    imageReleasedBy(Fixture.REPOSITORY);
    deployed(APPLICATION, RELEASE);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(503)
        .body("message", containsString(APPLICATION + " " + RELEASE))
        .body("message", containsString("repository " + Fixture.REPOSITORY));
  }

  /** The control: the same deployment with its ledger row is answered, and its pins are kept. */
  @Test
  void aDeployedVersionTheLedgerRecordedIsAnswered() {
    releasedAheadOfMain();
    Fixture.scriptContains(peers, Fixture.TAG_SHA, Fixture.HEAD_SHA, true);
    scanMain();
    imageReleasedBy(Fixture.REPOSITORY);
    deployed(APPLICATION, RELEASE);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), hasItem(TAG_PIN_VERSION));
  }

  /**
   * The release {@code main}'s head carries needs no ledger row: main's pins, read at that very
   * commit, are what it declared, and {@code mt_main_pin} serves them.
   */
  @Test
  void aDeployedVersionThatIsMainsReleaseIsAnsweredWithoutALedgerRow() {
    scanMain();
    imageReleasedBy(Fixture.REPOSITORY);
    store.recordGitlinkLatest(
        Fixture.REPOSITORY, RELEASE, GitlinkSha.of(Fixture.HEAD_SHA), Instant.now());
    deployed(APPLICATION, RELEASE);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), hasItem(MAIN_VERSION));
  }

  /**
   * …but only when main's pins were read AT that head. A latest that moved on a push frame ahead of
   * the scan names the release while {@code mt_main_pin} still holds the previous head's pins.
   */
  @Test
  void mainsReleaseWhoseHeadMainsPinsWereNotReadAtIsStillARefusal() {
    scanMain();
    imageReleasedBy(Fixture.REPOSITORY);
    store.recordGitlinkLatest(
        Fixture.REPOSITORY, RELEASE, GitlinkSha.of(Fixture.TAG_SHA), Instant.now());
    deployed(APPLICATION, RELEASE);

    given().when().get(BASE + "/pins").then().statusCode(503);
  }

  /**
   * <b>THE REFUSAL HEALS ITSELF.</b> The ledger is younger than the deployer's rollback set and has
   * no write door, so a 503 that only refused would stop the GC until the application redeployed.
   * The first read refuses and queues the record; the worker reads the tag exactly as an {@code
   * SCMRelease} would have; the next read is answered with that release's pins.
   */
  @Test
  void aRefusalRecordsTheReleaseFromItsTagAndTheNextReadIsAnswered() {
    scanMain();
    imageReleasedBy(Fixture.REPOSITORY);
    deployed(APPLICATION, RELEASE);
    tagHeld(RELEASE);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(503)
        .body("message", containsString("being recorded from its tag"));

    queue.awaitIdle(Duration.ofSeconds(30));
    assertTrue(store.releaseRecorded(Fixture.REPOSITORY, RELEASE), "the worker recorded it");

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), hasItem(TAG_PIN_VERSION))
        .body(
            "pins.find { it.version == '" + TAG_PIN_VERSION + "' && it.via != null }.via",
            org.hamcrest.Matchers.startsWith("release " + RELEASE));
  }

  /**
   * A tag the git host does not hold is a genuine inconsistency: it stays a 503 that says so, and a
   * GC polling every fifteen minutes does not read the git host about it on every poll.
   */
  @Test
  void aTagTheGitHostDoesNotHoldStaysARefusalAndIsNotAskedAboutOnEveryRead() {
    scanMain();
    imageReleasedBy(Fixture.REPOSITORY);
    deployed(APPLICATION, RELEASE);

    given().when().get(BASE + "/pins").then().statusCode(503);
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(1, tagReads(RELEASE), "the first refusal asked once");

    for (int poll = 0; poll < 3; poll++) {
      given()
          .when()
          .get(BASE + "/pins")
          .then()
          .statusCode(503)
          .body("message", containsString("holds no tag refs/tags/" + RELEASE));
      queue.awaitIdle(Duration.ofSeconds(30));
    }
    assertEquals(1, tagReads(RELEASE), "and nothing after it asked again");
    assertFalse(store.releaseRecorded(Fixture.REPOSITORY, RELEASE));
  }

  /**
   * An application no repository of this inventory released an image for — something this service
   * keeps no ledger of — is skipped rather than refused, or the GC would never run again.
   */
  @Test
  void aDeployedApplicationThatMapsToNoRepositoryIsSkipped() {
    scanMain();
    imageReleasedBy(Fixture.REPOSITORY);
    deployed("postgres-exporter", RELEASE);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), hasItem(MAIN_VERSION));
  }

  // --- rule 3c: a repository nobody maintains any more keeps nothing by "not on main" -------------

  /**
   * <b>THE STUCK KEEP</b> (qits-1735). A release recorded not on main, under a name the catalog then
   * renamed away: nothing scans an ABSENT repository, so {@code on_main} never flips and the ledger
   * served {@code release <v> (not on main yet)} for ever. It is kept only while it is deployed.
   */
  @Test
  void aNotOnMainReleaseOfARenamedAwayRepositoryIsNotKept() {
    notOnMainReleaseOfARetiredRepository(RepositoryStatus.ABSENT);
  }

  /** The same for a repository its project no longer declares (qits-1695's UNDECLARED). */
  @Test
  void aNotOnMainReleaseOfAnUndeclaredRepositoryIsNotKept() {
    notOnMainReleaseOfARetiredRepository(RepositoryStatus.UNDECLARED);
  }

  private void notOnMainReleaseOfARetiredRepository(RepositoryStatus status) {
    scanMain();
    recordedNotOnMain();
    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), hasItem(TAG_PIN_VERSION));

    retire(status);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body(eventstreamVersions(), not(hasItem(TAG_PIN_VERSION)));
  }

  /**
   * …but a version the deployer serves is kept whatever became of the repository: a running
   * system still needs what it was built from. Its image producer is the ABSENT row, which is not
   * asked to heal, so the application is kept by version alone.
   */
  @Test
  void aDeployedReleaseOfARenamedAwayRepositoryIsStillKept() {
    scanMain();
    recordedNotOnMain();
    imageReleasedBy(Fixture.REPOSITORY);
    retire(RepositoryStatus.ABSENT);
    deployed(APPLICATION, RELEASE);

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

  /**
   * An application whose only producer is an ABSENT repository is not a ledger question: the heal
   * would record a not-on-main row under a name nothing scans, which is how the stuck keep above was
   * made. It is answered, kept by version alone, and its tag is never read.
   */
  @Test
  void aDeployedApplicationProducedOnlyByARenamedAwayRepositoryIsNotHealed() {
    scanMain();
    imageReleasedBy(Fixture.REPOSITORY);
    retire(RepositoryStatus.ABSENT);
    deployed(APPLICATION, RELEASE);
    tagHeld(RELEASE);

    given().when().get(BASE + "/pins").then().statusCode(200);
    queue.awaitIdle(Duration.ofSeconds(30));

    assertFalse(store.releaseRecorded(Fixture.REPOSITORY, RELEASE), "no ledger row was healed");
    assertEquals(0, tagReads(RELEASE), "and its tag was never read");
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

  /** A release of the fixture repository at {@link #RELEASE}, recorded and not on main. */
  private void recordedNotOnMain() {
    store.recordRelease(
        Fixture.REPOSITORY,
        RELEASE,
        Fixture.TAG_SHA,
        Instant.parse("2026-10-10T19:27:00Z"),
        List.of(new MaintenanceStore.ReleasePin(Ecosystem.MAVEN, EVENTSTREAM, TAG_PIN_VERSION)));
  }

  /** The fixture repository's row, as a full scan leaves a rename or a dropped submodule. */
  private void retire(RepositoryStatus status) {
    store.markRepository(
        Fixture.REPOSITORY,
        Fixture.PROJECT,
        Fixture.CATALOG_ID,
        null,
        status,
        "no longer maintained",
        Instant.now());
  }

  /** The image release that ties {@link #APPLICATION} to a repository, as the frame recorded it. */
  private void imageReleasedBy(String repository) {
    store.upsertArtifact(
        Ecosystem.DOCKER,
        "qits/" + APPLICATION,
        RELEASE,
        repository,
        Instant.parse("2026-10-10T19:27:00Z"));
  }

  /** The git host holds {@code refs/tags/<version>} at {@link Fixture#TAG_SHA}, with a pom. */
  private void tagHeld(String version) {
    String root = "{\"entries\":[{\"name\":\"pom.xml\",\"type\":\"blob\"}]}";
    java.util.Map<String, String> sha = java.util.Map.of("Git-Commit-Sha", Fixture.TAG_SHA);
    String repository = "/git/" + Fixture.PROJECT + "/" + Fixture.REPOSITORY;
    peers.answer(PeerTarget.GITHOST, tagTree(version), FakePeers.Scripted.ok(root, sha));
    peers.answer(
        PeerTarget.GITHOST, repository + "/tree/" + Fixture.TAG_SHA, FakePeers.Scripted.ok(root, sha));
    peers.answer(
        PeerTarget.GITHOST,
        repository + "/blob/" + Fixture.TAG_SHA + "/pom.xml",
        FakePeers.Scripted.ok(
            """
            <project>
              <groupId>eu.wohlben.qits</groupId>
              <artifactId>qits-ci</artifactId>
              <version>%s</version>
              <dependencies>
                <dependency>
                  <groupId>eu.wohlben.qits</groupId>
                  <artifactId>qits-eventstream</artifactId>
                  <version>%s</version>
                </dependency>
              </dependencies>
            </project>
            """
                .formatted(version, TAG_PIN_VERSION),
            sha));
  }

  private static String tagTree(String version) {
    return "/git/" + Fixture.PROJECT + "/" + Fixture.REPOSITORY + "/tree/refs%2Ftags%2F" + version;
  }

  /** How many times the git host was asked for that tag's tree. */
  private long tagReads(String version) {
    return peers.urls().stream().filter(url -> url.endsWith(tagTree(version))).count();
  }

  /** qits-deployments serves exactly this. */
  private void deployed(String application, String version) {
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.ok(
            "{\"pins\":[{\"applicationName\":\"" + application + "\",\"shas\":[\""
                + version
                + "\"]}]}"));
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

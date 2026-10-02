package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
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
 * The four routes the dependency GRAPH added, and the two fields it added to routes that existed.
 *
 * <p><b>The whole subject is the line between two facts.</b> A pin is what a manifest DECLARES and a
 * bump can edit; a component is what a released package CONTAINS, transitives included. They are
 * joined on {@code (ecosystem, name)} and they never merge — so a repository can pin something none
 * of its artifacts ship, an artifact can ship something no manifest names, and every assertion below
 * is about one side or the other keeping to itself.
 *
 * <p>The graph is seeded through the store the way {@code MaintenanceApiTest} seeds the inventory
 * through a scan: what these routes serve is a READ, and driving a real ingest here would be
 * testing {@code SbomIngestServiceTest}'s subject through six more layers.
 */
@QuarkusTest
class SbomApiTest {

  private static final String BASE = "/maintenance/api";

  /** The artifact the fixture repository publishes. */
  private static final String CI = "eu.wohlben.qits:qits-ci";

  private static final String EVENTSTREAM = "eu.wohlben.qits:qits-eventstream";
  private static final String DATABIND = "com.fasterxml.jackson.core:jackson-databind";
  private static final String ANNOTATIONS = "com.fasterxml.jackson.core:jackson-annotations";
  private static final String POSTGRES = "org.postgresql:postgresql";
  private static final String QUARKUS_BOM = "io.quarkus.platform:quarkus-bom";

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

  /** Fills the inventory the way the client does, so the pins these routes join against are real. */
  private void scan() {
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
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      String status =
          given().when().get(BASE + "/scans/" + id).then().statusCode(200).extract().path("status");
      if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
        assertEquals("SUCCEEDED", status);
        return;
      }
      sleep();
    }
    throw new AssertionError("the scan never finished");
  }

  private static void sleep() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** One released artifact with a graph, as an ingest would have left it. */
  private UUID released(
      Ecosystem ecosystem,
      String name,
      String version,
      String repository,
      Instant occurredAt,
      List<ParsedSbom.Component> components,
      List<ParsedSbom.Edge> edges) {
    UUID id = store.upsertArtifact(ecosystem, name, version, repository, occurredAt);
    store.replaceGraph(id, components, edges, Instant.now());
    return id;
  }

  private static ParsedSbom.Component component(
      String ref, Ecosystem ecosystem, String name, String version, boolean direct) {
    return new ParsedSbom.Component(
        ref, "pkg:maven/" + name.replace(':', '/') + "@" + version, ecosystem, name, version, direct);
  }

  /**
   * What qits-ci released: an eventstream it also PINS, a jackson it does not, and two levels of
   * things no manifest of its own names.
   */
  private UUID theFixtureRelease() {
    return released(
        Ecosystem.MAVEN,
        CI,
        "2026.821.1",
        Fixture.REPOSITORY,
        Instant.parse("2026-09-01T10:00:00Z"),
        List.of(
            component("c-es", Ecosystem.MAVEN, EVENTSTREAM, "2026.811.1", true),
            component("c-db", Ecosystem.MAVEN, DATABIND, "2.18.2", true),
            component("c-an", Ecosystem.MAVEN, ANNOTATIONS, "2.18.2", false),
            component("c-pg", Ecosystem.MAVEN, POSTGRES, "42.7.0", false),
            // A transitive that the repository ALSO pins. It must not appear as a transitive: the
            // page already shows it with a version, a latest and a verdict.
            component("c-bom", Ecosystem.MAVEN, QUARKUS_BOM, "3.34.5", false)),
        List.of(
            new ParsedSbom.Edge(-1, 0),
            new ParsedSbom.Edge(-1, 1),
            new ParsedSbom.Edge(1, 2),
            new ParsedSbom.Edge(1, 3),
            new ParsedSbom.Edge(1, 4)));
  }

  // --- GET /dependencies/dependents ---------------------------------------------------------------

  @Test
  void whoShipsACopyOfThisIsAnsweredFromWhatWasReleasedRatherThanFromManifests() {
    released(
        Ecosystem.MAVEN,
        CI,
        "2026.821.1",
        Fixture.REPOSITORY,
        Instant.parse("2026-09-01T10:00:00Z"),
        List.of(component("c-an", Ecosystem.MAVEN, ANNOTATIONS, "2.18.2", false)),
        List.of(new ParsedSbom.Edge(-1, 0)));

    given()
        .when()
        .get(BASE + "/dependencies/dependents?ecosystem=maven&name=" + ANNOTATIONS)
        .then()
        .statusCode(200)
        .body("ecosystem", equalTo("maven"))
        .body("name", equalTo(ANNOTATIONS))
        // Nothing on this platform pins it, so no lookup ever ran and there is no latest. That is
        // ordinary rather than an error.
        .body("latest", nullValue())
        .body("dependents.size()", equalTo(1))
        .body("dependents[0].artifactName", equalTo(CI))
        .body("dependents[0].artifactVersion", equalTo("2026.821.1"))
        .body("dependents[0].repository", equalTo(Fixture.REPOSITORY))
        .body("dependents[0].embeddedVersion", equalTo("2.18.2"))
        .body("dependents[0].direct", equalTo(false))
        .body("dependents[0].sbomStatus", equalTo("INGESTED"))
        .body("dependents[0].occurredAt", notNullValue());
  }

  /**
   * <b>A DAEMON BINARY IS A DEPENDENT LIKE ANY OTHER ARTIFACT</b> (qits-621 / qits-703). Its
   * document is ingested into the same graph, so "who ships a copy of this" names the binaries that
   * carry a library — served under the stored word {@code daemon}, which is not an {@code Ecosystem},
   * and neither the dependents read nor the artifacts listing may trip over that.
   */
  @Test
  void aDaemonBinaryThatCarriesALibraryIsAmongItsDependents() {
    UUID daemon =
        store.upsertDaemonArtifact(
            "qits-platform-access-cli",
            "2026.1002.1",
            "qits-platform-access-cli",
            Instant.parse("2026-10-02T10:00:00Z"));
    store.replaceGraph(
        daemon,
        List.of(component("c-an", Ecosystem.MAVEN, ANNOTATIONS, "2.18.2", true)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        Instant.now());

    given()
        .when()
        .get(BASE + "/dependencies/dependents?ecosystem=maven&name=" + ANNOTATIONS)
        .then()
        .statusCode(200)
        .body("dependents.size()", equalTo(1))
        .body("dependents[0].artifactEcosystem", equalTo("daemon"))
        .body("dependents[0].artifactName", equalTo("qits-platform-access-cli"))
        .body("dependents[0].embeddedVersion", equalTo("2.18.2"))
        .body("dependents[0].sbomStatus", equalTo("INGESTED"));

    given()
        .when()
        .get(BASE + "/artifacts")
        .then()
        .statusCode(200)
        .body("find { it.name == 'qits-platform-access-cli' }.ecosystem", equalTo("daemon"))
        .body("find { it.name == 'qits-platform-access-cli' }.sbomStatus", equalTo("INGESTED"))
        .body("find { it.name == 'qits-platform-access-cli' }.latest", nullValue());
  }

  /**
   * THE DEFAULT VIEW IS THE NEWEST RELEASE OF EACH DEPENDENT. Forty-nine older releases of one
   * library are answers about versions nobody can change any more.
   */
  @Test
  void theDefaultIsTheNewestReleaseOfEachDependentAndAllTrueIsTheArchaeology() {
    // Written out of order on purpose: what decides "newest" is occurred_at, never the order rows
    // were inserted in or the order a database happened to return them.
    record Release(String version, String occurredAt, String embedded) {}
    for (Release release :
        List.of(
            new Release("2026.810.1", "2026-08-10T10:00:00Z", "2.18.0"),
            new Release("2026.821.1", "2026-08-21T10:00:00Z", "2.18.2"),
            new Release("2026.815.1", "2026-08-15T10:00:00Z", "2.18.1"))) {
      released(
          Ecosystem.MAVEN,
          CI,
          release.version(),
          Fixture.REPOSITORY,
          Instant.parse(release.occurredAt()),
          List.of(component("c-an", Ecosystem.MAVEN, ANNOTATIONS, release.embedded(), false)),
          List.of(new ParsedSbom.Edge(-1, 0)));
    }

    given()
        .when()
        .get(BASE + "/dependencies/dependents?ecosystem=maven&name=" + ANNOTATIONS)
        .then()
        .statusCode(200)
        .body("dependents.size()", equalTo(1))
        .body("dependents[0].artifactVersion", equalTo("2026.821.1"));

    given()
        .when()
        .get(BASE + "/dependencies/dependents?ecosystem=maven&name=" + ANNOTATIONS + "&all=true")
        .then()
        .statusCode(200)
        .body("dependents.size()", equalTo(3))
        // Newest first within one dependent.
        .body("dependents[0].artifactVersion", equalTo("2026.821.1"))
        .body("dependents[2].artifactVersion", equalTo("2026.810.1"));
  }

  @Test
  void aDependencyNothingShipsAnswersWithAnEmptyListRatherThanAFourOhFour() {
    given()
        .when()
        .get(BASE + "/dependencies/dependents?ecosystem=npm&name=@nobody/ships-this")
        .then()
        .statusCode(200)
        .body("dependents", empty());
  }

  @Test
  void anUnknownEcosystemIsRefusedRatherThanAnsweredWithNothing() {
    given()
        .when()
        .get(BASE + "/dependencies/dependents?ecosystem=cargo&name=serde")
        .then()
        .statusCode(400)
        .body("message", containsString("maven, npm or docker"));
    given()
        .when()
        .get(BASE + "/dependencies/dependents?ecosystem=maven")
        .then()
        .statusCode(400)
        .body("message", containsString("name"));
  }

  // --- GET /artifacts -----------------------------------------------------------------------------

  @Test
  void theArtifactListingSaysHowFarEachOnesReachGoesAndHowMuchOfItIsStale() {
    scan();
    // The library, released twice: the listing keeps the newest row.
    released(
        Ecosystem.MAVEN,
        EVENTSTREAM,
        "2026.811.1",
        "qits-eventstream-javalib",
        Instant.parse("2026-08-11T10:00:00Z"),
        List.of(),
        List.of());
    released(
        Ecosystem.MAVEN,
        EVENTSTREAM,
        "2026.821.3",
        "qits-eventstream-javalib",
        Instant.parse("2026-08-21T10:00:00Z"),
        List.of(),
        List.of());
    // And one thing that embeds it, at a version behind what the registry now offers.
    theFixtureRelease();

    given()
        .when()
        .get(BASE + "/artifacts")
        .then()
        .statusCode(200)
        .body("name", hasItem(EVENTSTREAM))
        .body("find { it.name == '" + EVENTSTREAM + "' }.ecosystem", equalTo("maven"))
        .body("find { it.name == '" + EVENTSTREAM + "' }.version", equalTo("2026.821.3"))
        .body("find { it.name == '" + EVENTSTREAM + "' }.repository", equalTo("qits-eventstream-javalib"))
        // The scan filled mt_latest from qits-artifacts' metadata.
        .body("find { it.name == '" + EVENTSTREAM + "' }.latest", equalTo("2026.821.3"))
        .body("find { it.name == '" + EVENTSTREAM + "' }.sbomStatus", equalTo("INGESTED"))
        .body("find { it.name == '" + EVENTSTREAM + "' }.dependentCount", equalTo(1))
        .body("find { it.name == '" + EVENTSTREAM + "' }.behindCount", equalTo(1))
        // …and qits-ci itself is an artifact of ours that nothing embeds.
        .body("find { it.name == '" + CI + "' }.dependentCount", equalTo(0))
        .body("find { it.name == '" + CI + "' }.behindCount", equalTo(0));
  }

  // --- POST /artifacts/ingest ---------------------------------------------------------------------

  @Test
  void aManualIngestIsAcceptedWithTheArtifactRowsIdAndTheRowIsCreated() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(
                "{\"ecosystem\":\"maven\",\"name\":\"" + EVENTSTREAM + "\",\"version\":\"2026.901.1\"}")
            .when()
            .post(BASE + "/artifacts/ingest")
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    assertNotNull(id);
    queue.awaitIdle(Duration.ofSeconds(30));

    // The peers are scripted for a scan and nothing answers the sbom route, so it is a 404 —
    // which is MISSING, the ordinary permanent answer during the rollout.
    assertEquals(
        "MISSING",
        store.artifact(UUID.fromString(id)).orElseThrow().sbomStatus,
        "nothing is stored for this coordinate, and that is not a failure");

    // And the row can be asked for again: the manual route is the only thing that moves a terminal
    // one, which is why it exists.
    given()
        .contentType(ContentType.JSON)
        .body("{\"ecosystem\":\"maven\",\"name\":\"" + EVENTSTREAM + "\",\"version\":\"2026.901.1\"}")
        .when()
        .post(BASE + "/artifacts/ingest")
        .then()
        .statusCode(202)
        .body("id", equalTo(id));
  }

  @Test
  void anIngestNamingNoVersionOrAnUnknownEcosystemIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"ecosystem\":\"cargo\",\"name\":\"serde\",\"version\":\"1.0.0\"}")
        .when()
        .post(BASE + "/artifacts/ingest")
        .then()
        .statusCode(400)
        .body("message", containsString("maven, npm, docker or daemon"));

    given()
        .contentType(ContentType.JSON)
        .body("{\"ecosystem\":\"maven\",\"name\":\"" + EVENTSTREAM + "\"}")
        .when()
        .post(BASE + "/artifacts/ingest")
        .then()
        .statusCode(400)
        .body("message", containsString("version"));
  }

  // --- GET /repositories/{name}/dependents --------------------------------------------------------

  @Test
  void aRepositorysBlastRadiusIsGroupedByTheArtifactItPublishes() {
    // The repository publishes two things, and one dependent embeds each.
    released(
        Ecosystem.MAVEN,
        EVENTSTREAM,
        "2026.821.3",
        "qits-eventstream-javalib",
        Instant.parse("2026-08-21T10:00:00Z"),
        List.of(),
        List.of());
    released(
        Ecosystem.NPM,
        "@qits/ui-components",
        "2026.8.4",
        "qits-eventstream-javalib",
        Instant.parse("2026-08-22T10:00:00Z"),
        List.of(),
        List.of());
    theFixtureRelease();

    given()
        .when()
        .get(BASE + "/repositories/qits-eventstream-javalib/dependents")
        .then()
        .statusCode(200)
        .body("repository", equalTo("qits-eventstream-javalib"))
        .body("artifacts.size()", equalTo(2))
        .body("artifacts.name", hasItem(EVENTSTREAM))
        .body("artifacts.name", hasItem("@qits/ui-components"))
        .body("artifacts.find { it.name == '" + EVENTSTREAM + "' }.dependents.size()", equalTo(1))
        .body(
            "artifacts.find { it.name == '" + EVENTSTREAM + "' }.dependents[0].artifactName",
            equalTo(CI))
        .body(
            "artifacts.find { it.name == '" + EVENTSTREAM + "' }.dependents[0].embeddedVersion",
            equalTo("2026.811.1"))
        .body(
            "artifacts.find { it.name == '" + EVENTSTREAM + "' }.dependents[0].direct",
            equalTo(true))
        .body("artifacts.find { it.name == '@qits/ui-components' }.dependents", empty());
  }

  // --- the rows written before the listener resolved the frame's repository -----------------------

  /**
   * <b>THE READ-SIDE BELT, for rows nothing can rewrite.</b> Until 2026-09-02 this service recorded
   * {@code SoftwareRelease.repository} verbatim, and that field is qits-projects' repository ROW ID
   * rather than the catalog name — so every artifact row released before the fix holds a uuid in
   * the column both reverse reads join on a name, and both of them answered nothing at all.
   *
   * <p>The catalog is what closes it: a scan writes {@code mt_repository.catalog_id}, so the same
   * repository is known under both spellings and {@code ArtifactGraph} translates in each
   * direction — the lookup finds the old rows, and the listing hands the client a name rather than
   * a uuid to link to.
   */
  @Test
  void anArtifactRowStillHoldingTheFramesRowIdIsFoundAndNamedByTheRepositoryItBelongsTo() {
    String rowId = "daf73ae4-1c9a-4f8e-9a51-0b0d0e0f1234";
    store.markRepository(
        "qits-eventstream-javalib", Fixture.PROJECT, rowId, null, RepositoryStatus.OK, null,
        Instant.now());

    // The row a release wrote before the fix: the uuid where a name is read.
    released(
        Ecosystem.MAVEN,
        EVENTSTREAM,
        "2026.821.3",
        rowId,
        Instant.parse("2026-08-21T10:00:00Z"),
        List.of(),
        List.of());
    theFixtureRelease();

    given()
        .when()
        .get(BASE + "/repositories/qits-eventstream-javalib/dependents")
        .then()
        .statusCode(200)
        .body("artifacts.size()", equalTo(1))
        .body("artifacts[0].name", equalTo(EVENTSTREAM))
        .body("artifacts[0].dependents.size()", equalTo(1))
        .body("artifacts[0].dependents[0].artifactName", equalTo(CI));

    given()
        .when()
        .get(BASE + "/artifacts")
        .then()
        .statusCode(200)
        .body(
            "find { it.name == '" + EVENTSTREAM + "' }.repository",
            equalTo("qits-eventstream-javalib"));
  }

  /**
   * And the case that must NOT be translated: a spelling the catalog knows neither way is what a
   * release said, and it is served as it is. Guessing a repository here would put a name on a page
   * that no event ever carried.
   */
  @Test
  void aRepositorySpellingTheCatalogDoesNotKnowIsServedExactlyAsItWasStored() {
    released(
        Ecosystem.MAVEN,
        EVENTSTREAM,
        "2026.821.3",
        "8f7e6d5c-0000-4000-8000-1a2b3c4d5e6f",
        Instant.parse("2026-08-21T10:00:00Z"),
        List.of(),
        List.of());

    given()
        .when()
        .get(BASE + "/artifacts")
        .then()
        .statusCode(200)
        .body(
            "find { it.name == '" + EVENTSTREAM + "' }.repository",
            equalTo("8f7e6d5c-0000-4000-8000-1a2b3c4d5e6f"));
  }

  @Test
  void aRepositoryThatHasReleasedNothingAnswersWithAnEmptyListRatherThanAFourOhFour() {
    given()
        .when()
        .get(BASE + "/repositories/nothing-like-this/dependents")
        .then()
        .statusCode(200)
        .body("artifacts", empty());
  }

  // --- the repository detail: pins beside transitives ----------------------------------------------

  /**
   * <b>THE PRINCIPLE, ON THE PAGE THAT SHOWS BOTH.</b> A pin is what the manifest edits and carries
   * a location and a group; a transitive is what the release contains and carries neither. The
   * detail serves both, in two arrays, and the {@code scope} field is what a client rendering them
   * in one table tells them apart by.
   */
  @Test
  void theDetailServesTheReleasesTransitivesBesideTheManifestsPins() {
    scan();
    // Something for the "behind" verdict to be about. Nothing pins postgres, so only the artifact
    // graph knows the platform ships it at all.
    store.recordLatest(
        Ecosystem.MAVEN, POSTGRES, LatestLookup.found("42.7.4", "http://stub"), Instant.now());
    theFixtureRelease();

    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        // Every pin says what it is, and it is always the same word: a manifest holds direct pins.
        .body("pins.find { it.name == '" + EVENTSTREAM + "' }.scope", equalTo("DIRECT"))
        .body("pins.findAll { it.scope != 'DIRECT' }", equalTo(List.of()))
        // The two transitives no manifest of this repository names.
        .body("transitives.name", hasItem(ANNOTATIONS))
        .body("transitives.name", hasItem(POSTGRES))
        .body("transitives.find { it.name == '" + ANNOTATIONS + "' }.version", equalTo("2.18.2"))
        .body("transitives.find { it.name == '" + ANNOTATIONS + "' }.ecosystem", equalTo("maven"))
        // `via` is the DIRECT component whose subtree pulled it in.
        .body("transitives.find { it.name == '" + ANNOTATIONS + "' }.via", equalTo(DATABIND))
        .body("transitives.find { it.name == '" + ANNOTATIONS + "' }.behind", equalTo(false))
        .body("transitives.find { it.name == '" + POSTGRES + "' }.via", equalTo(DATABIND))
        .body("transitives.find { it.name == '" + POSTGRES + "' }.behind", equalTo(true))
        // A DIRECT component is not a transitive, whether or not it is also a pin.
        .body("transitives.name", not(hasItem(DATABIND)))
        .body("transitives.name", not(hasItem(EVENTSTREAM)))
        // AND A TRANSITIVE THAT IS ALSO A PIN IS REMOVED: it is already on the page with a
        // version, a latest and a verdict, and repeating it here would say the opposite.
        .body("transitives.name", not(hasItem(QUARKUS_BOM)))
        .body("pins.find { it.name == '" + QUARKUS_BOM + "' }", notNullValue());
  }

  /**
   * A repository whose releases have no stored document has an empty section, and that reads as
   * "we do not know" rather than "there are none".
   */
  @Test
  void aRepositoryWithNoIngestedReleaseHasNoTransitivesAndStillHasItsPins() {
    scan();

    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        .body("transitives", empty())
        .body("pins.size()", org.hamcrest.Matchers.greaterThan(0));
  }

  // --- GET /dependencies?kind= --------------------------------------------------------------------

  @Test
  void theKindFilterIsServerSideBecauseTheTwoHalvesAreTwoPages() {
    scan();

    given()
        .when()
        .get(BASE + "/dependencies?kind=INTERNAL")
        .then()
        .statusCode(200)
        .body("name", hasItem(EVENTSTREAM))
        .body("name", not(hasItem(QUARKUS_BOM)))
        .body("name", not(hasItem("@angular/core")));

    given()
        .when()
        .get(BASE + "/dependencies?kind=EXTERNAL")
        .then()
        .statusCode(200)
        .body("name", hasItem(QUARKUS_BOM))
        .body("name", hasItem("@angular/core"))
        .body("name", not(hasItem(EVENTSTREAM)));

    // No filter is both halves, exactly as before.
    given()
        .when()
        .get(BASE + "/dependencies")
        .then()
        .statusCode(200)
        .body("name", hasItem(EVENTSTREAM))
        .body("name", hasItem(QUARKUS_BOM));
  }

  /** The glob and the kind compose, because the external page has a search box too. */
  @Test
  void theKindFilterComposesWithTheGlob() {
    scan();

    given()
        .when()
        .get(BASE + "/dependencies?name=io.quarkus.platform:*&kind=EXTERNAL")
        .then()
        .statusCode(200)
        .body("name", hasItem(QUARKUS_BOM))
        .body("size()", equalTo(1));

    given()
        .when()
        .get(BASE + "/dependencies?name=io.quarkus.platform:*&kind=INTERNAL")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
  }

  /**
   * REACTOR and UNRESOLVED are refused rather than answered with nothing: neither is a half of the
   * split this filter serves, and an empty list would read as "there are none of those".
   */
  @Test
  void aKindThatIsNotOneOfTheTwoHalvesIsRefused() {
    given()
        .when()
        .get(BASE + "/dependencies?kind=REACTOR")
        .then()
        .statusCode(400)
        .body("message", containsString("INTERNAL or EXTERNAL"));
    given()
        .when()
        .get(BASE + "/dependencies?kind=nonsense")
        .then()
        .statusCode(400)
        .body("message", containsString("INTERNAL or EXTERNAL"));
  }
}

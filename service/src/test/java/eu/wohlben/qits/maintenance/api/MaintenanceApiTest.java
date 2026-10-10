package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The REST boundary and the two flows behind it: a scan that fills the inventory, and a bump that
 * asks qits-ci to act on it.
 *
 * <p>The addresses are the shipped ones — the suite inherits {@code
 * quarkus.rest.path=/maintenance/api} from main's application.properties rather than re-declaring
 * it — so a change to the segment fails here rather than in a deployment.
 *
 * <p><b>No test sends an identity header</b>, and that is not a hole: qits-auth-core ships a {@code
 * %test} dev user carrying {@code qits:admin} and {@code qits:system}, so the shipped {@code
 * @RolesAllowed} pair is exercised rather than bypassed. That a real request must carry the pair is
 * pinned in {@code PackagedSurfaceIT}, where the identity contract is real.
 *
 * <p><b>Every poll is a fresh HTTP request and that is load-bearing.</b> A {@code @QuarkusTest}
 * holds ONE request context for the whole method, so a read made in the test thread would be
 * answered from the first session's cache and the row would look unchanged for ever while the
 * worker closed it in another session.
 */
@QuarkusTest
class MaintenanceApiTest {

  private static final String BASE = "/maintenance/api";

  /** What qits-projects names the request it opened, in every test that lets it answer. */
  private static final String RELEASE_REQUEST = "rr-0001";

  @Inject FakePeers peers;

  @Inject MaintenanceStore store;

  @Inject BumpService bumps;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void scriptThePeers() {
    // The class shares one database, and an active bump row holds its branch's lock — the next
    // test would be answered 409 by the last one's leftovers. Drain the worker first, or the row
    // being deleted is one a task still holds and the delete lands between its read and its write.
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    // Who publishes each internal pin: a bump's changelog ranges need a source repository (qits-893).
    Fixture.seedProducers(store);
    Fixture.scriptBranchAbsent(peers);
    // qits-projects answers the release ask by default, because the SUCCEEDED ending makes it: a
    // suite that left it unscripted would have every pushed branch record a refusal, and the tests
    // about the ask would be the only ones exercising the ordinary path.
    Fixture.scriptReleaseRequestAccepted(peers, RELEASE_REQUEST);
  }

  /** Queues a scan and waits for its row to close. */
  private String scan() {
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
    assertEquals("SUCCEEDED", awaitTerminal("/scans/" + id, "SUCCEEDED", "FAILED"));
    return id;
  }

  /** Polls one row the way the client does, until its status is one of the terminal ones. */
  private String awaitTerminal(String path, String... terminal) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      String status =
          given().when().get(BASE + path).then().statusCode(200).extract().path("status");
      for (String candidate : terminal) {
        if (candidate.equals(status)) {
          return status;
        }
      }
      sleep();
    }
    throw new AssertionError(path + " never reached a terminal status");
  }

  /** Polls until the field is set, which is how a test waits for a dispatch without a status. */
  private void awaitField(String path, String field) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      Object value = given().when().get(BASE + path).then().statusCode(200).extract().path(field);
      if (value != null) {
        return;
      }
      sleep();
    }
    throw new AssertionError(path + " never set " + field);
  }

  private static void sleep() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // --- the scan -------------------------------------------------------------------------------

  @Test
  void aScanIsAcceptedWithAnIdAndItsRowSaysWhatItCovered() {
    String id = scan();
    given()
        .when()
        .get(BASE + "/scans/" + id)
        .then()
        .statusCode(200)
        .body("scope", equalTo("ALL"))
        .body("repository", nullValue())
        .body("trigger", equalTo("MANUAL"))
        .body("status", equalTo("SUCCEEDED"))
        .body("startedAt", notNullValue())
        .body("finishedAt", notNullValue())
        .body("message", containsString("1 repositories"));
  }

  @Test
  void anUnknownScopeIsRefusedRatherThanTreatedAsEverything() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"scope\":\"SOMETIMES\"}")
        .when()
        .post(BASE + "/scans")
        .then()
        .statusCode(400)
        .body("message", containsString("INTERNAL, EXTERNAL or ALL"));
  }

  @Test
  void aScanFillsTheInventoryAndTheGroupingIsTheRepositorysOwn() {
    scan();
    given()
        .when()
        .get(BASE + "/repositories")
        .then()
        .statusCode(200)
        .body("name", hasItem(Fixture.REPOSITORY))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.status", equalTo("OK"))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.headSha", equalTo(Fixture.HEAD_SHA))
        // The repository declares `angular`; the two kind groups are appended after it, so no pin
        // is unclaimed and what the repository did not group still splits internal from external.
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups.name",
            equalTo(java.util.List.of("angular", "dependencies", "external")))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups[0].branch",
            equalTo("maintenance/angular"))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups[0].state", equalTo("NONE"))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups[2].branch",
            equalTo("maintenance/external"));
  }

  /**
   * THE SPLIT, ON THE ROUTE THAT SERVES IT. A group says HOW it claims — by kind, or by the globs
   * the repository wrote — and that is a different question from whether the repository asked for
   * the grouping at all, which is what `source` answers.
   */
  @Test
  void aGroupSaysWhetherItClaimsByKindOrByTheRepositorysOwnGlobs() {
    scan();
    String repository = BASE + "/repositories/" + Fixture.REPOSITORY;
    given()
        .when()
        .get(repository)
        .then()
        .statusCode(200)
        .body("groups.find { it.name == 'angular' }.kind", nullValue())
        .body("groups.find { it.name == 'angular' }.source", equalTo("CONFIG"))
        .body("groups.find { it.name == 'dependencies' }.kind", equalTo("INTERNAL"))
        .body("groups.find { it.name == 'external' }.kind", equalTo("EXTERNAL"))
        .body("groups.find { it.name == 'external' }.branch", equalTo("maintenance/external"))
        .body("groups.find { it.name == 'external' }.state", equalTo("NONE"))
        // The internal half claims the five internal pins that are behind; the external half claims
        // the quarkus BOM, and @angular/core went to the group this repository declared for it.
        .body("groups.find { it.name == 'dependencies' }.pending", equalTo(5))
        .body("groups.find { it.name == 'external' }.pending", equalTo(1))
        .body("groups.find { it.name == 'angular' }.pending", equalTo(1))
        // …and a pin names the group whose branch would carry it.
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }.group", equalTo("external"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.group",
            equalTo("dependencies"))
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.group", equalTo("dependencies"))
        .body("pins.find { it.name == '@qits/ui-components' }.group", equalTo("dependencies"));
  }

  @Test
  void anExternalBumpIsItsOwnBranchAndCarriesNoInternalChange() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-external");
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/external/bumps")
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    awaitField("/bumps/" + id, "ciRunId");

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .statusCode(200)
        .body("group", equalTo("external"))
        .body("branch", equalTo("maintenance/external"))
        .body("changes.size()", equalTo(1))
        .body("changes[0].name", equalTo("io.quarkus.platform:quarkus-bom"));

    String payload = peers.bodiesFor("/ci/api/events/trigger").get(0);
    assertTrue(payload.contains("\"branch\":\"maintenance/external\""), payload);
    // Nothing of ours rides along on somebody else's branch.
    assertTrue(!payload.contains("eu.wohlben.qits"), payload);
    assertTrue(!payload.contains("qits/build-images"), payload);
  }

  /**
   * <b>THE CATALOG ANSWERS AN {@code id} BESIDE THE NAME, AND A SCAN KEEPS IT.</b> Nothing here is
   * addressed by it — every read this service makes stays name-addressed — but qits-ci's {@code
   * SoftwareRelease} names a repository by exactly that id, so this column is the only thing that
   * can read such a frame back as a name. Without it the whole dependency graph joins a uuid to a
   * name and answers nothing, silently.
   */
  @Test
  void aScanKeepsTheCatalogRowsOwnIdSoAReleaseFrameCanBeReadBackAsAName() {
    scan();

    assertEquals(
        "r1",
        store.repository(Fixture.REPOSITORY).orElseThrow().catalogId,
        "the catalog's `id` field, as qits-projects' repository listing answers it");
    assertEquals(Fixture.REPOSITORY, store.repositoryName("r1"));
  }

  @Test
  void aCatalogRowWithNoNameHasNoAddressAndIsSkipped() {
    scan();
    given().when().get(BASE + "/repositories").then().statusCode(200).body("name", not(hasItem(nullValue())));
  }

  @Test
  void everyEcosystemIsParsedAndTheLocationIsWhereTheVersionIsSet() {
    scan();
    String repository = BASE + "/repositories/" + Fixture.REPOSITORY;
    given()
        .when()
        .get(repository)
        .then()
        .statusCode(200)
        .body("find { it.name == null }", nullValue())
        // maven, through a property
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.version", equalTo("2026.811.1"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.location",
            equalTo("property:qits.eventstream.version"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.kind", equalTo("INTERNAL"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.latest", equalTo("2026.821.3"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pending", equalTo(true))
        // npm, resolved through the lock, with the range beside it
        .body("pins.find { it.name == '@angular/core' }.version", equalTo("21.0.4"))
        .body("pins.find { it.name == '@angular/core' }.range", equalTo("^21.0.0"))
        .body("pins.find { it.name == '@angular/core' }.kind", equalTo("EXTERNAL"))
        .body("pins.find { it.name == '@angular/core' }.group", equalTo("angular"))
        // docker, by line number
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.version", equalTo("2026.813.1"))
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.location", equalTo("line:2"))
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.latest", equalTo("2026.821.2"));
  }

  /**
   * THE FOUR SHAPES THE FIRST LIVE SCAN GOT WRONG, on one repository.
   *
   * <p>Nineteen pins of qits-ci came back with `${project.groupId}:qits-arch-rules` classified
   * EXTERNAL, sibling modules offered as upgrades, and the repository's own root pom listed as a
   * parent to bump — after the scan itself had already died turning the first of those into a URL.
   */
  @Test
  void anExpressionInTheGroupIdIsResolvedAndTheArtifactIsInternal() {
    scan();
    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        // Written as ${project.groupId}:qits-arch-rules in service/pom.xml.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-arch-rules' }.kind", equalTo("INTERNAL"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-arch-rules' }.version",
            equalTo("2026.817.175344"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-arch-rules' }.latest",
            equalTo("2026.822.1"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-arch-rules' }.pending", equalTo(true))
        // The version IS a declared property, so it keeps an editable location.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-arch-rules' }.location",
            equalTo("property:qits.arch-rules.version"))
        // Both halves of the BOM's coordinate were properties too.
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }.kind", equalTo("EXTERNAL"))
        // Nothing anywhere still carries an expression in its name.
        .body("pins.findAll { it.name.contains('$') }", equalTo(java.util.List.of()));
  }

  @Test
  void aSiblingModuleIsTheRepositorysOwnAndIsNeverPending() {
    scan();
    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        // Pinned at ${project.version}: it moves with this repository's own release train.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-ci-domain' }.kind", equalTo("REACTOR"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-ci-domain' }.version", equalTo("2026.821.1"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-ci-domain' }.latest", nullValue())
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-ci-domain' }.pending", equalTo(false));
  }

  @Test
  void theRepositorysOwnRootPomIsNotADependencyButAnOutsideParentIs() {
    scan();
    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        // service/pom.xml inherits eu.wohlben.qits:qits-ci — the reactor's shape, not a dependency.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-ci' }", nullValue())
        // The ROOT's parent comes from the registry, so it stays a pin and can be bumped.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-parent' }.location",
            equalTo("parent:eu.wohlben.qits:qits-parent"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-parent' }.kind", equalTo("INTERNAL"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-parent' }.pending", equalTo(true));
  }

  @Test
  void anExpressionNobodyDeclaredIsVisibleAndIsNeverAskedAboutOrBumped() {
    scan();
    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        .body("pins.find { it.name == 'g:mystery' }.kind", equalTo("UNRESOLVED"))
        .body("pins.find { it.name == 'g:mystery' }.version", equalTo("${nobody.declared.this}"))
        .body("pins.find { it.name == 'g:mystery' }.latest", nullValue())
        .body("pins.find { it.name == 'g:mystery' }.pending", equalTo(false));
    // And no request was ever made for it — the scan that died was building exactly such a URL.
    assertTrue(
        peers.calls.stream().noneMatch(call -> call.url().contains("${")),
        "no url may carry an unresolved expression");
    assertTrue(
        peers.calls.stream().noneMatch(call -> call.url().contains("mystery")),
        "an unresolved pin must not be looked up at all");
  }

  @Test
  void anExternalBaseImageIsRecordedAndNeverLookedUp() {
    scan();
    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        // The fixture writes `FROM mirror.dev.localhost:8080/quay/…`, and the name recorded is the
        // image without the registry it was reached through — an address is not part of a name.
        .body(
            "pins.find { it.name == 'quay/quarkus/ubi9-quarkus-mandrel-builder-image' }.kind",
            equalTo("EXTERNAL"))
        .body(
            "pins.find { it.name == 'quay/quarkus/ubi9-quarkus-mandrel-builder-image' }.latest",
            nullValue());
  }

  @Test
  void aPrereleaseIsNotOfferedToAReleasedPin() {
    // The maven metadata carries 3.35.0.CR1 and the mirror's highest RELEASE is 3.34.6, which is
    // what the pin is offered.
    scan();
    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }.latest", equalTo("3.34.6"))
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }.pending", equalTo(true));
  }

  @Test
  void whoPinsThisDependencyIsAnswerableAcrossTheWholeInventory() {
    scan();
    given()
        .when()
        .get(BASE + "/dependencies?name=eu.wohlben.qits:*")
        .then()
        .statusCode(200)
        .body("name", hasItem("eu.wohlben.qits:qits-eventstream"))
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.latest", equalTo("2026.821.3"))
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pins[0].repository",
            equalTo(Fixture.REPOSITORY))
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pins[0].manifestPath",
            equalTo("pom.xml"))
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pins[0].pending", equalTo(true));
  }

  // --- the pin source the artifact GC reads -----------------------------------------------------

  /**
   * <b>THE KEEP-SET, AND WHAT IS DELIBERATELY NOT IN IT.</b> Every internal maven, npm and docker
   * pin the inventory holds, each naming the repository and the manifest that wrote it — and none of
   * the four kinds of row a garbage collector could not use: somebody else's package, this
   * repository's own module, an expression that never became a version, and a gitlink, whose version
   * is a commit sha that no registry has ever heard of.
   */
  @Test
  void thePinSourceAnswersEveryInternalRegistryPinAndNothingTheGcCouldNotUse() {
    scan();
    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("generatedAt", notNullValue())
        // The inventory's freshness travels with the answer: the consumer decides what a stale or
        // unreadable repository is worth, and it cannot without these three fields.
        .body("repositories.find { it.name == '" + Fixture.REPOSITORY + "' }.status", equalTo("OK"))
        .body(
            "repositories.find { it.name == '" + Fixture.REPOSITORY + "' }.lastScanAt",
            notNullValue())
        .body(
            "repositories.find { it.name == '" + Fixture.REPOSITORY + "' }.headSha",
            equalTo(Fixture.HEAD_SHA))
        // maven, npm and docker, each carrying its repository and the manifest that pins it.
        .body(
            "pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.ecosystem",
            equalTo("maven"))
        .body(
            "pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.version",
            equalTo("2026.811.1"))
        .body(
            "pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.repository",
            equalTo(Fixture.REPOSITORY))
        .body(
            "pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.manifestPath",
            equalTo("pom.xml"))
        .body("pins.find { it.name == '@qits/ui-components' }.ecosystem", equalTo("npm"))
        // The LOCK's resolved version, because that is what an install fetches out of the registry.
        .body("pins.find { it.name == '@qits/ui-components' }.version", equalTo("2026.8.1"))
        .body("pins.find { it.name == '@qits/ui-components' }.manifestPath", equalTo("package.json"))
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.ecosystem", equalTo("docker"))
        .body(
            "pins.find { it.name == 'qits/build-images/maven-base' }.version", equalTo("2026.813.1"))
        .body(
            "pins.find { it.name == 'qits/build-images/maven-base' }.manifestPath",
            equalTo("Dockerfile"))
        // EXTERNAL: somebody else's, and not this registry's to keep.
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }", nullValue())
        .body("pins.find { it.name == '@angular/core' }", nullValue())
        .body(
            "pins.find { it.name == 'quay/quarkus/ubi9-quarkus-mandrel-builder-image' }",
            nullValue())
        // REACTOR and UNRESOLVED: a version that moves with a release, and one that is not a version.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-ci-domain' }", nullValue())
        .body("pins.find { it.name == 'g:mystery' }", nullValue())
        // GITLINK: internal by construction, and a commit sha rather than a registry coordinate.
        .body("pins.find { it.name == 'qits-ci-frontend' }", nullValue())
        .body("pins.findAll { it.ecosystem == 'gitlink' }", equalTo(java.util.List.of()))
        .body("pins.findAll { it.version == '" + Fixture.GITLINK_SHA + "' }",
            equalTo(java.util.List.of()));

    // …and the gitlink really is in the inventory, so the absence above is a filter rather than a
    // fixture that never produced one.
    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        .body("pins.find { it.name == 'qits-ci-frontend' }.ecosystem", equalTo("gitlink"))
        .body("pins.find { it.name == 'qits-ci-frontend' }.kind", equalTo("INTERNAL"))
        .body("pins.find { it.name == 'qits-ci-frontend' }.version", equalTo(Fixture.GITLINK_SHA));
  }

  /**
   * THE REFUSAL, AND IT IS THE POINT OF THE ROUTE HAVING ONE. The consumer is fail-closed on a
   * source it could not read and treats an answer as authoritative — so an inventory that has never
   * been filled must not answer "nothing is referenced", which is the sentence that would collect
   * every internal library on the platform.
   */
  @Test
  void anInventoryThatWasNeverFilledRefusesRatherThanAnsweringAnEmptyKeepSet() {
    // No scan: the reset in @BeforeEach left the store with no repository row at all.
    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(503)
        .contentType(ContentType.JSON)
        .body("message", containsString("no repository"));
  }

  /**
   * A TOTAL ORDER, so a consumer diffing two runs sees a change in the platform rather than in a
   * query plan. Everything but the read moment is identical between two calls over one store.
   */
  @Test
  void twoReadsOfAnUnchangedStoreAnswerTheSamePinsInTheSameOrder() {
    scan();
    List<java.util.Map<String, Object>> first =
        given().when().get(BASE + "/pins").then().statusCode(200).extract().path("pins");
    List<java.util.Map<String, Object>> second =
        given().when().get(BASE + "/pins").then().statusCode(200).extract().path("pins");
    assertFalse(first.isEmpty(), "the scan must have left something to order");
    assertEquals(first, second, "the pins are served in one order, element for element");

    // And the order is the documented one — ecosystem, name, version, repository, manifest — rather
    // than whatever the store happened to answer twice.
    List<String> keys =
        first.stream()
            .map(
                pin ->
                    String.join(
                        " ",
                        String.valueOf(pin.get("ecosystem")),
                        String.valueOf(pin.get("name")),
                        String.valueOf(pin.get("version")),
                        String.valueOf(pin.get("repository")),
                        String.valueOf(pin.get("manifestPath"))))
            .toList();
    assertEquals(keys.stream().sorted().toList(), keys, "the pin source is served in a total order");
  }

  @Test
  void anUnknownRepositoryIsAFourOhFourWithTheMessageEnvelope() {
    given()
        .when()
        .get(BASE + "/repositories/nothing-like-this")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", containsString("nothing-like-this"));
  }

  // --- the bump -------------------------------------------------------------------------------

  /** Requests a bump of the catch-all group and waits until qits-ci has been told. */
  private String requestBump() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    awaitField("/bumps/" + id, "ciRunId");
    return id;
  }

  @Test
  void aBumpSendsExactlyThePendingChangesOfItsGroup() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-1");
    String id = requestBump();

    String payload = peers.bodiesFor("/ci/api/events/trigger").get(0);
    assertTrue(payload.contains("\"name\":\"MaintenanceBump\""), payload);
    assertTrue(payload.contains("\"eventId\":\"" + id + "\""), payload);
    assertTrue(payload.contains("\"repository\":\"" + Fixture.REPOSITORY + "\""), payload);
    assertTrue(payload.contains("\"branch\":\"" + Fixture.BRANCH + "\""), payload);
    assertTrue(payload.contains("\"baseRef\":\"main\""), payload);
    assertTrue(payload.contains("\"location\":\"property:qits.eventstream.version\""), payload);
    assertTrue(payload.contains("\"from\":\"2026.811.1\""), payload);
    assertTrue(payload.contains("\"to\":\"2026.821.3\""), payload);
    // @angular/core belongs to the `angular` group and must not ride along on this branch.
    assertTrue(!payload.contains("@angular/core"), payload);
  }

  @Test
  void aGreenRunThatMovedTheBranchSucceedsAndRecordsTheHead() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-1");
    String id = requestBump();

    // The run ends and the branch moved — the step wrote something.
    Fixture.scriptRun(peers, "run-1", "SUCCESS");
    Fixture.scriptBranchAt(peers, Fixture.BUMPED_SHA);
    bumps.sweep();

    assertEquals("SUCCEEDED", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .statusCode(200)
        .body("group", equalTo("dependencies"))
        .body("branch", equalTo(Fixture.BRANCH))
        .body("environment", equalTo("dev"))
        .body("trigger", equalTo("MANUAL"))
        .body("ciRunId", equalTo("run-1"))
        .body("ciRunStatus", equalTo("SUCCESS"))
        .body("configPath", containsString("platform-pipelines/maintenance-bump.yml"))
        // The `dependencies` group is the INTERNAL half: the eventstream property, the outside
        // parent, qits-arch-rules, the @qits npm package and the internal build image. The quarkus
        // BOM is external and rides on its own branch; @angular/core belongs to `angular`; and the
        // sibling module, the unresolved expression and the external base image are pins nothing
        // can bump at all.
        .body("changes.size()", equalTo(5))
        .body("finishedAt", notNullValue());

    given()
        .when()
        .get(BASE + "/repositories")
        .then()
        .statusCode(200)
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups.find { it.name == 'dependencies' }.state",
            equalTo("PUSHED"))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups.find { it.name == 'dependencies' }.headSha",
            equalTo(Fixture.BUMPED_SHA));
  }

  @Test
  void aGreenRunThatDidNotMoveTheBranchIsNothingToDoRatherThanSuccess() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-2");
    String id = requestBump();

    Fixture.scriptRun(peers, "run-2", "SUCCESS");
    // The branch is still absent: the step found the versions already there and wrote nothing.
    bumps.sweep();

    assertEquals(
        "NOTHING_TO_DO", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("message", containsString("did not move"));
  }

  @Test
  void aRedRunAgainstAMovedBranchMeansSomebodyRewroteItByHand() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-3");
    String id = requestBump();

    // The ff-only push was rejected — and the branch moved anyway, which is a person's commit.
    Fixture.scriptRun(peers, "run-3", "FAILED");
    Fixture.scriptBranchAt(peers, Fixture.BUMPED_SHA);
    bumps.sweep();

    assertEquals("FAILED", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    given().when().get(BASE + "/bumps/" + id).then().body("message", containsString("qits maintenance did not write"));
    given()
        .when()
        .get(BASE + "/repositories")
        .then()
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups.find { it.name == 'dependencies' }.state",
            equalTo("STALE"));
  }

  @Test
  void aTriggerAnsweredWithNoRunIsAFailureBecauseNothingIsRunning() {
    scan();
    peers.answer(
        eu.wohlben.qits.maintenance.peer.PeerTarget.CI,
        "/ci/api/events/trigger",
        FakePeers.Scripted.ok(
            "{\"eventId\":\"e1\",\"runIds\":[],\"repositoriesRead\":0,\"repositoriesSkipped\":[]}"));

    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
            .then()
            .statusCode(202)
            .extract()
            .path("id");

    assertEquals("FAILED", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("message", containsString("no run recorded for MaintenanceBump"));
  }

  @Test
  void a503LeavesTheBumpRequestedAndTheNextSweepSendsItAgain() {
    scan();
    peers.answer(
        eu.wohlben.qits.maintenance.peer.PeerTarget.CI,
        "/ci/api/events/trigger",
        FakePeers.Scripted.status(503, "{\"runIds\":[]}"));

    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
            .then()
            .statusCode(202)
            .extract()
            .path("id");

    awaitField("/bumps/" + id, "message");
    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("status", equalTo("REQUESTED"))
        .body("finishedAt", nullValue())
        .body("changes.size()", equalTo(5));

    // qits-ci is back. The sweep sends the SAME payload under the SAME event id.
    Fixture.scriptCiAccepts(peers, "run-4");
    bumps.sweep();
    awaitField("/bumps/" + id, "ciRunId");
    assertEquals(
        2, peers.bodiesFor("/ci/api/events/trigger").size(), "the trigger should have been retried");
    assertTrue(peers.bodiesFor("/ci/api/events/trigger").get(1).contains("\"eventId\":\"" + id + "\""));
  }

  @Test
  void aSecondBumpOfOneBranchIsRefusedWhileTheFirstIsInFlight() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-5");
    CountDownLatch held = peers.hold();
    try {
      given()
          .contentType(ContentType.JSON)
          .body("{}")
          .when()
          .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
          .then()
          .statusCode(202);

      given()
          .contentType(ContentType.JSON)
          .body("{}")
          .when()
          .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
          .then()
          .statusCode(409)
          .body("message", containsString("already active"));
    } finally {
      peers.release();
      assertEquals(0, held.getCount());
    }
  }

  @Test
  void bumpingAGroupTheRepositoryDoesNotDeclareIsAFourOhFour() {
    scan();
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/invented/bumps")
        .then()
        .statusCode(404)
        .body("message", containsString("invented"));
  }

  @Test
  void theBumpListingCarriesTheChangesTooBecauseTheyAreSmall() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-6");
    String id = requestBump();
    given()
        .when()
        .get(BASE + "/bumps?limit=5")
        .then()
        .statusCode(200)
        .body("id", hasItem(id))
        .body("find { it.id == '" + id + "' }.changes.size()", equalTo(5));
    given()
        .when()
        .get(BASE + "/bumps?repository=" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        .body("id", hasItem(id));
  }

  // --- the release ask --------------------------------------------------------------------------

  /** Drives one bump to SUCCEEDED with the branch moved, which is the only ending that asks. */
  private String bumpToSucceeded(String runId) {
    scan();
    Fixture.scriptCiAccepts(peers, runId);
    String id = requestBump();
    Fixture.scriptRun(peers, runId, "SUCCESS");
    Fixture.scriptBranchAt(peers, Fixture.BUMPED_SHA);
    bumps.sweep();
    assertEquals("SUCCEEDED", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    // The status is written BEFORE the release is asked for, on the same worker task — so a test
    // that read the row the instant it turned SUCCEEDED would be racing the ask it is about to
    // assert on.
    queue.awaitIdle(Duration.ofSeconds(30));
    return id;
  }

  /** Polls the bump until its release ask has settled, whichever way. */
  private void awaitReleaseAsked(String id) {
    awaitField("/bumps/" + id, "releaseRequestId");
  }

  /**
   * THE HAND-OFF THIS SERVICE NOW MAKES ITSELF. A branch nobody asks about sits there; the bump that
   * pushed one opens a release request in qits-projects, addressed by the repository's CATALOG id —
   * qits-projects' own row id, which is the only thing that route resolves.
   */
  @Test
  void aPushedBranchOpensAReleaseRequestAddressedByTheCatalogId() {
    String id = bumpToSucceeded("run-door-1");
    awaitReleaseAsked(id);

    // The path itself is the assertion the id is right: FakePeers keys on target AND path, so an
    // ask addressed by the name or the project would have gone to an unscripted 404 instead.
    List<String> asks = peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH);
    assertEquals(1, asks.size(), "the release request is asked for exactly once per pushed branch");
    String ask = asks.get(0);
    assertTrue(ask.contains("\"branch\":\"" + Fixture.BRANCH + "\""), ask);
    // The subject shape the bump's own commits carry, so the request, the branch and the commits
    // all read the same in a listing.
    assertTrue(ask.contains("\"summary\":\"bump(dependencies): 5 dependencies\""), ask);
    // AND NOTHING ELSE. The door took an expectedSha to pin the ask to what was built; a release
    // request is re-folded and re-gated on every push to a named source, so the pin is replaced by
    // continuous re-gating and sending one would be a field nobody reads.
    assertFalse(ask.contains("expectedSha"), ask);
    assertFalse(ask.contains("projectId"), ask);

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("SUCCEEDED"))
        .body("releaseRequestId", equalTo(RELEASE_REQUEST))
        .body("message", containsString(RELEASE_REQUEST));
  }

  /**
   * THE FAILURE POLICY, AND IT IS THE POINT OF THE WHOLE DESIGN. The bump succeeded — a green run and
   * a branch that moved, both facts about this service's own work. A qits-projects that is not there
   * says nothing about either, so the status must not move; the ask is simply still owed, and the
   * sweep is what owes it.
   */
  @Test
  void aProjectsThatIsDownLeavesTheBumpSucceededAndTheNextSweepAsksAgain() {
    Fixture.scriptReleaseRequestUnreachable(peers);
    String id = bumpToSucceeded("run-door-2");
    awaitField("/bumps/" + id, "message");

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .statusCode(200)
        // The verdict is untouched, and so is the sentence the verdict wrote.
        .body("status", equalTo("SUCCEEDED"))
        .body("message", containsString("dependencies on " + Fixture.BRANCH))
        .body("message", containsString("next sweep asks again"))
        // NULL is what "work is owed" means, and it is what the sweep selects on.
        .body("releaseRequestId", nullValue());

    // qits-projects is back.
    Fixture.scriptReleaseRequestAccepted(peers, RELEASE_REQUEST);
    bumps.sweep();
    awaitReleaseAsked(id);
    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("status", equalTo("SUCCEEDED"))
        .body("releaseRequestId", equalTo(RELEASE_REQUEST));
    assertEquals(
        2,
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).size(),
        "qits-projects should have been asked again");
  }

  /**
   * A 2xx WITH NO ID IS SETTLED, NOT RETRIED. qits-projects answered, so nothing is owed by it;
   * asking again would get the same empty answer. The column carries the word that stops the
   * retrying, because leaving it null would have the sweep re-ask every fifteen seconds for ever.
   */
  @Test
  void anAnswerWithNoRequestIdIsConvergenceRatherThanAFailure() {
    Fixture.scriptReleaseRequestAnswers(peers, 200, "{\"request\":null}");
    String id = bumpToSucceeded("run-door-3");
    awaitReleaseAsked(id);

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("status", equalTo("SUCCEEDED"))
        .body("releaseRequestId", equalTo("converged"))
        .body("message", containsString("without an id"));

    bumps.sweep();
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(
        1,
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).size(),
        "a converged ask must not be re-sent by the sweep");
  }

  /**
   * A 4xx IS A REFUSAL A RETRY CANNOT FIX, and the sentinel is what stops the sweep. The next
   * nightly bump of the group opens a fresh row and asks again from scratch, which is the recovery —
   * not this row's sweep hammering a service that has already given its answer.
   */
  @Test
  void aRefusedAskStopsTheSweepAndSaysWhy() {
    Fixture.scriptReleaseRequestAnswers(
        peers, 400, "{\"message\":\"A release request carries a summary\"}");
    String id = bumpToSucceeded("run-door-6");
    awaitReleaseAsked(id);

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("status", equalTo("SUCCEEDED"))
        .body("releaseRequestId", equalTo("refused"))
        .body("message", containsString("HTTP 400"));

    bumps.sweep();
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(
        1,
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).size(),
        "a refused ask must not be re-sent by the sweep");
  }

  /**
   * A 403 IS RETRYABLE, and deliberately not a refusal: it is this service's credential not being
   * admitted, which is a deployment grant rather than anything a bump can fix — so the ask heals the
   * moment the grant lands, instead of needing the bump run again.
   */
  @Test
  void anAuthRefusalIsRetriedRatherThanRecorded() {
    Fixture.scriptReleaseRequestAnswers(peers, 403, "{\"message\":\"forbidden\"}");
    String id = bumpToSucceeded("run-door-7");
    awaitField("/bumps/" + id, "message");

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("status", equalTo("SUCCEEDED"))
        .body("releaseRequestId", nullValue())
        .body("message", containsString("would not admit this service"));
  }

  /** A green run over an unmoved branch pushed nothing, so there is nothing to release. */
  @Test
  void nothingToDoAsksForNoReleaseBecauseNoBranchWasPushed() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-door-4");
    String id = requestBump();
    Fixture.scriptRun(peers, "run-door-4", "SUCCESS");
    bumps.sweep();

    assertEquals(
        "NOTHING_TO_DO", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    queue.awaitIdle(Duration.ofSeconds(30));
    assertTrue(
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).isEmpty(),
        "no branch was pushed, so no ask");
    given().when().get(BASE + "/bumps/" + id).then().body("releaseRequestId", nullValue());
  }

  /**
   * A STALE branch is somebody's hand-written commit that the ff-only push refused to overwrite. They
   * own it now — and asking for THEIR commits to be released is precisely the thing this service must
   * never do on their behalf.
   */
  @Test
  void aStaleBranchAsksForNoReleaseBecauseSomebodyElseOwnsIt() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-door-5");
    String id = requestBump();
    Fixture.scriptRun(peers, "run-door-5", "FAILED");
    Fixture.scriptBranchAt(peers, Fixture.BUMPED_SHA);
    bumps.sweep();

    assertEquals("FAILED", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    queue.awaitIdle(Duration.ofSeconds(30));
    assertTrue(
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).isEmpty(),
        "a branch somebody rewrote by hand is not this service's to release");
  }

  /**
   * <b>The window has a door, and the door is the reason the row is worth having.</b> Before it, the
   * only way to make the estate bump outside 02:00 was to override the cron in the deployment config
   * and redeploy — which restarts the very service whose window you are opening.
   *
   * <p>The three verbs in one method because they are one fact's lifecycle: none, opened, none
   * again. The DELETE is asserted twice on purpose — closing a window that is not there is the
   * caller asking for a state, not for a transition.
   *
   * <p><b>The GET is 200 in all three states, and the 404 it used to give with no window open was a
   * real cost.</b> The question this door is opened with is "why has nothing been dispatched", and
   * answering it with "there is no window" said only that the first gate was shut — while the bump
   * listing beside it holds only bumps that were dispatched. Fifteen repositories owed since the
   * morning and a dead scheduler were the same picture from every surface. With no window the two
   * timestamps are null and the reasoning is answered anyway.
   */
  @Test
  void theDispatchWindowIsOpenedClosedAndReadThroughItsOwnDoor() {
    given().when().delete(BASE + "/bumps/window").then().statusCode(204);
    given()
        .when()
        .get(BASE + "/bumps/window")
        .then()
        .statusCode(200)
        .body("open", equalTo(false))
        .body("openedAt", nullValue())
        .body("closesAt", nullValue())
        .body("outcome", notNullValue())
        .body("summary", notNullValue());

    given()
        .when()
        .post(BASE + "/bumps/window")
        .then()
        .statusCode(200)
        .body("open", equalTo(true))
        .body("openedAt", notNullValue())
        .body("closesAt", notNullValue());
    given().when().get(BASE + "/bumps/window").then().statusCode(200).body("open", equalTo(true));

    given().when().delete(BASE + "/bumps/window").then().statusCode(204);
    given().when().delete(BASE + "/bumps/window").then().statusCode(204);
    given()
        .when()
        .get(BASE + "/bumps/window")
        .then()
        .statusCode(200)
        .body("open", equalTo(false));
  }

  @Test
  void anIdThatIsNotAUuidIsAnUnknownRowRatherThanAnError() {
    given().when().get(BASE + "/bumps/not-a-uuid").then().statusCode(404).body("message", notNullValue());
    given().when().get(BASE + "/scans/not-a-uuid").then().statusCode(404).body("message", notNullValue());
  }

  /**
   * <b>THE TWO RETIRED DOORS (qits-1006).</b> {@code /branches/bumps} and {@code
   * /release-requests/{id}/screenshot-baselines} delegated to the {@code estate-pins} and {@code
   * screenshot-baselines} release-request automations; both are a plain 404 now — no route answers
   * either path at all, with or without a repository the inventory holds. The re-run door, {@code
   * POST /release-requests/{id}/automations/{kind}/runs}, is the one address left for either.
   */
  @Test
  void theRetiredBranchesBumpsAndScreenshotBaselinesDoorsAnswer404() {
    scan();
    given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"workspace/ws-7\",\"changes\":[]}")
        .when()
        .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/branches/bumps")
        .then()
        .statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(
            BASE + "/repositories/" + Fixture.REPOSITORY
                + "/release-requests/0d0a15ac-5e67-4e03-ad00-ff25d9bbcfea/screenshot-baselines")
        .then()
        .statusCode(404);
  }
}

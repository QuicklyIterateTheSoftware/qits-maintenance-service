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
import org.junit.jupiter.api.AfterEach;
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

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @AfterEach
  void drain() {
    queue.awaitIdle(Duration.ofSeconds(30));
  }

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

  /**
   * The fixture's {@code maintenance.yml} still declares {@code groups:} — an {@code angular} group
   * from before qits-1133 R5. It is IGNORED, not refused: the repository scans OK and every pin
   * splits into the two kind groups.
   */
  @Test
  void aScanFillsTheInventoryAndARetiredGroupsKeyIsIgnored() {
    scan();
    given()
        .when()
        .get(BASE + "/repositories")
        .then()
        .statusCode(200)
        .body("name", hasItem(Fixture.REPOSITORY))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.status", equalTo("OK"))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.headSha", equalTo(Fixture.HEAD_SHA))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups.name",
            equalTo(java.util.List.of("dependencies", "external")))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups[0].state", equalTo("NONE"))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups[1].branch",
            equalTo("maintenance/external"));
  }

  /** THE SPLIT, ON THE ROUTE THAT SERVES IT: each group says which kind it claims. */
  @Test
  void aGroupSaysWhichKindItClaims() {
    scan();
    String repository = BASE + "/repositories/" + Fixture.REPOSITORY;
    given()
        .when()
        .get(repository)
        .then()
        .statusCode(200)
        .body("groups.find { it.name == 'angular' }", nullValue())
        .body("groups.find { it.name == 'dependencies' }.source", equalTo("DEFAULT"))
        .body("groups.find { it.name == 'dependencies' }.kind", equalTo("INTERNAL"))
        .body("groups.find { it.name == 'external' }.kind", equalTo("EXTERNAL"))
        .body("groups.find { it.name == 'external' }.branch", equalTo("maintenance/external"))
        .body("groups.find { it.name == 'external' }.state", equalTo("NONE"))
        // The internal half claims the five internal pins that are behind; the external half claims
        // the quarkus BOM and @angular/core.
        .body("groups.find { it.name == 'dependencies' }.pending", equalTo(5))
        .body("groups.find { it.name == 'external' }.pending", equalTo(2))
        // …and a pin names the group that claims it.
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }.group", equalTo("external"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.group",
            equalTo("dependencies"))
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.group", equalTo("dependencies"))
        .body("pins.find { it.name == '@qits/ui-components' }.group", equalTo("dependencies"));
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
        .body("pins.find { it.name == '@angular/core' }.group", equalTo("external"))
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

  // --- the bump log -----------------------------------------------------------------------------

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

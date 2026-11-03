package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two routes that replaced the three train ones, over a store seeded through the domain.
 *
 * <p><b>{@code /repositories/{name}/downstream} is a WIRE CONTRACT and is asserted field by
 * field.</b> qits-projects reads it on its release-request announce path and folds the names into
 * {@code ReleaseRequestChanged}; qits-ci orders its build queue by what comes out. So the shape here
 * — the top-level {@code repository} and {@code catalogId}, and {@code repository}, {@code
 * catalogId}, {@code archetype}, {@code depth}, {@code via} on every entry — is pinned rather than
 * sampled, and so is the ORDER, because "upstream first" is the whole information a queue takes from
 * it.
 *
 * <p>No test sends an identity header: qits-auth-core ships a {@code %test} dev user carrying both
 * roles, so the shipped {@code @RolesAllowed} pair is exercised rather than bypassed. That a real
 * request must carry one is {@code PackagedSurfaceIT}'s and {@code MaintenanceRefusalIT}'s subject,
 * where the identity contract is real.
 */
@QuarkusTest
class AdoptionApiTest {

  private static final String BASE = "/maintenance/api";
  private static final String ADOPTION = BASE + "/adoption/by-release";

  private static final String LIBRARY = "qits-ui-components-jslib";
  private static final String LIBRARY_ID = "r-ui-components";
  private static final String LIBRARY_PACKAGE = "@qits/ui-components";
  private static final String VERSION = "2026.905.1";

  private static final String FRONTEND = "qits-ci-frontend";
  private static final String FRONTEND_ID = "r-ci-frontend";
  private static final String FRONTEND_PACKAGE = "@qits/ci-spa";
  private static final String FRONTEND_VERSION = "2026.905.2";

  private static final String SERVICE = "qits-ci-service";
  private static final String SERVICE_ID = "r-ci-service";

  /** The wrapper, which gitlink-pins all three and must be on none of it. */
  private static final String WRAPPER = "qits-qits";

  private static final Instant WHEN = Instant.parse("2026-09-05T09:00:00Z");
  private static final Instant LATER = Instant.parse("2026-09-06T09:00:00Z");

  @Inject MaintenanceStore store;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void anEmptyEstate() {
    // The class shares a database with every other @QuarkusTest here; drain first, so a task still
    // running from the previous class does not write into a store this one just emptied.
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
  }

  // --- the fixture ------------------------------------------------------------------------------

  private void scanned(
      String repository, String catalogId, RepositoryArchetype archetype, ParsedPin... pins) {
    store.replaceInventory(
        repository,
        "qits",
        catalogId,
        archetype == null ? null : archetype.name(),
        "main",
        RepositoryStatus.OK,
        "sha",
        null,
        List.of(pins),
        List.of(),
        GroupSource.DEFAULT,
        pin -> PinKind.INTERNAL,
        WHEN);
  }

  private static ParsedPin npm(String name) {
    return ParsedPin.of(Ecosystem.NPM, "package.json", name, "0.0.1", null, "dependency:" + name);
  }

  private static ParsedPin gitlink(String repository) {
    return ParsedPin.of(
        Ecosystem.GITLINK,
        ".gitmodules",
        repository,
        "c0ffee11d00d2233445566778899aabbccddeeff",
        null,
        "gitlink:service/src/main/webui");
  }

  private void released(
      String repository,
      Ecosystem ecosystem,
      String name,
      String version,
      Instant when,
      ParsedSbom.Component... components) {
    UUID id = store.upsertArtifact(ecosystem, name, version, repository, when);
    if (components.length > 0) {
      store.replaceGraph(id, List.of(components), List.of(), Instant.now());
    }
  }

  /** The library, the frontend that pins its package, the service that submodules the frontend. */
  private void theChain() {
    scanned(LIBRARY, LIBRARY_ID, RepositoryArchetype.LIBRARY);
    released(LIBRARY, Ecosystem.NPM, LIBRARY_PACKAGE, VERSION, WHEN);
    scanned(FRONTEND, FRONTEND_ID, RepositoryArchetype.FRONTEND, npm(LIBRARY_PACKAGE));
    scanned(SERVICE, SERVICE_ID, RepositoryArchetype.SERVICE, gitlink(FRONTEND));
    scanned(
        WRAPPER, "r-qits", RepositoryArchetype.PROJECT,
        gitlink(LIBRARY), gitlink(FRONTEND), gitlink(SERVICE));
  }

  /** …and the frontend's own release, which is what carries the library into the service. */
  private void theFrontendShippedIt() {
    released(
        FRONTEND,
        Ecosystem.NPM,
        FRONTEND_PACKAGE,
        FRONTEND_VERSION,
        LATER,
        new ParsedSbom.Component(
            "c-1", "pkg:npm/x@" + VERSION, Ecosystem.NPM, LIBRARY_PACKAGE, VERSION, true));
  }

  // --- the closure, which is Contract A ----------------------------------------------------------

  @Test
  void theClosureCarriesEveryFieldTheContractNamesInUpstreamFirstOrder() {
    theChain();

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(BASE + "/repositories/" + LIBRARY + "/downstream")
        .then()
        .statusCode(200)
        .body("repository", equalTo(LIBRARY))
        .body("catalogId", equalTo(LIBRARY_ID))
        .body("downstream.size()", equalTo(2))
        .body("downstream.repository", contains(FRONTEND, SERVICE))
        .body("downstream[0].catalogId", equalTo(FRONTEND_ID))
        .body("downstream[0].archetype", equalTo("FRONTEND"))
        .body("downstream[0].depth", equalTo(1))
        .body("downstream[0].via", contains(LIBRARY))
        // THE HOP THE RELEASE TRAINS COULD NOT MAKE: the service carries the frontend as a
        // submodule, and a submodule is not a released coordinate.
        .body("downstream[1].catalogId", equalTo(SERVICE_ID))
        .body("downstream[1].archetype", equalTo("SERVICE"))
        .body("downstream[1].depth", equalTo(2))
        .body("downstream[1].via", contains(FRONTEND))
        .body("downstream.repository", everyItem(not(equalTo(WRAPPER))));
  }

  @Test
  void theClosureTakesTheCatalogIdSpellingTheAnnouncePathHolds() {
    theChain();

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(BASE + "/repositories/" + LIBRARY_ID + "/downstream")
        .then()
        .statusCode(200)
        // Asked by id, answered by name — the inventory's own key, whichever spelling came in.
        .body("repository", equalTo(LIBRARY))
        .body("downstream.repository", contains(FRONTEND, SERVICE));
  }

  /**
   * <b>No 404, and here that is load-bearing rather than tidy.</b> The caller is an announce path: a
   * repository this service has never scanned must cost it an empty list, never a refusal it has to
   * classify.
   */
  @Test
  void anUnknownRepositoryIsAnEmptyClosureRatherThanARefusal() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(BASE + "/repositories/qits-never-heard-of/downstream")
        .then()
        .statusCode(200)
        .body("repository", equalTo("qits-never-heard-of"))
        .body("catalogId", nullValue())
        .body("downstream", empty());
  }

  // --- the journey -------------------------------------------------------------------------------

  @Test
  void theJourneyNamesWhatWasReleasedAndWhoIsCarryingIt() {
    theChain();
    theFrontendShippedIt();

    given()
        .header("Authorization", SuiteBearers.operator())
        .queryParam("repository", LIBRARY)
        .queryParam("version", VERSION)
        .when()
        .get(ADOPTION)
        .then()
        .statusCode(200)
        .body("repository", equalTo(LIBRARY))
        .body("catalogId", equalTo(LIBRARY_ID))
        .body("version", equalTo(VERSION))
        .body("packages.size()", equalTo(1))
        .body("packages[0].ecosystem", equalTo("npm"))
        .body("packages[0].name", equalTo(LIBRARY_PACKAGE))
        .body("adopters.repository", contains(FRONTEND, SERVICE))
        .body("adopters[0].state", equalTo("ADOPTED"))
        // THE ADOPTER'S OWN RELEASE, not the library version it took: that value is half of the
        // address of the release request the adoption opened.
        .body("adopters[0].adoptedVersion", equalTo(FRONTEND_VERSION))
        // The wire form of an Instant, asserted rather than assumed: a numeric epoch would render
        // as 1970 in a client with nothing failing on this side.
        .body("adopters[0].adoptedAt", equalTo("2026-09-06T09:00:00Z"))
        .body("adopters[0].repositoryStatus", equalTo("OK"))
        .body("adopters[0].catalogId", equalTo(FRONTEND_ID))
        .body("adopters[0].depth", equalTo(1))
        .body("adopters[0].via", contains(LIBRARY))
        // The service carries a frontend that shipped it, and has not shipped that frontend.
        .body("adopters[1].state", equalTo("PENDING"))
        .body("adopters[1].adoptedVersion", nullValue())
        .body("adopters[1].adoptedAt", nullValue())
        .body("adopters[1].depth", equalTo(2))
        .body("adopters[1].via", contains(FRONTEND));
  }

  @Test
  void theJourneyTakesTheCatalogIdSpellingTheReleaseRequestPageHolds() {
    theChain();
    theFrontendShippedIt();

    given()
        .header("Authorization", SuiteBearers.operator())
        .queryParam("repository", LIBRARY_ID)
        .queryParam("version", VERSION)
        .when()
        .get(ADOPTION)
        .then()
        .statusCode(200)
        .body("repository", equalTo(LIBRARY))
        .body("adopters.repository", contains(FRONTEND, SERVICE));
  }

  /**
   * <b>THE 404 IS GONE, and its absence is the change from the trains.</b> {@code
   * /trains/by-release} answered 404 for a release that had opened no station, which was a fact
   * about the log rather than about the release. Nothing is logged now: an unknown release published
   * no coordinate anybody could be carrying, so the closure is real and every row of it is PENDING.
   */
  @Test
  void aReleaseThisServiceNeverHeardOfIsAnAnswerRatherThanA404() {
    theChain();

    given()
        .header("Authorization", SuiteBearers.operator())
        .queryParam("repository", LIBRARY)
        .queryParam("version", "2026.999.1")
        .when()
        .get(ADOPTION)
        .then()
        .statusCode(200)
        .body("version", equalTo("2026.999.1"))
        .body("packages", empty())
        .body("adopters.repository", contains(FRONTEND, SERVICE))
        .body("adopters.state", everyItem(equalTo("PENDING")));
  }

  /** And a repository nothing has scanned answers the same way, for the same reason. */
  @Test
  void anUnknownRepositoryIsAnAnswerToo() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .queryParam("repository", "qits-never-heard-of")
        .queryParam("version", VERSION)
        .when()
        .get(ADOPTION)
        .then()
        .statusCode(200)
        .body("repository", equalTo("qits-never-heard-of"))
        .body("packages", empty())
        .body("adopters", empty());
  }

  // --- the one refusal ----------------------------------------------------------------------------

  /**
   * Half a key is a caller bug, and answering "the newest release of that repository" instead would
   * be this service deciding which release was meant.
   */
  @Test
  void aByReleaseLookupMissingHalfItsKeyIsRefusedRatherThanGuessed() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(ADOPTION)
        .then()
        .statusCode(400)
        .body("message", containsString("both a repository and a version"));

    given()
        .header("Authorization", SuiteBearers.operator())
        .queryParam("repository", LIBRARY)
        .when()
        .get(ADOPTION)
        .then()
        .statusCode(400);

    given()
        .header("Authorization", SuiteBearers.operator())
        .queryParam("version", VERSION)
        .when()
        .get(ADOPTION)
        .then()
        .statusCode(400);

    // A blank is the same thing as an absent one — a client that sent `?repository=` sent nothing.
    given()
        .header("Authorization", SuiteBearers.operator())
        .queryParam("repository", "  ")
        .queryParam("version", VERSION)
        .when()
        .get(ADOPTION)
        .then()
        .statusCode(400);
  }
}

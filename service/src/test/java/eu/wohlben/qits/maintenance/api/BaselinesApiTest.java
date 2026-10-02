package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The screenshot-baselines door: render a release request's screenshots in the CI image, and join
 * the images that changed to that request.
 *
 * <p>The three endings are what a caller reads back: SUCCEEDED (the branch moved and is joined),
 * NOTHING_TO_DO (every image already matched), FAILED (the run was red). No test sends an identity
 * header, for the reason {@code MaintenanceApiTest} gives.
 */
@QuarkusTest
class BaselinesApiTest {

  private static final String BASE = "/maintenance/api";

  private static final String REQUEST = "0d0a15ac-5e67-4e03-ad00-ff25d9bbcfea";

  private static final String BRANCH = BumpService.BASELINES_BRANCH_PREFIX + REQUEST;

  private static final String BEFORE = "1111111111111111111111111111111111111111";

  private static final String PUSHED = "2222222222222222222222222222222222222222";

  private static final String RUN = "run-baselines-api";

  private static final String DOOR =
      BASE + "/repositories/" + Fixture.REPOSITORY + "/release-requests/" + REQUEST
          + "/screenshot-baselines";

  private static final String REQUEST_PATH = Fixture.RELEASE_REQUESTS_PATH + "/" + REQUEST;

  @Inject FakePeers peers;

  @Inject ScanService scans;

  @Inject BumpService bumps;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, RUN);
    peers.answer(
        PeerTarget.PROJECTS,
        REQUEST_PATH,
        FakePeers.Scripted.ok("{\"request\":{\"id\":\"" + REQUEST + "\",\"state\":\"REJECTED\"}}"));
    peers.answer(
        PeerTarget.PROJECTS,
        REQUEST_PATH + "/sources",
        FakePeers.Scripted.ok("{\"request\":{\"id\":\"" + REQUEST + "\",\"state\":\"PENDING\"}}"));
    Fixture.scriptForeignBranchAt(peers, BRANCH, BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private String open(String body) {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post(DOOR)
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    queue.awaitIdle(Duration.ofSeconds(30));
    return id;
  }

  /**
   * The run is qits-ci's screenshot-baselines pipeline, started from the fold, and a moved branch
   * is joined to the request.
   */
  @Test
  void aRunThatPushedIsJoinedToTheRequest() {
    String id = open("{\"workItem\":\"qits-112\"}");

    List<String> triggers = peers.bodiesFor("/ci/api/events/trigger");
    assertEquals(1, triggers.size());
    String payload = triggers.get(0);
    assertTrue(payload.contains("\"name\":\"ScreenshotBaselines\""), payload);
    assertTrue(!payload.contains("\"job\""), payload);
    assertTrue(payload.contains("\"baseRef\":\"release/" + REQUEST + "\""), payload);
    assertTrue(payload.contains("\"branch\":\"" + BRANCH + "\""), payload);
    assertTrue(payload.contains("\"workItem\":\"qits-112\""), payload);
    assertTrue(payload.contains("\"changes\":[]"), payload);

    Fixture.scriptForeignBranchAt(peers, BRANCH, PUSHED);
    Fixture.scriptRun(peers, RUN, "SUCCESS");
    bumps.poll(UUID.fromString(id));
    queue.awaitIdle(Duration.ofSeconds(30));

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .statusCode(200)
        .body("mode", equalTo("BASELINES"))
        .body("status", equalTo("SUCCEEDED"))
        .body("resultSha", equalTo(PUSHED))
        .body("releaseRequestId", equalTo(REQUEST))
        .body("message", containsString("joined to release request " + REQUEST));
    List<String> joins = peers.bodiesFor(REQUEST_PATH + "/sources");
    assertEquals(1, joins.size());
    assertTrue(joins.get(0).contains("\"branch\":\"" + BRANCH + "\""), joins.get(0));
  }

  /** A green run that left the branch where it was found every image unchanged: nothing joined. */
  @Test
  void anUnmovedBranchIsUnchanged() {
    String id = open("{}");
    Fixture.scriptRun(peers, RUN, "SUCCESS");
    bumps.poll(UUID.fromString(id));
    queue.awaitIdle(Duration.ofSeconds(30));

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("status", equalTo("NOTHING_TO_DO"))
        .body("message", containsString("unchanged"));
    assertTrue(peers.bodiesFor(REQUEST_PATH + "/sources").isEmpty());
  }

  /** A red run is FAILED, and nothing is joined. */
  @Test
  void aRedRunFails() {
    String id = open("{}");
    Fixture.scriptRun(peers, RUN, "FAILED");
    bumps.poll(UUID.fromString(id));
    queue.awaitIdle(Duration.ofSeconds(30));

    given().when().get(BASE + "/bumps/" + id).then().body("status", equalTo("FAILED"));
    assertTrue(peers.bodiesFor(REQUEST_PATH + "/sources").isEmpty());
  }

  /** A settled request takes no branch: 409 before anything runs. */
  @Test
  void aSettledRequestIsRefused() {
    peers.answer(
        PeerTarget.PROJECTS,
        REQUEST_PATH,
        FakePeers.Scripted.ok("{\"request\":{\"id\":\"" + REQUEST + "\",\"state\":\"RELEASED\"}}"));
    given().contentType(ContentType.JSON).body("{}").when().post(DOOR).then().statusCode(409);
    assertTrue(peers.bodiesFor("/ci/api/events/trigger").isEmpty());
  }

  /** Not a request id, or not a work item: 400. */
  @Test
  void implausibleInputIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(
            BASE + "/repositories/" + Fixture.REPOSITORY
                + "/release-requests/not-a-uuid/screenshot-baselines")
        .then()
        .statusCode(400);
    given()
        .contentType(ContentType.JSON)
        .body("{\"workItem\":\"$(rm -rf)\"}")
        .when()
        .post(DOOR)
        .then()
        .statusCode(400);
  }

  /** A second one for the same request while the first runs: 409. */
  @Test
  void aSecondOneForTheSameRequestIsRefused() {
    open("{}");
    given().contentType(ContentType.JSON).body("{}").when().post(DOOR).then().statusCode(409);
  }

  /** An unknown repository: 404. */
  @Test
  void anUnknownRepositoryIsA404() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(
            BASE + "/repositories/never-scanned/release-requests/" + REQUEST
                + "/screenshot-baselines")
        .then()
        .statusCode(404);
  }
}

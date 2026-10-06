package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The door another service uses: <b>put these pins on this branch, and tell me which commit holds
 * them.</b>
 *
 * <p>The caller is qits-projects, arming a wrapper release request: the request's fold has to carry
 * the gitlink pins CI will gate and a person will approve, rather than have the release bank them
 * afterwards. So the two things this pins are the two halves of that exchange — the 202 with an id,
 * and the id answering a SHA once the run is over. Everything about how the bump is READ is
 * {@code TargetedBumpTest}'s subject; what is here is the boundary.
 *
 * <p><b>No test sends an identity header</b>, for the reason {@code MaintenanceApiTest} gives: the
 * {@code %test} dev user carries both roles, so the shipped {@code @RolesAllowed} pair is exercised
 * rather than bypassed.
 */
@QuarkusTest
class TargetedBumpApiTest {

  private static final String BASE = "/maintenance/api";

  private static final String BRANCH = "workspace/ws-api";

  private static final String PUSHED_SHA = "4444444444444444444444444444444444444444";

  private static final String RUN = "run-targeted-api";

  private static final String BODY =
      """
      {"branch":"%s",
       "changes":[{"ecosystem":"gitlink","manifestPath":"webui","name":"qits-ci-frontend",
                   "from":"c0ffee11d00d2233445566778899aabbccddeeff","to":"2026.910.180413",
                   "location":"gitlink:webui"}]}
      """
          .formatted(BRANCH);

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
    Fixture.scriptForeignBranchAt(peers, BRANCH, PUSHED_SHA);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private String open() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body(BODY)
            .when()
            .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/branches/bumps")
            .then()
            .statusCode(202)
            .contentType(ContentType.JSON)
            .extract()
            .path("id");
    queue.awaitIdle(Duration.ofSeconds(30));
    return id;
  }

  /** 202 with an id, and the id answers the sha the CI step pushed. */
  @Test
  void theDoorAnswers202AndTheBumpReadAnswersTheCommitItWrote() {
    String id = open();

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .statusCode(200)
        .body("mode", equalTo("AUTOMATION"))
        .body("group", equalTo("estate-pins"))
        .body("configPath", equalTo(".config/qits/platform-pipelines/maintenance-bump.yml"))
        .body("branch", equalTo(BRANCH))
        .body("status", equalTo("RUNNING"))
        // Nothing was written yet, and the field says so rather than guessing.
        .body("resultSha", org.hamcrest.Matchers.nullValue());

    Fixture.scriptRun(peers, RUN, "SUCCESS");
    bumps.poll(UUID.fromString(id));
    queue.awaitIdle(Duration.ofSeconds(30));

    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("SUCCEEDED"))
        // THE ANSWER THE CALLER CAME FOR: which commit now holds the pins it asked for.
        .body("resultSha", equalTo(PUSHED_SHA))
        .body("releaseRequestId", org.hamcrest.Matchers.nullValue())
        .body("finishedAt", notNullValue());
  }

  /** A body with no branch is a 400: there is nothing to derive one from in this mode. */
  @Test
  void aBodyWithNoBranchIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"changes\":[]}")
        .when()
        .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/branches/bumps")
        .then()
        .statusCode(400);
  }

  /** A branch that is not a plain ref is refused HERE rather than as a red run over there. */
  @Test
  void anImplausibleBranchIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"--force\",\"changes\":[]}")
        .when()
        .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/branches/bumps")
        .then()
        .statusCode(400);
  }

  /** An unknown repository is a 404: it can be named in a payload but not addressed. */
  @Test
  void anUnknownRepositoryIsA404() {
    given()
        .contentType(ContentType.JSON)
        .body(BODY)
        .when()
        .post(BASE + "/repositories/never-scanned/branches/bumps")
        .then()
        .statusCode(404);
  }

  /** And a second one onto the same branch, while the first is going, is a 409. */
  @Test
  void aSecondBumpOntoTheSameBranchIsRefused() {
    String first = open();
    assertEquals(36, first.length(), "the 202 answers a row id");

    given()
        .contentType(ContentType.JSON)
        .body(BODY)
        .when()
        .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/branches/bumps")
        .then()
        .statusCode(409);
  }
}

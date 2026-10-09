package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three doors of {@code /maintenance/api/release-requests/{requestId}/automations}: who may
 * open them, and what the re-run answers — 202, 400, 404 and 409.
 */
@QuarkusTest
class ReleaseRequestAutomationControllerTest {

  private static final String BASE = "/maintenance/api/release-requests/";

  private static final String DOOR = BASE + REQUEST + "/automations";

  private static final String RERUN = DOOR + "/screenshot-baselines/runs";

  private static final String TRIGGER_BODY =
      "{\"repository\":\"" + Fixture.REPOSITORY + "\",\"foldSha\":\"" + FOLD_A + "\","
          + "\"previousFoldSha\":null,\"changedSincePrevious\":null,"
          + "\"sourceBranches\":[\"main\",\"work\"],\"workItem\":null}";

  @Inject FakePeers peers;

  @Inject ScanService scans;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, "run-automation-door");
    AutomationFixture.scriptFold(peers, FOLD_A, true);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    Fixture.scriptForeignBranchAt(
        peers, AutomationFixture.branch(REQUEST), AutomationFixture.BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private static RequestSpecification as(String role) {
    return given()
        .header("X-Qits-User", "someone")
        .header("X-Qits-Roles", role)
        .contentType(ContentType.JSON);
  }

  private static RequestSpecification rerun(String body) {
    return given().contentType(ContentType.JSON).body(body);
  }

  /**
   * A caller that accepts NOT_APPLICABLE gets every kind (qits-1133): the one that runs, and the
   * ones that do not apply with their reason and no row — on the trigger and on the read.
   */
  @Test
  void aCallerThatAcceptsNotApplicableGetsEveryKind() {
    String body =
        TRIGGER_BODY.replace("\"workItem\":null}", "\"workItem\":null,"
            + "\"accepts\":[\"WAITING\",\"NOT_APPLICABLE\"]}");
    given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(DOOR)
        .then()
        .statusCode(200)
        .body("automations", hasSize(3))
        .body("automations.find { it.kind == 'estate-pins' }.state", equalTo("NOT_APPLICABLE"))
        .body("automations.find { it.kind == 'estate-pins' }.detail", notNullValue())
        .body("automations.find { it.kind == 'estate-pins' }.bumpId", is((Object) null))
        .body("automations.find { it.kind == 'estate-pins' }.runIds", hasSize(0))
        .body("automations.find { it.kind == 'entity-diagram' }.state", equalTo("NOT_APPLICABLE"))
        .body("automations.find { it.kind == 'screenshot-baselines' }.state", equalTo("REQUESTED"));
    queue.awaitIdle(Duration.ofSeconds(30));

    given()
        .when()
        .get(DOOR + "?foldSha=" + FOLD_A + "&accepts=WAITING,NOT_APPLICABLE")
        .then()
        .statusCode(200)
        .body("automations", hasSize(3));
    given()
        .when()
        .get(DOOR + "?foldSha=" + FOLD_A)
        .then()
        .statusCode(200)
        .body("automations", hasSize(1));
  }

  /** The trigger answers the per-kind states, and the read answers the same. */
  @Test
  void theTriggerAnswersEveryApplicableKindAndTheReadAgrees() {
    String bumpId =
        given()
            .contentType(ContentType.JSON)
            .body(TRIGGER_BODY)
            .when()
            .post(DOOR)
            .then()
            .statusCode(200)
            .body("requestId", equalTo(REQUEST))
            .body("foldSha", equalTo(FOLD_A))
            .body("automations", hasSize(1))
            .body("automations[0].kind", equalTo(ScreenshotBaselinesAutomation.KIND))
            .body("automations[0].label", equalTo("Screenshot baselines"))
            .body("automations[0].state", equalTo("REQUESTED"))
            .body("automations[0].branch", equalTo(AutomationFixture.branch(REQUEST)))
            .body("automations[0].updatedAt", notNullValue())
            .extract()
            .path("automations[0].bumpId");
    queue.awaitIdle(Duration.ofSeconds(30));

    given()
        .when()
        .get(DOOR + "?foldSha=" + FOLD_A)
        .then()
        .statusCode(200)
        .body("automations[0].bumpId", equalTo(bumpId))
        .body("automations[0].state", equalTo("RUNNING"))
        .body("automations[0].runIds[0]", equalTo("run-automation-door"));
    given().when().get(DOOR).then().statusCode(200).body("foldSha", equalTo(FOLD_A));
    given().when().get("/maintenance/api/bumps/" + bumpId).then().statusCode(200)
        .body("mode", equalTo("AUTOMATION"));
  }

  /** A read about a request nothing was asked about is empty, not a 404. */
  @Test
  void aReadOfAnUnknownRequestIsEmpty() {
    given()
        .when()
        .get(DOOR)
        .then()
        .statusCode(200)
        .body("automations", hasSize(0));
  }

  @Test
  void theReRunAnswers202AndIsFollowedThroughTheBump() {
    String id =
        rerun("{\"repository\":\"" + Fixture.REPOSITORY + "\",\"workItem\":\"qits-998\"}")
            .when()
            .post(RERUN)
            .then()
            .statusCode(202)
            .body("id", notNullValue())
            .extract()
            .path("id");
    queue.awaitIdle(Duration.ofSeconds(30));
    given()
        .when()
        .get("/maintenance/api/bumps/" + id)
        .then()
        .statusCode(200)
        .body("mode", equalTo("AUTOMATION"))
        .body("trigger", equalTo("MANUAL"))
        .body("status", equalTo("RUNNING"));
  }

  @Test
  void aSecondReRunWhileOneIsActiveIs409() {
    rerun("{\"repository\":\"" + Fixture.REPOSITORY + "\"}").when().post(RERUN).then().statusCode(202);
    // The repository is now known from the first row, so the second need not name it.
    rerun("{}").when().post(RERUN).then().statusCode(409);
  }

  @Test
  void aSettledRequestIs409() {
    AutomationFixture.scriptRequest(peers, REQUEST, "RELEASED", FOLD_A);
    rerun("{\"repository\":\"" + Fixture.REPOSITORY + "\"}").when().post(RERUN).then().statusCode(409);
  }

  @Test
  void anUnknownKindIs404() {
    rerun("{\"repository\":\"" + Fixture.REPOSITORY + "\"}")
        .when()
        .post(DOOR + "/no-such-kind/runs")
        .then()
        .statusCode(404);
  }

  @Test
  void anUnknownRepositoryIs404() {
    rerun("{\"repository\":\"never-scanned\"}").when().post(RERUN).then().statusCode(404);
    // …and so is a request no automation was ever asked about, when no repository is named.
    rerun("{}").when().post(RERUN).then().statusCode(404);
  }

  @Test
  void aRequestIdThatIsNotOneIs400() {
    rerun("{}").when().post(BASE + "not-a-uuid/automations/screenshot-baselines/runs").then()
        .statusCode(400);
    given().contentType(ContentType.JSON).body(TRIGGER_BODY.replace(FOLD_A, "main")).when()
        .post(DOOR).then().statusCode(400);
  }

  @Test
  void theThreeRolesOpenTheDoorsAndNoOtherDoes() {
    for (String role : new String[] {"qits:admin", "qits:system", "qits:agent"}) {
      as(role).body(TRIGGER_BODY).post(DOOR).then().statusCode(not(anyOf(is(401), is(403))));
      as(role).get(DOOR).then().statusCode(200);
      queue.awaitIdle(Duration.ofSeconds(30));
    }
    as("qits:agent")
        .body("{\"repository\":\"" + Fixture.REPOSITORY + "\"}")
        .post(RERUN)
        .then()
        .statusCode(not(anyOf(is(401), is(403))));
    as("qits:reader").body(TRIGGER_BODY).post(DOOR).then().statusCode(403);
    as("qits:reader").get(DOOR).then().statusCode(403);
    as("qits:reader").body("{}").post(RERUN).then().statusCode(403);
  }
}

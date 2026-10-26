package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code qits:desk} (qits-1596): the delivery desk's credential, carrying {@code qits:agent} and
 * {@code qits:desk} and NOT {@code qits:admin-agent}. The rule is that the desk is admitted
 * everywhere the coordinator ({@code qits:admin-agent}) is — this service has no project concept
 * to bind it to, so every grant here is outright — EXCEPT the two doors that actually write a git
 * ref: {@link ScanController} and {@link ArtifactController#ingest} queue reads only and ARE opened
 * to the desk; the release-request automation trigger and re-run dispatch a bump through {@link
 * eu.wohlben.qits.maintenance.bump.CiClient}, which is what pushes a branch, and stay closed to the
 * desk exactly as they are closed to plain {@code qits:agent}.
 *
 * <p>{@code qits:desk-admin} (epic qits-1621) is a different grant: it is a platform operator role,
 * not the desk's own project-bound credential, and the epic's "Inventory" dossier page records no
 * refusal for this service — so unlike {@code qits:desk}, it reaches the trigger and the re-run
 * too, exactly where {@code qits:admin-agent} does.
 *
 * <p>Each request names its identity in {@code X-Qits-User} / {@code X-Qits-Roles}, mirroring
 * {@link AgentReadAccessTest}.
 */
@QuarkusTest
class DeskAccessTest {

  private static final String BASE = "/maintenance/api";

  private static RequestSpecification as(String role) {
    return given().header("X-Qits-User", "the-delivery-desk").header("X-Qits-Roles", role);
  }

  private static RequestSpecification desk() {
    return as("qits:desk");
  }

  private static void allowed(Response response) {
    response.then().statusCode(not(anyOf(is(401), is(403))));
  }

  @Test
  void aDeskCallerReadsEverythingTheCoordinatorReads() {
    desk().get(BASE + "/repositories").then().statusCode(200);
    allowed(desk().get(BASE + "/repositories/qits-ci-service"));
    allowed(desk().get(BASE + "/repositories/qits-ci-service/dependents"));
    allowed(desk().get(BASE + "/repositories/qits-ci-service/downstream"));
    desk().get(BASE + "/artifacts").then().statusCode(200);
    allowed(desk().get(BASE + "/scans/" + UUID.randomUUID()));
    allowed(desk().get(BASE + "/dependencies"));
    allowed(
        desk()
            .get(BASE + "/dependencies/dependents?ecosystem=maven&name=eu.wohlben.qits:qits-auth-core"));
    allowed(desk().get(BASE + "/pins"));
    allowed(desk().get(BASE + "/adoption/by-release?repository=qits-ci-service&version=1"));
    desk().get(BASE + "/automations/kinds").then().statusCode(200);
    desk().get(BASE + "/bumps").then().statusCode(200);
    desk().get(BASE + "/bumps/pending").then().statusCode(200);
    allowed(desk().get(BASE + "/bumps/" + UUID.randomUUID()));
    allowed(desk().get(BASE + "/release-requests/" + UUID.randomUUID() + "/automations"));
  }

  /**
   * Unlike plain {@code qits:agent} ({@link
   * AgentReadAccessTest#anAgentScansNothingAndIngestsNothing}), the desk is admitted on a scan and
   * an ingest — neither writes a git ref, both only queue a read, so the "no write does" line that
   * excludes {@code qits:agent} here does not exclude the desk, which is let in exactly where the
   * coordinator already is.
   */
  @Test
  void aDeskCallerScansAndIngestsJustLikeTheCoordinator() {
    allowed(
        desk()
            .contentType(ContentType.JSON)
            .body("{\"scope\":\"ALL\"}")
            .post(BASE + "/scans"));
    allowed(
        desk()
            .contentType(ContentType.JSON)
            .body("{\"ecosystem\":\"maven\",\"name\":\"x\",\"version\":\"1\"}")
            .post(BASE + "/artifacts/ingest"));
  }

  /**
   * <b>The one place the desk does NOT follow the coordinator.</b> Both doors dispatch a bump row
   * through {@code CiClient}, which is what pushes a branch into a repository on qits-ci's side —
   * exactly the write qits-1596 forbids handing to the desk, so {@code qits:admin-agent} still
   * opens both and {@code qits:desk} opens neither.
   */
  @Test
  void aDeskCallerCannotTriggerOrReRunAnAutomation() {
    String requestId = UUID.randomUUID().toString();
    desk()
        .contentType(ContentType.JSON)
        .body(
            "{\"repository\":\"qits-ci-service\",\"foldSha\":\"deadbeef\","
                + "\"previousFoldSha\":null,\"changedSincePrevious\":null,"
                + "\"sourceBranches\":[\"main\"],\"workItem\":null}")
        .post(BASE + "/release-requests/" + requestId + "/automations")
        .then()
        .statusCode(403);
    desk()
        .contentType(ContentType.JSON)
        .body("{\"repository\":\"qits-ci-service\"}")
        .post(BASE + "/release-requests/" + requestId + "/automations/screenshot-baselines/runs")
        .then()
        .statusCode(403);
  }

  /**
   * {@code qits:desk-admin} is admitted everywhere {@code qits:admin-agent} is (epic qits-1621),
   * the trigger and the re-run included — the one place {@code qits:desk} is refused above.
   */
  @Test
  void aDeskAdminCallerTriggersAndReRunsJustLikeTheCoordinator() {
    String requestId = UUID.randomUUID().toString();
    allowed(
        as("qits:desk-admin")
            .contentType(ContentType.JSON)
            .body(
                "{\"repository\":\"qits-ci-service\",\"foldSha\":\"deadbeef\","
                    + "\"previousFoldSha\":null,\"changedSincePrevious\":null,"
                    + "\"sourceBranches\":[\"main\"],\"workItem\":null}")
            .post(BASE + "/release-requests/" + requestId + "/automations"));
    allowed(
        as("qits:desk-admin")
            .contentType(ContentType.JSON)
            .body("{\"repository\":\"qits-ci-service\"}")
            .post(BASE + "/release-requests/" + requestId + "/automations/screenshot-baselines/runs"));
  }
}

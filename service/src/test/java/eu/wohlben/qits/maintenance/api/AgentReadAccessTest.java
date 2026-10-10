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
 * {@code qits:agent}, a commissioned agent's own role: it reads every GET route, it works the bump
 * doors, and it starts nothing else.
 *
 * <p>Each request names its identity in {@code X-Qits-User} / {@code X-Qits-Roles}, so the {@code
 * %test} dev user does not apply and the identity holds exactly the role sent. Where a route needs
 * data this suite does not seed, the test asserts only that the door let the request through.
 *
 * <p><b>The bumps were refused here once, and are not now.</b> A bump chooses no content — its pins
 * resolve tags that are already released — so what the door grants is timing, and refusing an agent
 * bought nothing but a person to go and press it. What still bounds the role is the rest of the
 * write surface, and the roles outside it: {@code qits:reader} is refused on the same doors.
 */
@QuarkusTest
class AgentReadAccessTest {

  private static final String BASE = "/maintenance/api";

  private static RequestSpecification as(String role) {
    return given().header("X-Qits-User", "dyn-workspace-agent").header("X-Qits-Roles", role);
  }

  private static RequestSpecification agent() {
    return as("qits:agent");
  }

  private static void readable(String path) {
    allowed(agent().get(BASE + path));
  }

  /** The door opened — whatever the request then made of itself. */
  private static void allowed(Response response) {
    response.then().statusCode(not(anyOf(is(401), is(403))));
  }

  @Test
  void anAgentReadsTheRepositories() {
    agent().get(BASE + "/repositories").then().statusCode(200);
    readable("/repositories/qits-ci-service");
    readable("/repositories/qits-ci-service/dependents");
    readable("/repositories/qits-ci-service/downstream");
  }

  @Test
  void anAgentReadsTheArtifacts() {
    agent().get(BASE + "/artifacts").then().statusCode(200);
  }

  @Test
  void anAgentReadsAScan() {
    readable("/scans/" + UUID.randomUUID());
  }

  @Test
  void anAgentReadsTheDependencies() {
    readable("/dependencies");
    readable("/dependencies/dependents?ecosystem=maven&name=eu.wohlben.qits:qits-auth-core");
  }

  @Test
  void anAgentReadsThePins() {
    readable("/pins");
  }

  @Test
  void anAgentReadsTheAdoption() {
    readable("/adoption/by-release?repository=qits-ci-service&version=1");
  }

  @Test
  void anAgentReadsTheBumps() {
    agent().get(BASE + "/bumps").then().statusCode(200);
    agent().get(BASE + "/bumps/pending").then().statusCode(200);
    readable("/bumps/" + UUID.randomUUID());
  }

  @Test
  void anAgentScansNothingAndIngestsNothing() {
    agent()
        .contentType(ContentType.JSON)
        .body("{\"scope\":\"ALL\"}")
        .post(BASE + "/scans")
        .then()
        .statusCode(403);
    agent()
        .contentType(ContentType.JSON)
        .body("{\"ecosystem\":\"maven\",\"name\":\"x\",\"version\":\"1\"}")
        .post(BASE + "/artifacts/ingest")
        .then()
        .statusCode(403);
  }

  @Test
  void aRoleOutsideTheBoundaryIsStillRefused() {
    as("qits:reader").get(BASE + "/repositories").then().statusCode(403);
  }
}

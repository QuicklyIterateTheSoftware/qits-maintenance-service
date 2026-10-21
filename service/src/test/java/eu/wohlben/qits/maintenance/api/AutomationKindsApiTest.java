package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /maintenance/api/automations/kinds} (epic qits-1038): the catalogue qits-projects'
 * {@code AutomationContentSource} reads to open an approval requirement from a kind's own
 * declaration, with no kind table of its own.
 *
 * <p><b>No test sends an identity header</b>, as {@link MaintenanceApiTest} notes: qits-auth-core
 * ships a {@code %test} dev user carrying {@code qits:admin} and {@code qits:system}, so the
 * shipped {@code @RolesAllowed} pair is exercised. {@link AgentReadAccessTest} covers {@code
 * qits:agent} and the 403 for a role outside the boundary.
 */
@QuarkusTest
class AutomationKindsApiTest {

  private static final String PATH = "/maintenance/api/automations/kinds";

  @Test
  void answersEveryKindSortedByKindWithItsRolesSorted() {
    given()
        .when()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("kinds.kind", contains("dependency-bump", "entity-diagram", "estate-pins", "screenshot-baselines"))
        .body("kinds.find { it.kind == 'screenshot-baselines' }.label", org.hamcrest.Matchers.is("Screenshot baselines"))
        .body(
            "kinds.find { it.kind == 'screenshot-baselines' }.committablePaths",
            contains(":(glob)**/__screenshots__/**", ":(glob)**/testing/browser/renderer.txt"))
        .body(
            "kinds.find { it.kind == 'screenshot-baselines' }.approvalRoles",
            contains("agent", "person"))
        .body(
            "kinds.find { it.kind == 'entity-diagram' }.approvalRoles", contains("agent", "person"))
        .body("kinds.find { it.kind == 'estate-pins' }.approvalRoles", empty())
        .body("kinds.find { it.kind == 'dependency-bump' }.approvalRoles", empty())
        .body(
            "kinds.find { it.kind == 'dependency-bump' }.committablePaths",
            containsInAnyOrder(
                ":(glob)**/pom.xml",
                ":(glob)**/package.json",
                ":(glob)**/package-lock.json",
                ":(glob)**/Dockerfile",
                ":(glob)**/Dockerfile.*",
                ":(glob)**/*.Dockerfile"));
  }
}

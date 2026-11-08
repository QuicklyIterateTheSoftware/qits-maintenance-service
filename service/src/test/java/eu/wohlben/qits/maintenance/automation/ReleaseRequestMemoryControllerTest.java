package eu.wohlben.qits.maintenance.automation;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.api.SuiteBearers;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /maintenance/api/release-requests/{requestId}} (qits-1954): what this service
 * remembers about one release request — its {@code mt_release_request} row — and the 404 for one it
 * remembers nothing about.
 */
@QuarkusTest
class ReleaseRequestMemoryControllerTest {

  private static final String BASE = "/maintenance/api/release-requests/";

  private static final String MAIN_ONLY = "7a2c4e6f-1b3d-4f5a-8c9e-0d1f2a3b4c5d";

  private static final String WITHDRAWN = "8b3d5f7a-2c4e-4a6b-9d0f-1e2a3b4c5d6e";

  private static final String UNKNOWN = "9c4e6a8b-3d5f-4b7c-8e1a-2f3b4c5d6e7f";

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject MaintenanceStore store;

  @BeforeEach
  void forgetEveryRequest() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
  }

  /** A MAIN_ONLY request with its pending changes and three upstream restarts: starved. */
  @Test
  void aMainOnlyRequestReadsWithItsChangesAndIsStarvedAtThreeRestarts() {
    Instant now = Instant.parse("2026-11-08T10:00:00Z");
    store.recordOpenedRequest(
        MAIN_ONLY,
        Fixture.REPOSITORY,
        "main",
        MtReleaseRequest.MAIN_ONLY,
        List.of(
            Map.of("name", "quarkus-bom", "from", "3.40.0", "to", "3.40.1"),
            Map.of("name", "jackson-bom", "from", "2.20.0", "to", "2.20.1")),
        now);
    for (int restart = 1; restart <= UpstreamReplan.STARVATION_RESTARTS; restart++) {
      store.recordUpstreamRestart(MAIN_ONLY, Fixture.REPOSITORY, now.plusSeconds(restart));
    }

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(BASE + MAIN_ONLY)
        .then()
        .statusCode(200)
        .body("requestId", equalTo(MAIN_ONLY))
        .body("repository", equalTo(Fixture.REPOSITORY))
        .body("opened", equalTo(true))
        .body("purpose", equalTo("MAIN_ONLY"))
        .body("branch", equalTo("main"))
        .body("openedAt", notNullValue())
        .body("changes", instanceOf(List.class))
        .body("changes", hasSize(2))
        .body("changes[0].name", equalTo("quarkus-bom"))
        .body("upstreamRestarts", equalTo(3))
        .body("upstreamRestartedAt", notNullValue())
        .body("starved", equalTo(true))
        .body("withdrawnAt", nullValue())
        .body("updatedAt", notNullValue());
  }

  /**
   * A request an upstream only restarted is answered unopened; once this service opened and
   * withdrew it, it carries when and why.
   */
  @Test
  void aWithdrawnRequestReadsWithItsWithdrawal() {
    Instant now = Instant.parse("2026-11-08T11:00:00Z");
    store.recordUpstreamRestart(WITHDRAWN, Fixture.REPOSITORY, now);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(BASE + WITHDRAWN)
        .then()
        .statusCode(200)
        .body("opened", equalTo(false))
        .body("purpose", nullValue())
        .body("branch", nullValue())
        .body("openedAt", nullValue())
        .body("changes", hasSize(0))
        .body("upstreamRestarts", equalTo(1))
        .body("starved", equalTo(false));

    store.recordOpenedRequest(
        WITHDRAWN,
        Fixture.REPOSITORY,
        "maintenance/quarkus",
        MtReleaseRequest.GROUP_BUMP,
        null,
        now.plusSeconds(1));
    store.requestWithdrawn(WITHDRAWN, "its group bump was superseded", now.plusSeconds(2));

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(BASE + WITHDRAWN)
        .then()
        .statusCode(200)
        .body("opened", equalTo(true))
        .body("purpose", equalTo("GROUP_BUMP"))
        .body("changes", hasSize(0))
        .body("withdrawnAt", notNullValue())
        .body("withdrawnReason", equalTo("its group bump was superseded"));
  }

  /** A request nothing is remembered about is a 404 with the platform's envelope. */
  @Test
  void anUnknownRequestIs404() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(BASE + UNKNOWN)
        .then()
        .statusCode(404)
        .body(
            "message",
            equalTo("qits-maintenance remembers nothing about release request " + UNKNOWN));
  }

  /** A blank id is not a request id. */
  @Test
  void aBlankIdIs400() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(BASE + "{requestId}", " ")
        .then()
        .statusCode(400);
  }

  /** The desk reads it, as it reads the automations beside it (qits-1596). */
  @Test
  void theDeskMayRead() {
    store.recordUpstreamRestart(MAIN_ONLY, Fixture.REPOSITORY, Instant.now());

    given()
        .header("Authorization", SuiteBearers.bearer("the-desk", "qits:desk"))
        .when()
        .get(BASE + MAIN_ONLY)
        .then()
        .statusCode(200)
        .body("requestId", equalTo(MAIN_ONLY));
  }
}

package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /deployed-pacts} (qits-1765): the newest pact at or below each deployed consumer
 * version, compared as calver, one row per (consumer, pact version) — and the refusals.
 */
@QuarkusTest
class DeployedPactsApiTest {

  private static final String URL = "/maintenance/api/deployed-pacts";

  private static final String PROVIDER = "qits-projects-service";

  private static final String CONSUMER = "qits-maintenance-service";

  private static final String ARTIFACT = CONSUMER + "-pacts-" + PROVIDER;

  /** What qits-deployments calls the consumer: the last segment of its image. */
  private static final String APPLICATION = "qits-maintenance";

  @Inject FakePeers peers;

  @Inject MaintenanceStore store;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void reset() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    store.upsertArtifact(
        Ecosystem.DOCKER,
        "qits/" + APPLICATION,
        "2026.1030.204903",
        CONSUMER,
        Instant.parse("2026-10-30T20:49:03Z"));
  }

  /**
   * Pacts at 312 and 1200 of one day — text order would put 1200 first — and deployed versions at
   * 900 and 1300: 900 takes 312 (1200 is above it), 1300 takes 1200.
   */
  @Test
  void eachDeployedVersionTakesTheNewestPactAtOrBelowItNumerically() {
    pact(CONSUMER, "2026.1030.312");
    pact(CONSUMER, "2026.1030.1200");
    deployed(APPLICATION, "2026.1030.900", "2026.1030.1300");

    given()
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(200)
        .body("provider", equalTo(PROVIDER))
        .body("pacts", hasSize(2))
        .body("pacts[0].consumerRepository", equalTo(CONSUMER))
        .body("pacts[0].groupId", equalTo("eu.wohlben.qits"))
        .body("pacts[0].artifactId", equalTo(ARTIFACT))
        .body("pacts[0].pactVersion", equalTo("2026.1030.312"))
        .body("pacts[0].deployedVersions", contains("2026.1030.900"))
        .body("pacts[1].pactVersion", equalTo("2026.1030.1200"))
        .body("pacts[1].deployedVersions", contains("2026.1030.1300"));
  }

  /**
   * Sparse pacts: two deployed versions after the same pact are ONE row naming both, newest first;
   * a deployed version older than every pact, and a commit sha, name none.
   */
  @Test
  void deployedVersionsSharingAPactAreOneRowAndOnesBelowEveryPactAreSkipped() {
    pact(CONSUMER, "2026.1028.235307");
    pact(CONSUMER, "2026.1031.1");
    deployed(
        APPLICATION,
        "2026.1030.192107",
        "2026.1030.204903",
        "2026.1027.1",
        "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80");

    given()
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(200)
        .body("pacts", hasSize(1))
        .body("pacts[0].pactVersion", equalTo("2026.1028.235307"))
        .body(
            "pacts[0].deployedVersions", contains("2026.1030.204903", "2026.1030.192107"));
  }

  /** An exact match is "at or below"; consumers are sorted; another provider's pacts stay out. */
  @Test
  void consumersAreSortedAndOnlyTheNamedProvidersPactsCount() {
    String other = "qits-ci-service";
    store.upsertArtifact(
        Ecosystem.DOCKER, "qits/qits-ci", "2026.1029.5", other, Instant.parse("2026-10-29T00:00:05Z"));
    pact(CONSUMER, "2026.1030.204903");
    pact(other, "2026.1029.5");
    store.upsertArtifact(
        Ecosystem.MAVEN,
        "eu.wohlben.qits:" + CONSUMER + "-pacts-qits-ci-service",
        "2026.1030.1",
        CONSUMER,
        Instant.parse("2026-10-30T00:00:01Z"));
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.ok(
            "{\"pins\":[{\"applicationName\":\""
                + APPLICATION
                + "\",\"shas\":[\"2026.1030.204903\"]},{\"applicationName\":\"qits-ci\",\"shas\":[\"2026.1029.5\"]}]}"));

    given()
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(200)
        .body("pacts.consumerRepository", contains(other, CONSUMER))
        .body("pacts.pactVersion", contains("2026.1029.5", "2026.1030.204903"))
        .body("pacts[0].artifactId", equalTo(other + "-pacts-" + PROVIDER));
  }

  @Test
  void anUnknownProviderIsAnEmptyList() {
    pact(CONSUMER, "2026.1028.235307");
    deployed(APPLICATION, "2026.1030.204903");

    given()
        .queryParam("provider", "qits-nobody-service")
        .when()
        .get(URL)
        .then()
        .statusCode(200)
        .body("provider", equalTo("qits-nobody-service"))
        .body("pacts", empty());
  }

  @Test
  void aMissingOrBlankProviderIsABadRequest() {
    given().when().get(URL).then().statusCode(400);
    given().queryParam("provider", "  ").when().get(URL).then().statusCode(400);
  }

  @Test
  void aDeployerThatCannotAnswerIsARefusal() {
    pact(CONSUMER, "2026.1028.235307");
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.unreachable("connection refused"));

    given()
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(503)
        .body("message", containsString("deployed versions could not be read"));
  }

  /** A CI step's token carries {@code qits:ci-run} and nothing else; a reader is still refused. */
  @Test
  void aCiRunReadsItAndAReaderDoesNot() {
    given()
        .header("X-Qits-User", "tok-ci-run")
        .header("X-Qits-Roles", "qits:ci-run")
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(not(anyOf(is(401), is(403))));
    given()
        .header("X-Qits-User", "someone")
        .header("X-Qits-Roles", "qits:reader")
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(403);
  }

  private void pact(String consumer, String version) {
    store.upsertArtifact(
        Ecosystem.MAVEN,
        "eu.wohlben.qits:" + consumer + "-pacts-" + PROVIDER,
        version,
        consumer,
        Instant.parse("2026-10-30T00:00:00Z"));
  }

  private void deployed(String application, String... versions) {
    String shas =
        Arrays.stream(versions).map(v -> "\"" + v + "\"").collect(Collectors.joining(","));
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.ok(
            "{\"pins\":[{\"applicationName\":\"" + application + "\",\"shas\":[" + shas + "]}]}"));
  }
}

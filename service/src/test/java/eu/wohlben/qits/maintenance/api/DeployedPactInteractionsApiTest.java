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
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.maintenance.control.DeployedPactInteractions;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
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
 * {@code GET /deployed-pacts/interactions} (qits-2702): each deployed consumer pact's interactions
 * against the provider, read off its jar; a consumer that no longer declares the pact is left out;
 * an unreadable jar fails the whole answer.
 */
@QuarkusTest
class DeployedPactInteractionsApiTest {

  private static final String URL = "/maintenance/api/deployed-pacts/interactions";

  private static final String PROVIDER = "qits-toggles-service";

  private static final String CONSUMER = "qits-maintenance-service";

  private static final String ARTIFACT = CONSUMER + "-pacts-" + PROVIDER;

  private static final String APPLICATION = "qits-maintenance";

  private static final String PACT_VERSION = "2026.1125.100";

  private static final String DEPLOYED = "2026.1126.200";

  @Inject FakePeers peers;

  @Inject MaintenanceStore store;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject DeployedPactInteractions interactions;

  @BeforeEach
  void reset() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    interactions.forgetJars();
    store.upsertArtifact(
        Ecosystem.DOCKER,
        "qits/" + APPLICATION,
        DEPLOYED,
        CONSUMER,
        Instant.parse("2026-11-26T02:00:00Z"));
    store.upsertArtifact(
        Ecosystem.MAVEN,
        "eu.wohlben.qits:" + ARTIFACT,
        PACT_VERSION,
        CONSUMER,
        Instant.parse("2026-11-25T00:01:00Z"));
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.ok(
            "{\"pins\":[{\"applicationName\":\"" + APPLICATION + "\",\"shas\":[\"" + DEPLOYED
                + "\"]}]}"));
  }

  @Test
  void aDeclaringConsumersJarIsReadIntoItsInteractions() {
    store.replacePactEdges(CONSUMER, List.of(PROVIDER));
    publishJar();

    given()
        .header("Authorization", SuiteBearers.platform())
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(200)
        .body("provider", equalTo(PROVIDER))
        .body("pacts", hasSize(1))
        .body("pacts[0].consumerRepository", equalTo(CONSUMER))
        .body("pacts[0].pactVersion", equalTo(PACT_VERSION))
        .body("pacts[0].deployedVersions", contains(DEPLOYED))
        .body("pacts[0].interactions", hasSize(2))
        .body("pacts[0].interactions[0].method", equalTo("GET"))
        .body("pacts[0].interactions[0].path", equalTo("/toggles/api/toggle/new-page/boolean"))
        .body("pacts[0].interactions[0].operationId", equalTo("evaluateBooleanToggle"))
        .body(
            "pacts[0].interactions[0].providerStates[0].name",
            equalTo("toggle new-page is declared"))
        .body("pacts[0].interactions[0].providerStates[0].params.slug", equalTo("new-page"))
        .body("pacts[0].interactions[1].path", equalTo("/toggles/api/toggle/old-page/boolean"));
  }

  /** The jar is a released artifact and never changes: a second read does not download it again. */
  @Test
  void aJarIsDownloadedOnce() {
    store.replacePactEdges(CONSUMER, List.of(PROVIDER));
    publishJar();
    String jarUrl = peers.url(PeerTarget.MAVEN_REGISTRY, PactJarFixture.path(ARTIFACT, PACT_VERSION));

    for (int i = 0; i < 2; i++) {
      given()
          .header("Authorization", SuiteBearers.platform())
          .queryParam("provider", PROVIDER)
          .when()
          .get(URL)
          .then()
          .statusCode(200)
          .body("pacts[0].interactions", hasSize(2));
    }
    assertEquals(1, peers.urls().stream().filter(jarUrl::equals).count());
  }

  /** A consumer whose release.yml dropped the pact counts no more, though its jar is deployed. */
  @Test
  void aConsumerThatNoLongerDeclaresThePactIsLeftOut() {
    store.replacePactEdges(CONSUMER, List.of("qits-projects-service"));
    publishJar();

    given()
        .header("Authorization", SuiteBearers.platform())
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(200)
        .body("pacts", empty());
    assertEquals(
        0,
        peers.urls().stream()
            .filter(url -> url.equals(
                peers.url(PeerTarget.MAVEN_REGISTRY, PactJarFixture.path(ARTIFACT, PACT_VERSION))))
            .count(),
        "a consumer left out is not downloaded");
  }

  @Test
  void aMissingJarFailsTheAnswer() {
    store.replacePactEdges(CONSUMER, List.of(PROVIDER));

    given()
        .header("Authorization", SuiteBearers.platform())
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(502)
        .body("message", containsString(ARTIFACT + ":" + PACT_VERSION));
  }

  @Test
  void aJarWithoutAPactOnTheProviderFailsTheAnswer() {
    store.replacePactEdges(CONSUMER, List.of(PROVIDER));
    peers.answerBytes(
        PeerTarget.MAVEN_REGISTRY,
        PactJarFixture.path(ARTIFACT, PACT_VERSION),
        PactJarFixture.jar(Map.of("pacts/" + CONSUMER + "_qits-other-service.json", "{}")));

    given()
        .header("Authorization", SuiteBearers.platform())
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(502)
        .body("message", containsString("holds no pacts/*_" + PROVIDER + ".json"));
  }

  @Test
  void aDeployerThatCannotAnswerIsARefusal() {
    store.replacePactEdges(CONSUMER, List.of(PROVIDER));
    publishJar();
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.unreachable("connection refused"));

    given()
        .header("Authorization", SuiteBearers.platform())
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(503);
  }

  @Test
  void anUnknownProviderIsAnEmptyListAndNoProviderIsABadRequest() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .queryParam("provider", "qits-nobody-service")
        .when()
        .get(URL)
        .then()
        .statusCode(200)
        .body("pacts", empty());
    given().header("Authorization", SuiteBearers.platform()).when().get(URL).then().statusCode(400);
  }

  @Test
  void aServiceReadsItAndAReaderDoesNot() {
    given()
        .header("Authorization", SuiteBearers.bearer("dev-qits-toggles", "qits:system"))
        .queryParam("provider", "qits-nobody-service")
        .when()
        .get(URL)
        .then()
        .statusCode(not(anyOf(is(401), is(403))));
    given()
        .header("Authorization", SuiteBearers.bearer("someone", "qits:reader"))
        .queryParam("provider", PROVIDER)
        .when()
        .get(URL)
        .then()
        .statusCode(403);
  }

  private void publishJar() {
    peers.answerBytes(
        PeerTarget.MAVEN_REGISTRY,
        PactJarFixture.path(ARTIFACT, PACT_VERSION),
        PactJarFixture.jar(
            Map.of(
                "pacts/" + CONSUMER + "_" + PROVIDER + ".json",
                PactJarFixture.pact(
                    CONSUMER,
                    PROVIDER,
                    Map.of(
                        "/toggles/api/toggle/new-page/boolean",
                        new String[] {
                          "evaluateBooleanToggle", "toggle new-page is declared", "slug", "new-page"
                        },
                        "/toggles/api/toggle/old-page/boolean",
                        new String[] {
                          "evaluateBooleanToggle", "toggle old-page is declared", "slug", "old-page"
                        })),
                // Another provider's pact in the same jar, and a nested file: neither is read.
                "pacts/" + CONSUMER + "_qits-other-service.json",
                "{\"interactions\":[{\"request\":{\"method\":\"GET\",\"path\":\"/other\"}}]}")));
  }
}

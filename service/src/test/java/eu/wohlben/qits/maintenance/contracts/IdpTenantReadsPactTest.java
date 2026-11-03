package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the quarkus-oidc tenant's two startup reads</b> (ticket qits-708): the
 * discovery document and the JWKS it names, each against a pact mock server answering what {@link
 * IdpContract#DISCOVERY} and {@link IdpContract#JWKS} promise, read as quarkus-oidc reads them —
 * and the discovery {@code issuer} compared by value, since it is what every bearer's {@code iss}
 * is checked against.
 *
 * <p>Plain JUnit: no class of this source tree makes either call, so there is no client to drive.
 * The rows are held to the configuration that switches them on instead ({@link
 * #theTenantsReadsArePacted}): turning discovery off again, or adding a {@code token.issuer} beside
 * it, is a red build here rather than an unpacted read or an issuer pinned in configuration.
 *
 * <p>The committed pact file is compared by {@code IdpTokenMintPactTest}, the one writer of {@code
 * pacts/qits-maintenance-service_qits-idp-service.json}; this class writes nothing.
 */
class IdpTenantReadsPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void theTenantReadsTheDiscoveryDocument() {
    IdpContract.PACT.run(
        IdpContract.DISCOVERY,
        (url, recorded) -> {
          JsonNode document = JSON.readTree(get(url + recorded.path()));
          assertEquals(
              IdpContract.IDP
                  .json(IdpContract.A_PUBLISHED_SIGNING_KEY, "getOpenIdConfiguration")
                  .path("issuer")
                  .asText(),
              document.path("issuer").asText(),
              "the issuer every bearer's iss is checked against");
          URI.create(document.path("jwks_uri").asText());
          URI.create(document.path("token_endpoint").asText());
        });
  }

  @Test
  void theTenantReadsTheJwks() {
    IdpContract.PACT.run(
        IdpContract.JWKS,
        (url, recorded) -> {
          JsonWebKeySet keys = new JsonWebKeySet(get(url + recorded.path()));
          JsonWebKey key =
              keys.findJsonWebKey(
                  recorded.params().get("kid"), RsaJsonWebKey.KEY_TYPE, "sig", "RS256");
          assertNotNull(
              key, () -> "an RSA RS256 signing key named by the state's kid in " + keys.getJsonWebKeys());
          assertNotNull(((RsaJsonWebKey) key).getRsaPublicKey().getModulus());
        });
  }

  /**
   * The tenant's reads are held to the shipped configuration: it reads qits-idp, no issuer is
   * configured (qits-730), and with discovery on — the default, and what ships — the pact carries
   * both the discovery document and the JWKS; with discovery off it would be the JWKS alone.
   */
  @Test
  void theTenantsReadsArePacted() throws IOException {
    Properties shipped = new Properties();
    try (InputStream in =
        Files.newInputStream(
            GoldenFiles.repositoryRoot()
                .resolve("service/src/main/resources/application.properties"))) {
      shipped.load(in);
    }
    String server = shipped.getProperty("quarkus.oidc.auth-server-url", "");
    assertTrue(
        server.startsWith("https://idp.qits."), "the tenant reads qits-idp at its edge host: " + server);
    assertNull(
        shipped.getProperty("quarkus.oidc.token.issuer"),
        "the issuer is never configuration: discovery publishes it (qits-730)");
    boolean discovery =
        Boolean.parseBoolean(shipped.getProperty("quarkus.oidc.discovery-enabled", "true"));
    Set<String> tenantReads = new TreeSet<>();
    for (GoldenInteraction row : IdpContract.PACT.rows()) {
      if (row.trigger().equals(IdpContract.DISCOVERY.trigger())) {
        tenantReads.add(row.operationId());
      }
    }
    assertEquals(
        discovery ? Set.of("getJwks", "getOpenIdConfiguration") : Set.of("getJwks"),
        tenantReads,
        "the oidc tenant's reads of " + server);
  }

  /** A plain GET of the mock server, as quarkus-oidc's own reads are: 200 and the body. */
  private static String get(String url) throws Exception {
    HttpResponse<String> answer =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, answer.statusCode(), answer.body());
    return answer.body();
  }
}

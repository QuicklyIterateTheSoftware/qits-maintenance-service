package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.maintenance.peer.PeerTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the token mint</b> (ticket qits-1149): {@link PeerTokens#token()} through
 * the real quarkus-oidc-client, its {@code qits} client pointed at a pact mock server that answers
 * what {@link IdpContract#MINT} promises — and the bearer it hands {@code PeerClient} compared with
 * what the mock answered.
 *
 * <p>A {@code @QuarkusTest}, unlike the peer rows: the mint is the oidc client's, configured from
 * config at boot, so the mock runs on a port this profile picks before the application starts.
 *
 * <p>The committed {@code pacts/qits-maintenance-service_qits-idp-service.json} is compared here
 * too, or rewritten under {@code -Dgolden.update=true}.
 */
@QuarkusTest
@TestProfile(IdpTokenMintPactTest.MockIdp.class)
class IdpTokenMintPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  /**
   * The port the pact mock server listens on, chosen free once per JVM and kept in a system
   * property: the test profile is instantiated in Quarkus' own class loader, where a static field
   * of this class is a second copy with a second free port.
   */
  static final String PORT_PROPERTY = "qits.contracts.mock-idp-port";

  static final int PORT = port();

  @Test
  void theCommittedPactIsWhatTheRowWrites() {
    IdpContract.PACT.compareOrWritePactFile();
    IdpContract.PACT.assertEveryInteractionCarriesBothReferences();
  }

  /** The {@code qits} client switched on and pointed at the mock, as the state's client. */
  public static class MockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> params = IdpContract.IDP.params(IdpContract.MINT.state());
      // The state hands its client as the Basic credential it sends: id and secret, decoded.
      String basic = params.get("authorization").substring("Basic ".length());
      String[] pair =
          new String(Base64.getDecoder().decode(basic), StandardCharsets.UTF_8).split(":", 2);
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled", "true",
          "quarkus.oidc-client.qits.auth-server-url", "http://127.0.0.1:" + PORT + "/idp",
          "quarkus.oidc-client.qits.client-id", pair[0],
          "quarkus.oidc-client.qits.credentials.secret", pair[1],
          "quarkus.oidc-client.qits.grant-options.client.audience", params.get("audience"));
    }
  }

  @Inject PeerTokens tokens;

  @Test
  void thePeerBearerIsTheTokenTheIdpMinted() {
    String minted = IdpContract.IDP.json(IdpContract.MINT.state(), IdpContract.MINT.operationId())
        .path("access_token")
        .asText();
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            IdpContract.PACT.pact(List.of(IdpContract.MINT)),
            MockProviderConfig.Companion.httpConfig("127.0.0.1", PORT, PactSpecVersion.V4),
            (mockServer, context) -> {
              assertEquals(Optional.of(minted), tokens.token());
              return null;
            });
    assertInstanceOf(PactVerificationResult.Ok.class, result, String.valueOf(result));
  }

  private static synchronized int port() {
    String chosen = System.getProperty(PORT_PROPERTY);
    if (chosen == null) {
      chosen = String.valueOf(freePort());
      System.setProperty(PORT_PROPERTY, chosen);
    }
    return Integer.parseInt(chosen);
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * <b>The consumer half of the token mint</b> (ticket qits-1149): {@link PeerTokens#token()} through
 * the real quarkus-oidc-client, its {@code qits} client pointed at a pact mock server that answers
 * what {@link IdpContract#MINT} promises — and the bearer it hands {@code PeerClient} compared with
 * what the mock answered.
 *
 * <p>A {@code @QuarkusTest}, unlike the peer rows: the mint is the oidc client's, configured from
 * config at boot, so the mock runs on a port this profile picks before the application starts.
 *
 * <p><b>Enabled once qits-idp records the state</b> ({@link #recorded}): until then there are no
 * params to configure the client with, and {@code PeerConsumerPactTest} reports the row skipped
 * with the state it needs.
 */
@QuarkusTest
@TestProfile(IdpTokenMintPactTest.MockIdp.class)
@EnabledIf("recorded")
class IdpTokenMintPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  /** The port the pact mock server listens on, chosen free once per JVM. */
  static final int PORT = freePort();

  static boolean recorded() {
    return IdpContract.IDP.recorded(IdpContract.MINT.state(), IdpContract.MINT.operationId());
  }

  /** The {@code qits} client switched on and pointed at the mock, as the state's client. */
  public static class MockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> params = IdpContract.IDP.params(IdpContract.MINT.state());
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled", "true",
          "quarkus.oidc-client.qits.auth-server-url", "http://127.0.0.1:" + PORT + "/idp",
          "quarkus.oidc-client.qits.client-id", params.get("callerClientId"),
          "quarkus.oidc-client.qits.credentials.secret", params.get("clientSecret"));
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

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

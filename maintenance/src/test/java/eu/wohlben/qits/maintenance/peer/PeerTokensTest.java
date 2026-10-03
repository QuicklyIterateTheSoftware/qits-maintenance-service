package eu.wohlben.qits.maintenance.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import io.smallrye.mutiny.Uni;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PeerTokens} mints through ONE named client, {@code qits}, for every peer now (epic qits-540
 * dossier, 'Plan (as of 2026-09-13)', C4) — there used to be five, one per peer's own audience. There
 * is no {@code credential} argument left to branch on: {@link #token()} either asks the one injected
 * client or it does not, which is the whole of what this proves.
 *
 * <p>No CDI: the field {@link PeerTokens#qits} is set directly, the same shortcut every plain unit
 * test in this module takes around the container.
 */
class PeerTokensTest {

  @AfterEach
  void clearOverride() {
    System.clearProperty("quarkus.oidc-client.qits.client-enabled");
  }

  @Test
  void disabledAnswersEmptyAndNeverAsksTheClient() {
    System.setProperty("quarkus.oidc-client.qits.client-enabled", "false");
    PeerTokens tokens = new PeerTokens();
    tokens.qits = refusingClient();

    assertTrue(tokens.token().isEmpty());
  }

  @Test
  void enabledMintsThroughTheOneClientForAnyPeer() {
    System.setProperty("quarkus.oidc-client.qits.client-enabled", "true");
    PeerTokens tokens = new PeerTokens();
    tokens.qits = fakeClient("a-fresh-bearer");

    // One call, no PeerTarget or credential name passed at all — a call for githost, ci or mirror
    // asks the exact same way.
    assertEquals(Optional.of("a-fresh-bearer"), tokens.token());
  }

  private static OidcClient fakeClient(String accessToken) {
    return new OidcClient() {
      @Override
      public Uni<Tokens> getTokens(Map<String, String> additionalGrantParameters) {
        return Uni.createFrom().item(new Tokens(accessToken, null, null, null, null, null, "qits"));
      }

      @Override
      public Uni<Tokens> refreshTokens(String refreshToken, Map<String, String> additionalParams) {
        return getTokens(additionalParams);
      }

      @Override
      public Uni<Boolean> revokeAccessToken(String accessToken, Map<String, String> additionalParams) {
        return Uni.createFrom().item(Boolean.TRUE);
      }

      @Override
      public void close() {}
    };
  }

  /** A client that fails the test if PeerTokens asks it anything — the disabled path must not. */
  private static OidcClient refusingClient() {
    return new OidcClient() {
      @Override
      public Uni<Tokens> getTokens(Map<String, String> additionalGrantParameters) {
        throw new AssertionError("a disabled qits client must never be asked for a token");
      }

      @Override
      public Uni<Tokens> refreshTokens(String refreshToken, Map<String, String> additionalParams) {
        throw new AssertionError("a disabled qits client must never be asked for a token");
      }

      @Override
      public Uni<Boolean> revokeAccessToken(String accessToken, Map<String, String> additionalParams) {
        throw new AssertionError("a disabled qits client must never be asked for a token");
      }

      @Override
      public void close() {}
    };
  }
}

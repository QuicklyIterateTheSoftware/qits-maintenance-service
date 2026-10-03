package eu.wohlben.qits.maintenance.peer;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * The one named oidc client, {@code qits}, and the bearer it mints for every peer.
 *
 * <p><b>One client now, where there used to be five.</b> A token used to be cut FOR one service's
 * own audience — qits-githost refused a bearer whose audience named qits-ci — so a peer-scoped call
 * needed a peer-scoped client. The epic qits-540 dossier's 'Plan (as of 2026-09-13)', C4, gave every
 * token one platform audience instead, {@code qits-platform}, which every receiver now accepts; one
 * client mints it for every peer this service calls.
 *
 * <p><b>The switch is the extension's own</b>, {@code quarkus.oidc-client.qits.client-enabled}. It
 * reads only the deployer's {@code idp:client} resource now, with no fallback to the OLD
 * {@code projects} client's toggle. There is no key of ours beside it — one switch cannot disagree
 * with itself. Off, this answers empty and a call goes out with the forward-auth headers alone.
 *
 * <p><b>The release ask needs no client of its own.</b> It goes to qits-projects, on the credential
 * every catalog read already mints.
 *
 * <p><b>A token this cannot mint is empty rather than an exception</b>, the orchestrator's stance:
 * the refusal that matters belongs to the call itself. An anonymous call to a guarded peer comes
 * back 401 and the row records the url and the status, which is more useful than a mint failure one
 * layer earlier.
 */
@ApplicationScoped
public class PeerTokens {

  private static final Logger LOG = Logger.getLogger(PeerTokens.class);

  /** The mint is not the call: this bounds the hop to idp, not the hop to the peer. */
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @Inject
  @NamedOidcClient("qits")
  OidcClient qits;

  /** Caches and refreshes the token, so a scan of seventy repositories is not seventy token
   * requests. */
  private final TokensHelper helper = new TokensHelper();

  /** The bearer for every peer, or empty when the client is disabled or cannot mint. */
  public Optional<String> token() {
    if (!enabled()) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(helper.getTokens(qits).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for a peer: %s", e.toString());
      return Optional.empty();
    }
  }

  private boolean enabled() {
    return ConfigProvider.getConfig()
        .getOptionalValue("quarkus.oidc-client.qits.client-enabled", Boolean.class)
        .orElse(false);
  }
}

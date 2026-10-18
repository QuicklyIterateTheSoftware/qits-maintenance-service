package eu.wohlben.qits.maintenance.peer;

import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * <b>A real {@link PeerClient} pointed at one pact mock server, mounts kept</b> (ticket qits-1149).
 *
 * <p>{@link TestPeerClient} drops the target and appends the path to the mock's base, which is
 * right for every target whose key is a bare host. Four are not: the registry and mirror targets
 * name a MOUNT ({@code /artifacts/maven/maven}, {@code /v2}, {@code /npm/npmjs} …), and the path a
 * provider records is the whole route, mount included. So the mount is read off the shipped config
 * — the path part of each target's url — and kept between the mock's base and the call's path. The
 * mount is therefore what the deployment ships, not a second copy written down here.
 */
public final class ContractPeerClient extends PeerClient {

  private final String base;

  public ContractPeerClient(String base) {
    this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.callTimeout = Duration.ofSeconds(5);
    this.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    this.tokens =
        new PeerTokens() {
          @Override
          public Optional<String> token() {
            // The machine token is minted at qits-idp by quarkus-oidc-client, a call of its own
            // with a row of its own (IdpContract); a peer row carries the forward-auth pair alone.
            return Optional.empty();
          }
        };
  }

  @Override
  public String url(PeerTarget target, String path) {
    return base + mount(target) + path;
  }

  /** The path the shipped config puts in front of every call to {@code target}; empty for a host. */
  public static String mount(PeerTarget target) {
    String url =
        target.urlKey() == null
            ? target.derivedBase(null)
            : ConfigProvider.getConfig().getValue(target.urlKey(), String.class);
    String path = URI.create(url).getRawPath();
    if (path == null || path.equals("/")) {
      return "";
    }
    return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }
}

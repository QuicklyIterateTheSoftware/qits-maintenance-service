package eu.wohlben.qits.maintenance.peer;

import java.time.Duration;
import java.util.Optional;

/**
 * <b>A REAL {@link PeerClient} for the qits-projects consumer pact</b> ({@code
 * eu.wohlben.qits.maintenance.sbomcheck.ProjectsContract}, epic qits-965/qits-974): every call
 * still goes out over HTTP through the inherited {@link PeerClient#send}, exactly the path
 * production takes — only {@link #url} is overridden, to send every call at a pact-jvm mock
 * server's address instead of resolving a {@link PeerTarget}'s configured one. A hand-rolled call
 * would prove a contract for a client that does not ship; {@link TicketClient} knows nothing of
 * this class and is handed one through its ordinary {@code peers} field.
 *
 * <p>It lives in this package rather than in {@code sbomcheck} (where {@code ProjectsContract} and
 * {@code TicketClient} do) because {@link PeerClient#callTimeout} and {@link PeerClient#tokens} are
 * package-private test seams — this class sets both directly rather than needing CDI or a running
 * Quarkus application, which is also why the whole pact test is plain JUnit.
 */
public final class TestPeerClient extends PeerClient {

  private final String base;

  public TestPeerClient(String base) {
    this.base = base;
    this.callTimeout = Duration.ofSeconds(5);
    this.tokens =
        new PeerTokens() {
          @Override
          public Optional<String> token() {
            // No idp in a plain JUnit test: every call goes out with the forward-auth headers
            // alone, exactly as a deployment with quarkus.oidc-client.qits.client-enabled=false
            // ships (see PeerTokens' own javadoc).
            return Optional.empty();
          }
        };
  }

  @Override
  public String url(PeerTarget target, String path) {
    return base + path;
  }
}

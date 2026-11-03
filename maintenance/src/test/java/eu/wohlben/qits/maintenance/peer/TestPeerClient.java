package eu.wohlben.qits.maintenance.peer;

import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.Optional;

/**
 * <b>A REAL {@link PeerClient} for the qits-projects consumer pact</b> ({@code
 * eu.wohlben.qits.maintenance.contracts.ProjectsContract}, epics qits-965/qits-546): every call
 * still goes out over HTTP through the inherited {@link PeerClient#send}, exactly the path
 * production takes — only {@link #url} is overridden, to send every call at a pact-jvm mock
 * server's address instead of resolving a {@link PeerTarget}'s configured one. A hand-rolled call
 * would prove a contract for a client that does not ship; {@link TicketClient} knows nothing of
 * this class and is handed one through its ordinary {@code peers} field.
 *
 * <p>It lives in this package rather than in {@code contracts} (where {@code ProjectsContract}
 * does) because {@link PeerClient#callTimeout}, {@link PeerClient#tokens} and
 * {@link PeerClient#clients} are package-private test seams — this class sets all three directly
 * rather than needing CDI or a running Quarkus application, which is also why the whole pact test
 * is plain JUnit.
 */
public final class TestPeerClient extends PeerClient {

  private final String base;

  public TestPeerClient(String base) {
    this.base = base;
    this.callTimeout = Duration.ofSeconds(5);
    this.clients = QitsHttpClients.of(OpenTelemetry.noop(), null);
    this.tokens =
        new PeerTokens() {
          @Override
          public Optional<String> token() {
            // No idp in a plain JUnit test, and no pact row pins what this bearer says — but
            // PeerClient now refuses to send any call with no bearer at all (qits-543), so a
            // stand-in is what makes the call go out at all.
            return Optional.of("a-contract-test-bearer");
          }
        };
  }

  @Override
  public String url(PeerTarget target, String path) {
    return base + path;
  }
}

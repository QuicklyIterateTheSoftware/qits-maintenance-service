package eu.wohlben.qits.maintenance.sbomcheck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.maintenance.peer.TestPeerClient;
import eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-projects contract</b> (epic qits-965, qits-974): a real {@link
 * TicketClient}, with a real {@link eu.wohlben.qits.maintenance.peer.PeerClient#send} making a real
 * HTTP call, pointed at a pact-jvm mock server that answers exactly what {@link ProjectsContract}'s
 * interaction for that row promises — and the row's own assertions on what {@link TicketClient}
 * made of it. A row whose request the client does not make, or whose answer it cannot bind, fails
 * here; the committed pact file is checked by {@code ProjectsPactFileTest}.
 *
 * <p><b>Plain JUnit 5, unlike qits-workspaces-service's copy of this class.</b> That repository's
 * equivalent client is a generated reactive REST client, which refuses to build outside a running
 * Quarkus application — forcing {@code @QuarkusTest} there. {@link TicketClient} talks over the
 * JDK's own {@code HttpClient} ({@link eu.wohlben.qits.maintenance.peer.PeerClient}), which needs no
 * CDI and no augmentation to construct, so nothing here needs Quarkus either.
 *
 * <p><b>pact-jvm's programmatic runner, not {@code PactConsumerTestExt}</b>, for the same reason
 * qits-workspaces-service's copy picked it: the extension serves every interaction of a pact from
 * ONE mock server, which cannot tell apart the rows that send the identical {@code GET
 * /projects/api/work/{qualifiedId}} — only their trigger differs, so all but the first would read
 * as "never called". One mock server per row sidesteps that.
 */
class ProjectsConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Test
  void everyRowOfTheContractIsWhatTicketClientAsksAndUnderstands() {
    assertFalse(ProjectsContract.CASES.isEmpty());
    List<String> failures = new ArrayList<>();
    for (ProjectsContract.Case row : ProjectsContract.CASES) {
      PactVerificationResult result =
          ConsumerPactRunnerKt.runConsumerTest(
              ProjectsContract.pact(List.of(row)),
              MockProviderConfig.createDefault(PactSpecVersion.V4),
              (mockServer, context) -> {
                row.call().run(ticketClientAgainst(mockServer.getUrl()), GoldenMasters.params(row.state()));
                return null;
              });
      if (!(result instanceof PactVerificationResult.Ok)) {
        failures.add(row.description() + " [" + row.state() + "]: " + describe(result));
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " contract row(s) failed:\n  " + String.join("\n  ", failures));
    }
  }

  private static TicketClient ticketClientAgainst(String baseUrl) {
    TicketClient client = new TicketClient();
    client.peers = new TestPeerClient(baseUrl);
    return client;
  }

  private static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}

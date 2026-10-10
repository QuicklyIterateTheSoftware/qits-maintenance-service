package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of the qits-projects contract</b> (epics qits-965, qits-546): the real
 * clients ({@link Clients}), with a real {@link eu.wohlben.qits.maintenance.peer.PeerClient#send}
 * making a real HTTP call, pointed at a pact-jvm mock server that answers exactly what {@link
 * ProjectsContract}'s interaction for that row promises — and the row's own assertions on what the
 * client made of it. A row whose request the client does not make, or whose answer it cannot
 * bind, fails here; the committed pact file is checked by {@code ProjectsPactFileTest}.
 *
 * <p><b>Plain JUnit 5.</b> The clients talk over the JDK's own {@code HttpClient} ({@link
 * eu.wohlben.qits.maintenance.peer.PeerClient}), which needs no CDI and no augmentation.
 *
 * <p><b>pact-jvm's programmatic runner, one mock server per row</b>, not {@code
 * PactConsumerTestExt}: the extension serves every interaction of a pact from ONE mock server,
 * which cannot tell apart rows that send the identical request and differ only by trigger.
 */
class ProjectsConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @TestFactory
  Stream<DynamicTest> everyRowIsWhatTheClientAsksAndUnderstands() {
    assertFalse(ProjectsContract.CASES.isEmpty());
    return ProjectsContract.CASES.stream()
        .map(row -> DynamicTest.dynamicTest(row.description() + " [" + row.state() + "]", () -> run(row)));
  }

  private static void run(ProjectsContract.Case row) {
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            ProjectsContract.pact(List.of(row)),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              row.call().run(Clients.against(mockServer.getUrl()), GoldenMasters.params(row.state()));
              return null;
            });
    if (!(result instanceof PactVerificationResult.Ok)) {
      fail(describe(result));
    }
  }

  private static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}

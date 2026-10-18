package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.maintenance.peer.ContractPeerClient;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of every contract against a peer other than qits-projects</b> (ticket
 * qits-1149): each {@link PeerRow}'s real client call, over a real {@code PeerClient} ({@link
 * ContractPeerClient}), against a pact-jvm mock server answering that row's interaction (and its
 * companions) from the provider's golden master — and per provider the committed {@code
 * pacts/qits-maintenance-service_<provider>.json}, compared, or rewritten under {@code
 * -Dgolden.update=true}.
 *
 * <p><b>A row whose provider state is not recorded is skipped</b>, naming the state, the operation
 * and the provider ({@code needs provider state '…' for … in …}), and is in no pact file. The day
 * the provider records it, the pact-file test goes red until the file is rewritten.
 */
class PeerConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  /** Every peer contract this service has, by provider. */
  static final Map<String, PeerRow.Contract> CONTRACTS =
      Map.of(
          "qits-ci-service", CiContract.CONTRACT,
          "qits-deployments-service", DeploymentsContract.CONTRACT,
          "qits-artifacts-service", ArtifactsContract.CONTRACT,
          "qits-githost-service", GitHostContract.CONTRACT,
          "qits-mirror-service", MirrorContract.CONTRACT);

  @TestFactory
  Stream<DynamicNode> everyRowIsWhatTheClientAsksAndUnderstands() {
    return CONTRACTS.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                DynamicContainer.dynamicContainer(
                    entry.getKey(),
                    entry.getValue().rows().stream()
                        .map(
                            row ->
                                DynamicTest.dynamicTest(
                                    row.interaction().description()
                                        + " ["
                                        + row.interaction().state()
                                        + "]",
                                    () -> run(entry.getValue(), row)))));
  }

  @TestFactory
  Stream<DynamicNode> theCommittedPactsAreWhatTheRowsWrite() {
    List<DynamicNode> nodes = new ArrayList<>();
    CONTRACTS.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              ConsumerPact pact = entry.getValue().pact();
              nodes.add(
                  DynamicTest.dynamicTest(pact.file(), pact::compareOrWritePactFile));
              nodes.add(
                  DynamicTest.dynamicTest(
                      pact.file() + " carries both references",
                      () -> {
                        Assumptions.assumeFalse(pact.recorded().isEmpty(), "no row recorded yet");
                        pact.assertEveryInteractionCarriesBothReferences();
                      }));
            });
    nodes.add(DynamicTest.dynamicTest(IdpContract.PACT.file(), IdpContract.PACT::compareOrWritePactFile));
    nodes.add(
        DynamicTest.dynamicTest(
            IdpContract.PACT.file() + ": " + IdpContract.MINT.description(),
            () -> {
              // Run through the real oidc client by IdpTokenMintPactTest once recorded.
              if (!IdpContract.PACT.recorded().contains(IdpContract.MINT)) {
                Assumptions.abort(IdpContract.PACT.needs(IdpContract.MINT));
              }
            }));
    return nodes.stream();
  }

  private static void run(PeerRow.Contract contract, PeerRow row) {
    ConsumerPact pact = contract.pact();
    GoldenInteraction interaction = row.interaction();
    if (!contract.masters().recorded(interaction.state(), interaction.operationId())) {
      Assumptions.abort(pact.needs(interaction));
    }
    for (GoldenInteraction companion : row.companions()) {
      if (!contract.masters().recorded(companion.state(), companion.operationId())) {
        Assumptions.abort("its companion " + pact.needs(companion));
      }
    }
    if (row.companions().isEmpty()) {
      pact.run(
          interaction,
          (url, recorded) ->
              row.call()
                  .run(
                      PeerRow.Peers.over(new ContractPeerClient(url)),
                      new PeerRow.Params(recorded.params())));
      return;
    }
    List<GoldenInteraction> served = new ArrayList<>();
    served.add(interaction);
    served.addAll(row.companions());
    Map<String, String> params = contract.masters().params(interaction.state());
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            pact.pact(served),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              row.call()
                  .run(
                      PeerRow.Peers.over(new ContractPeerClient(mockServer.getUrl())),
                      new PeerRow.Params(params));
              return null;
            });
    if (result instanceof PactVerificationResult.Error error
        && error.getError() instanceof AssertionError assertion) {
      throw assertion;
    }
    if (!(result instanceof PactVerificationResult.Ok)) {
      fail(interaction.description() + ": " + result);
    }
  }

  @org.junit.jupiter.api.Test
  void everyProviderHasARow() {
    CONTRACTS.values().forEach(contract -> assertFalse(contract.rows().isEmpty()));
  }
}

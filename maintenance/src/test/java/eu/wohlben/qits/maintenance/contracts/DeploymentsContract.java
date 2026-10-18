package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.maintenance.control.DeployedVersions;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>What qits-maintenance asks qits-deployments, and why</b> (ticket qits-1149): {@link
 * DeployedVersions}' one read, the versions that serve and the ones a rollback restores, which
 * {@code GET /pins} ({@code listDependencyPins}) carries for the registry GC.
 */
final class DeploymentsContract {

  static final GoldenMasters DEPLOYMENTS =
      GoldenMasters.of("qits-deployments-service", "qits-deployments");

  static final String AN_APPLICATION_SERVING_WITH_A_ROLLBACK_VERSION =
      "an application serving with a rollback version";

  static final List<PeerRow> ROWS =
      List.of(
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.operation("listDependencyPins"),
                      AN_APPLICATION_SERVING_WITH_A_ROLLBACK_VERSION,
                      "listPins")
                  .consumes("pins[].applicationName", "pins[].shas[]")
                  .anyLength("pins", "pins[].shas"),
              (peers, p) -> {
                Map<String, Set<String>> deployed = peers.deployed().byApplication();
                assertFalse(deployed.isEmpty());
                deployed.values().forEach(versions -> assertFalse(versions.isEmpty()));
              }));

  static final PeerRow.Contract CONTRACT = new PeerRow.Contract(DEPLOYMENTS, ROWS);

  private DeploymentsContract() {}
}

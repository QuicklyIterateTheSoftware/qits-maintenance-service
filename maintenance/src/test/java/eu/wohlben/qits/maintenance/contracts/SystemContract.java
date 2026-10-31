package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.SystemClient;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.List;

/**
 * <b>What qits-maintenance asks qits-system, and why</b> (qits-1769): {@link SystemClient}'s one
 * read, every target's slots, reservations and leases in use, from which the dispatcher counts the
 * CI room by qits-system's own allocation rule.
 *
 * <p>Two states, because the rule reads both halves of {@code connected}: a connected target with
 * leases (whose free CI slots the gate counts) and a declared, unconnected one (which it must not
 * count). The listing is a top-level array, so its length is left open — a provider with a second
 * target must not break this pact.
 */
final class SystemContract {

  static final GoldenMasters SYSTEM = GoldenMasters.of("qits-system-service", "qits-system");

  static final String A_CONNECTED_TARGET_WITH_LEASES = "a connected target with leases";
  static final String A_DECLARED_TARGET = "a declared target";

  /** {@link SystemClient#targets}: what the free-slot rule reads of each target. */
  static final List<String> TARGET =
      List.of(
          "[].name",
          "[].slotsTotal",
          "[].reservations",
          "[].used",
          "[].connected",
          "[].quarantined");

  static final List<PeerRow> ROWS =
      List.of(
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("BumpDispatcher.tick"),
                      A_CONNECTED_TARGET_WITH_LEASES,
                      "listTargets")
                  .consumes(TARGET)
                  .anyLength("$"),
              (peers, p) -> {
                SystemClient.Targets targets = peers.system().targets();
                assertTrue(targets.readable(), targets.error());
                assertFalse(targets.eligible().isEmpty(), "the target is connected");
                // 6 slots, 1 ci + 1 workspace used, the unused 1 workspace + 1 desk reservation
                // kept for them: 6 - 2 - 2 = 2 free for CI, and 6 - 3 = 3 it could ever hold.
                assertEquals(2, targets.freeCi());
                assertEquals(3, targets.ciCapacity());
              }),
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("BumpDispatcher.tick"), A_DECLARED_TARGET, "listTargets")
                  .consumes(TARGET)
                  .anyLength("$"),
              (peers, p) -> {
                SystemClient.Targets targets = peers.system().targets();
                assertTrue(targets.readable(), targets.error());
                assertFalse(targets.targets().isEmpty());
                assertTrue(targets.eligible().isEmpty(), "a declared target is not connected");
                assertEquals(0, targets.ciCapacity());
              }));

  static final PeerRow.Contract CONTRACT = new PeerRow.Contract(SYSTEM, ROWS);

  private SystemContract() {}
}

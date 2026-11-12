package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SystemClient.Target}'s CI room under a per-type maximum (qits-2094): the slot rule
 * qits-1769 mirrors from qits-system's {@code SlotAllocator}, capped by {@code maximums.ci} where a
 * target declares one. Live, {@code ws-proof} answered 8 slots, {@code maximums.ci = 2} and both CI
 * leases in use — no room by qits-system's rule — and the gate counted one free slot of seven.
 */
class SystemTargetTest {

  private static SystemClient.Target target(
      int total, Map<String, Integer> used, Map<String, Integer> maximums) {
    return new SystemClient.Target("ws-proof", total, Map.of(), used, true, false, maximums);
  }

  @Test
  void aTargetAtItsCiMaximumHasNoRoom() {
    SystemClient.Target target = target(8, Map.of("ci", 2, "workspace", 1), Map.of("ci", 2));
    assertEquals(0, target.freeCi());
    assertEquals(2, target.ciCapacity());
  }

  @Test
  void belowItsMaximumTheSmallerOfTheTwoCounts() {
    assertEquals(2, target(8, Map.of("ci", 1), Map.of("ci", 3)).freeCi(), "3 - 1, not 7");
    assertEquals(1, target(4, Map.of("ci", 1, "workspace", 2), Map.of("ci", 3)).freeCi(), "4 - 3");
  }

  @Test
  void usedAboveTheMaximumIsNoRoomRatherThanNegative() {
    assertEquals(0, target(8, Map.of("ci", 3), Map.of("ci", 2)).freeCi());
  }

  @Test
  void noMaximumOrOneForAnotherTypeLeavesTheSlotRuleAlone() {
    assertEquals(6, target(8, Map.of("ci", 2), Map.of()).freeCi());
    assertEquals(8, target(8, Map.of("ci", 2), Map.of()).ciCapacity());
    assertEquals(6, target(8, Map.of("ci", 2), Map.of("workspace", 1)).freeCi());
    assertEquals(8, target(8, Map.of("ci", 2), Map.of("workspace", 1)).ciCapacity());
  }
}

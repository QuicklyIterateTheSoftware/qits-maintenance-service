package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The release order a bump's base is chosen by (qits-1081): numeric per segment, because the
 * estate's own versions are not zero-padded and a string compare ranks them the wrong way round.
 */
class CalverTest {

  /** The case from the ticket: 17:16:56 is later than 06:18:54, whatever the text says. */
  @Test
  void aLaterTimeOfDayIsNewerThoughItSortsFirstAsText() {
    assertTrue("2026.1007.171656".compareTo("2026.1007.61854") < 0, "the trap, as text");
    assertTrue(Calver.compare("2026.1007.171656", "2026.1007.61854") > 0);
    assertTrue(Calver.compare("2026.1007.61854", "2026.1007.171656") < 0);
  }

  @Test
  void aLaterMonthIsNewerThoughItIsShorter() {
    assertTrue(Calver.compare("2026.1006.40352", "2026.919.120127") > 0);
    assertTrue(Calver.compare("2027.101.1", "2026.1231.235959") > 0);
  }

  @Test
  void equalVersionsAndZeroPaddingCompareEqual() {
    assertEquals(0, Calver.compare("2026.1007.171656", "2026.1007.171656"));
    assertEquals(0, Calver.compare("2026.1007", "2026.1007.0"));
  }

  /** Something the platform did not cut is never chosen over something it did. */
  @Test
  void aNonCalverStringSortsBelowEveryRelease() {
    assertTrue(Calver.compare("latest", "2026.1.1") < 0);
    assertTrue(Calver.compare("2026.1.1", "2026.1.1-rc1") > 0);
    assertTrue(Calver.compare(null, "2026.1.1") < 0);
  }

  @Test
  void theOrderSortsAListOldestFirst() {
    List<String> versions =
        new ArrayList<>(
            List.of("2026.1007.171656", "2026.919.120127", "2026.1007.61854", "2026.1006.40352"));
    versions.sort(Calver.ORDER);
    assertEquals(
        List.of("2026.919.120127", "2026.1006.40352", "2026.1007.61854", "2026.1007.171656"),
        versions);
  }
}

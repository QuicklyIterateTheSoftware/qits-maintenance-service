package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.CiClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>The registry's two invariants</b>, over the kinds this build really registers: ids are unique
 * and well-formed, and no two kinds may commit the same path — the half of "no automation's output
 * is another automation's input" a test can hold.
 */
@QuarkusTest
class AutomationRegistryTest {

  @Inject AutomationService automations;

  @Test
  void kindIdsAreUniqueAndWellFormed() {
    List<ReleaseRequestAutomation> kinds = automations.allKinds();
    assertFalse(kinds.isEmpty(), "screenshot-baselines at least");
    Set<String> seen = new HashSet<>();
    for (ReleaseRequestAutomation kind : kinds) {
      assertTrue(kind.kind().matches("[a-z0-9-]+"), kind.kind());
      assertTrue(seen.add(kind.kind()), "two kinds named " + kind.kind());
      assertFalse(kind.label() == null || kind.label().isBlank(), kind.kind());
      assertTrue(
          List.of(CiClient.AUTOMATION_EVENT_NAME, CiClient.EVENT_NAME).contains(kind.pipeline()),
          kind.kind() + " names a pipeline qits-ci does not run: " + kind.pipeline());
      if (kind.target() == Target.OWN_BRANCH) {
        assertEquals(
            AutomationService.BRANCH_PREFIX + kind.kind() + "/",
            kind.branchPrefix(),
            "qits-ci lets an automation push under maintenance/automations/<kind>/ and nowhere"
                + " else");
      }
    }
  }

  @Test
  void committablePathsArePairwiseDisjoint() {
    assertDisjoint(automations.allKinds(), null);
  }

  /** A kind behind a switch that is off is registered but not offered (qits-1133). */
  @Test
  void aSwitchedOffKindIsNotOffered() {
    assertTrue(
        automations.allKinds().stream()
            .anyMatch(kind -> DependencyBumpAutomation.KIND.equals(kind.kind())));
    assertTrue(
        automations.kinds().stream()
            .noneMatch(kind -> DependencyBumpAutomation.KIND.equals(kind.kind())),
        "the switch is off by default");
  }

  /** The two SOURCE kinds are SOURCE; everything else is DERIVED. */
  @Test
  void theSourceKindsAreEstatePinsAndDependencyBump() {
    for (ReleaseRequestAutomation kind : automations.allKinds()) {
      boolean source =
          List.of(EstatePinsAutomation.KIND, DependencyBumpAutomation.KIND).contains(kind.kind());
      assertEquals(source ? Stage.SOURCE : Stage.DERIVED, kind.stage(), kind.kind());
    }
  }

  /**
   * Every witness path one kind's pathspecs produce is matched by no other kind's — read through
   * {@link Pathspecs}, the same reading carry-over and the core's postlude apply.
   */
  static void assertDisjoint(List<ReleaseRequestAutomation> kinds, AutomationSubject subject) {
    for (ReleaseRequestAutomation one : kinds) {
      List<String> mine = subject == null ? one.committablePaths() : one.committablePaths(subject);
      for (ReleaseRequestAutomation other : kinds) {
        if (other == one) {
          continue;
        }
        List<String> theirs =
            subject == null ? other.committablePaths() : other.committablePaths(subject);
        for (String pathspec : mine) {
          for (String witness : witnesses(pathspec)) {
            assertTrue(Pathspecs.matches(pathspec, witness), pathspec + " should match " + witness);
            assertFalse(
                Pathspecs.matchesAny(theirs, witness),
                one.kind() + " and " + other.kind() + " both commit " + witness);
          }
        }
      }
    }
  }

  /** Paths a pathspec matches: its wildcards filled in a couple of ways. */
  static List<String> witnesses(String pathspec) {
    return Pathspecs.witnesses(pathspec);
  }
}

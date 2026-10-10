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
 * <b>The registry's invariants</b>, over the kinds this build really registers: ids are unique and
 * well-formed, no two kinds may commit the same path — across ALL kinds, whatever their stage — and,
 * restated for the pre-run's two stages (qits-1133), no kind's output is the declared input of
 * another kind IN THE SAME STAGE, nor of any SOURCE kind. A DERIVED kind may read SOURCE output:
 * the stage ordering is what keeps those two apart, not carry-over.
 */
@QuarkusTest
class AutomationRegistryTest {

  @Inject AutomationService automations;

  @Test
  void kindIdsAreUniqueAndWellFormed() {
    List<ReleaseRequestAutomation> kinds = automations.kinds();
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
            "qits-ci lets the core push under maintenance/automations/<kind>/ and nowhere else");
        assertEquals(CiClient.AUTOMATION_EVENT_NAME, kind.pipeline());
      }
    }
  }

  @Test
  void committablePathsArePairwiseDisjoint() {
    assertDisjoint(automations.kinds(), null);
  }

  /** Each of the four kinds in the stage the ticket put it in. */
  @Test
  void everyKindHasItsStage() {
    for (ReleaseRequestAutomation kind : automations.kinds()) {
      Stage expected =
          switch (kind.kind()) {
            case EstatePinsAutomation.KIND, DependencyBumpAutomation.KIND -> Stage.SOURCE;
            default -> Stage.DERIVED;
          };
      assertEquals(expected, kind.stage(), kind.kind());
    }
  }

  /**
   * <b>No output feeds another kind in the same stage, and no DERIVED output feeds a SOURCE kind</b>
   * — read against the inputs a kind declares; a kind that declares none reads "everything" and is
   * held to the union rule instead, which disjoint outputs already satisfy.
   */
  @Test
  void noKindsOutputIsTheInputOfAKindItCouldLoopWith() {
    List<ReleaseRequestAutomation> kinds = automations.kinds();
    for (ReleaseRequestAutomation writer : kinds) {
      for (ReleaseRequestAutomation reader : kinds) {
        if (writer == reader || reader.inputPaths() == null) {
          continue;
        }
        boolean mayRead = writer.stage() == Stage.SOURCE && reader.stage() == Stage.DERIVED;
        if (mayRead) {
          continue;
        }
        for (String pathspec : writer.committablePaths()) {
          for (String witness : witnesses(pathspec)) {
            assertFalse(
                Pathspecs.matchesAny(reader.inputPaths(), witness),
                writer.kind() + " writes " + witness + ", which " + reader.kind() + " reads");
          }
        }
      }
    }
  }

  /** The other direction holds by design: the bump's manifests are the diagram's inputs. */
  @Test
  void aDerivedKindReadsWhatASourceKindWrites() {
    ReleaseRequestAutomation diagram = automations.kind(EntityDiagramAutomation.KIND).orElseThrow();
    assertTrue(Pathspecs.matchesAny(diagram.inputPaths(), "service/pom.xml"));
    assertTrue(Pathspecs.matchesAny(DependencyBumpAutomation.MANIFESTS, "service/pom.xml"));
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
    String spec = pathspec.startsWith(":(") ? pathspec.substring(pathspec.indexOf(')') + 1) : pathspec;
    String shallow =
        spec.replace("**/", "").replace("/**", "/x.png").replace("**", "x").replace("*", "x")
            .replace("?", "x");
    String deep =
        spec.replace("**/", "a/b/").replace("/**", "/c/d.png").replace("**", "y").replace("*", "y")
            .replace("?", "y");
    return List.of(shallow, deep);
  }
}

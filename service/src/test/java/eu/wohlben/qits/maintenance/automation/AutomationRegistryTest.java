package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

  /**
   * <b>Per subject, for both build shapes</b> (qits-1516): the two {@code golden-recordings-*} kinds
   * both write {@code pacts/*.json}, and the root {@code pom.xml} decides which one names it — a
   * maven fold (with a {@code package.json} beside the pom) and an npm fold each give the path to
   * exactly one of them.
   */
  @Test
  void committablePathsAreDisjointPerSubjectForBothBuildShapes() {
    AutomationSubject maven = subject(Map.of("pom.xml", "<project/>", "package.json", "{}"));
    assertEquals(
        List.of(":(glob)pacts/*.json"),
        automations.kind(GoldenRecordingsMavenAutomation.KIND).orElseThrow().committablePaths(maven));
    assertEquals(
        List.of(),
        automations.kind(GoldenRecordingsNpmAutomation.KIND).orElseThrow().committablePaths(maven));
    assertDisjoint(automations.kinds(), maven);

    AutomationSubject npm = subject(Map.of("package.json", "{}"));
    assertEquals(
        List.of(":(glob)pacts/*.json"),
        automations.kind(GoldenRecordingsNpmAutomation.KIND).orElseThrow().committablePaths(npm));
    assertEquals(
        List.of(),
        automations.kind(GoldenRecordingsMavenAutomation.KIND).orElseThrow().committablePaths(npm));
    assertDisjoint(automations.kinds(), npm);
  }

  /** A subject whose fold holds exactly these root files, and nothing else. */
  private static AutomationSubject subject(Map<String, String> files) {
    GitHostReader host =
        new GitHostReader() {
          @Override
          public FileLookup blob(String project, String repository, String revision, String path) {
            String content = files.get(path);
            return content == null ? FileLookup.absent() : FileLookup.found(content);
          }
        };
    MtRepository repository = new MtRepository();
    repository.name = "repo-under-test";
    repository.project = "qits";
    repository.archetype = "SERVICE";
    String sha = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";
    return new AutomationSubject(
        repository, "request-1", sha, "release/request-1", List.of("work"), null,
        new FoldReader(host, "qits", "repo-under-test", sha));
  }

  /** Each kind in the stage its ticket put it in. */
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

  /**
   * <b>Every kind declares the approval roles epic qits-1038 decided</b> (qits-1045):
   * {@code screenshot-baselines} and {@code entity-diagram} admit an agent or a person —
   * generated content a release request would otherwise ship unreviewed — and {@code estate-pins}
   * and {@code dependency-bump}, which move internal pins only and are already gated by CI, admit
   * nobody — nor do qits-1516's two {@code golden-recordings-*} kinds, whose re-recorded pacts the
   * gate re-checks and the providers verify.
   */
  @Test
  void everyKindDeclaresItsApprovalRoles() {
    for (ReleaseRequestAutomation kind : automations.kinds()) {
      Set<String> expected =
          switch (kind.kind()) {
            case ScreenshotBaselinesAutomation.KIND, EntityDiagramAutomation.KIND ->
                Set.of("agent", "person");
            case EstatePinsAutomation.KIND,
                DependencyBumpAutomation.KIND,
                GoldenRecordingsMavenAutomation.KIND,
                GoldenRecordingsNpmAutomation.KIND ->
                Set.of();
            default ->
                throw new AssertionError(
                    "a new kind declares approvalRoles with no expectation pinned here: "
                        + kind.kind());
          };
      assertEquals(expected, kind.approvalRoles(), kind.kind());
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

package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Git's two pathspec readings, as carry-over applies them to the paths a fold changed. */
class PathspecsTest {

  private static final List<String> SCREENSHOTS =
      List.of(
          ":(glob)**/__screenshots__/**",
          ":(glob)**/testing/browser/renderer.txt",
          ":(glob)**/testing/browser/routes.json",
          ":(glob)**/testing/browser/freezes/**");

  @Test
  void globMagicSpansDirectoriesOnlyWithTwoStars() {
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "src/app/home/__screenshots__/home.png"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "__screenshots__/top.png"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "src/testing/browser/renderer.txt"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "testing/browser/renderer.txt"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "src/testing/browser/routes.json"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "testing/browser/routes.json"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "src/testing/browser/freezes/home.html"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "testing/browser/freezes/manifest.json"));
    assertFalse(Pathspecs.matchesAny(SCREENSHOTS, "src/app/x.ts"));
    assertFalse(Pathspecs.matchesAny(SCREENSHOTS, "src/app/__screenshots__"), "the directory alone");
    assertFalse(Pathspecs.matchesAny(SCREENSHOTS, "src/testing/browser/freezes"), "the directory alone");
    assertFalse(Pathspecs.matchesAny(SCREENSHOTS, "src/testing/browser/renderer.txt.bak"));
    assertFalse(Pathspecs.matchesAny(SCREENSHOTS, "src/testing/browser/routes.json.bak"));
    assertFalse(Pathspecs.matches(":(glob)src/*.ts", "src/app/x.ts"), "one star stays in a segment");
    assertTrue(Pathspecs.matches(":(glob)src/*.ts", "src/x.ts"));
  }

  @Test
  void aPlainPathspecIsThePathOrADirectoryAboveIt() {
    assertTrue(Pathspecs.matches("components/qits-ci/qits-ci-frontend", "components/qits-ci/qits-ci-frontend"));
    assertTrue(Pathspecs.matches("components/qits-ci", "components/qits-ci/qits-ci-frontend"));
    assertFalse(Pathspecs.matches("components/qits-ci", "components/qits-ci-old/x"));
    assertTrue(Pathspecs.matches("docs/*.md", "docs/a/b.md"), "a plain wildcard crosses slashes");
    assertFalse(Pathspecs.matches(":(literal)docs/*.md", "docs/a.md"));
    assertFalse(Pathspecs.matches(null, "x"));
  }

  /** Git's exclusion magic takes a path back out of a list (qits-1957). */
  @Test
  void anExclusionTakesAPathBackOutOfTheList() {
    List<String> sources =
        List.of(
            ":(glob)src/**",
            ":(exclude,glob)**/__screenshots__/**",
            ":(exclude,glob)**/testing/browser/renderer.txt");
    assertTrue(Pathspecs.matchesAny(sources, "src/testing/browser/routes.json"));
    assertTrue(Pathspecs.matchesAny(sources, "src/app/x/x.store.pact.spec.ts"));
    assertFalse(Pathspecs.matchesAny(sources, "src/app/x/__screenshots__/a.png"));
    assertFalse(Pathspecs.matchesAny(sources, "src/testing/browser/renderer.txt"));
    assertFalse(Pathspecs.matchesAny(sources, "README.md"), "nothing includes it");
    assertFalse(Pathspecs.matches(":(exclude,glob)**/__screenshots__/**", "a/__screenshots__/b"));
    assertFalse(Pathspecs.matches(":!docs", "src/x"), "an exclusion alone includes nothing");
  }

  @Test
  void theShortSpellingsAndAListOfExclusionsAloneReadAsGitReadsThem() {
    assertFalse(Pathspecs.matchesAny(List.of("src", ":!src/gen"), "src/gen/a.ts"));
    assertFalse(Pathspecs.matchesAny(List.of("src", ":^src/gen"), "src/gen/a.ts"));
    assertTrue(Pathspecs.matchesAny(List.of("src", ":!src/gen"), "src/app/a.ts"));
    assertTrue(Pathspecs.matchesAny(List.of(":(exclude)docs"), "src/x"), "everything else");
    assertFalse(Pathspecs.matchesAny(List.of(":(exclude)docs"), "docs/a.md"));
  }
}

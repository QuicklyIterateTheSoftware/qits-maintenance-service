package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Git's two pathspec readings, as carry-over applies them to the paths a fold changed. */
class PathspecsTest {

  private static final List<String> SCREENSHOTS =
      List.of(":(glob)**/__screenshots__/**", ":(glob)**/testing/browser/renderer.txt");

  @Test
  void globMagicSpansDirectoriesOnlyWithTwoStars() {
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "src/app/home/__screenshots__/home.png"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "__screenshots__/top.png"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "src/testing/browser/renderer.txt"));
    assertTrue(Pathspecs.matchesAny(SCREENSHOTS, "testing/browser/renderer.txt"));
    assertFalse(Pathspecs.matchesAny(SCREENSHOTS, "src/app/x.ts"));
    assertFalse(Pathspecs.matchesAny(SCREENSHOTS, "src/app/__screenshots__"), "the directory alone");
    assertFalse(Pathspecs.matchesAny(SCREENSHOTS, "src/testing/browser/renderer.txt.bak"));
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

  /** The runtime disjointness check (qits-1133): a path both lists claim, or null. */
  @Test
  void overlapNamesAPathBothListsClaim() {
    assertEquals("webui", Pathspecs.overlap(List.of("webui"), List.of("pom.xml", "webui")));
    assertEquals(
        "docs/database/x.png",
        Pathspecs.overlap(List.of(":(glob)docs/database/**"), List.of("docs")));
    assertNull(Pathspecs.overlap(List.of("pom.xml"), SCREENSHOTS));
    assertNull(Pathspecs.overlap(List.of(), List.of("pom.xml")));
    assertNull(Pathspecs.overlap(null, List.of("pom.xml")));
  }
}

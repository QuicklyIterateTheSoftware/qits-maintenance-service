package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.changelog.ChangelogRange;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What this side refuses before anything is sent, and what it deliberately does not.
 *
 * <p>Every rule here is about a value reaching a shell or a git ref, which is the same question
 * whichever step applies the change — so the validation knows nothing about ecosystems, and a
 * gitlink change passes it for exactly the same reasons a maven one does.
 */
class BumpPayloadTest {

  private static final String BRANCH = "maintenance/dependencies";

  private static List<String> problems(Change... changes) {
    return BumpPayload.problems("dependencies", BRANCH, "main", List.of(changes));
  }

  private static Change gitlink(String to, String path) {
    return Change.of(
        Ecosystem.GITLINK,
        path,
        "qits-artifacts-frontend",
        "aa11bb22cc33dd44ee55ff6677889900aabbccdd",
        to,
        "gitlink:" + path);
  }

  /**
   * A GITLINK CHANGE IS ADMITTED, and its two unusual halves are why this is worth asserting: the
   * {@code from} is a commit sha rather than a version (never a precondition, so never checked),
   * and the {@code manifestPath} is a directory that names no file on this side or the step's.
   */
  @Test
  void aGitlinkChangeIsAdmitted() {
    assertTrue(problems(gitlink("2026.901.1", "service/src/main/webui")).isEmpty());
  }

  @Test
  void aGitlinkChangeIsHeldToTheSameVersionAndPathRulesAsEveryOther() {
    assertEquals(1, problems(gitlink("2026.901.1; rm -rf /", "service/src/main/webui")).size());
    assertEquals(1, problems(gitlink("2026.901.1", "../somebody-elses-tree")).size());
    assertEquals(1, problems(gitlink("2026.901.1", "/etc/webui")).size());
  }

  /** A mixed bump is one payload, and every ecosystem's changes ride it together. */
  @Test
  void aMixedPayloadOfAllFourEcosystemsPasses() {
    assertTrue(
        problems(
                Change.of(Ecosystem.MAVEN, "pom.xml", "g:a", "1.0.0", "1.1.0", "property:a.version"),
                Change.of(Ecosystem.NPM, "package.json", "@qits/ui", "1.0.0", "1.1.0", "dependencies"),
                Change.of(Ecosystem.DOCKER, "Dockerfile", "qits/base", "1.0.0", "1.1.0", "line:3"),
                gitlink("2026.901.1", "service/src/main/webui"))
            .isEmpty());
  }

  @Test
  void everyProblemIsReportedRatherThanTheFirst() {
    List<String> problems =
        BumpPayload.problems(
            "not a group",
            "maintenance/two words",
            "main",
            List.of(gitlink("no spaces allowed", "service/src/main/webui")));

    assertEquals(3, problems.size(), problems.toString());
  }

  /**
   * <b>{@code replaceHead} licenses a leased force-push, so it is held to exactly what the step
   * admits</b> (qits-1081): a full lowercase sha, 40 hex or 64, or absent. Anything else is refused
   * here, where it is a sentence on the row, rather than there, where it is a red run.
   */
  @Test
  void aReplaceHeadMustBeAFullLowercaseSha() {
    Change change = gitlink("2026.901.1", "service/src/main/webui");
    String tag = "refs/tags/2026.1007.171656";
    assertTrue(
        BumpPayload.problems("dependencies", BRANCH, tag, null, List.of(change)).isEmpty(),
        "no replaceHead is the ordinary payload: continue the branch");
    assertTrue(
        BumpPayload.problems(
                "dependencies", BRANCH, tag, "aa11bb22cc33dd44ee55ff6677889900aabbccdd", List.of(change))
            .isEmpty());
    assertTrue(
        BumpPayload.problems("dependencies", BRANCH, tag, "ab".repeat(32), List.of(change))
            .isEmpty(),
        "a SHA-256 repository's object name is 64 hex, and the step admits it");

    for (String bad :
        List.of(
            "",
            "aa11bb2",
            "AA11BB22CC33DD44EE55FF6677889900AABBCCDD",
            "aa11bb22cc33dd44ee55ff6677889900aabbccdd0",
            "aa11bb22cc33dd44ee55ff6677889900aabbccdz",
            "aa11bb22cc33dd44ee55ff6677889900aabbccdd; git push --force")) {
      List<String> problems =
          BumpPayload.problems("dependencies", BRANCH, tag, bad, List.of(change));
      assertEquals(1, problems.size(), "'" + bad + "': " + problems);
      assertTrue(problems.get(0).contains("replace head"), problems.toString());
    }
  }

  /** A tag base is a plain ref like any other, and the step fetches a {@code refs/…} base as written. */
  @Test
  void aTagBaseIsAPlainRef() {
    Change change = gitlink("2026.901.1", "service/src/main/webui");
    assertTrue(
        BumpPayload.problems("dependencies", BRANCH, "refs/tags/2026.1007.171656", List.of(change))
            .isEmpty());
    assertEquals(
        1,
        BumpPayload.problems("dependencies", BRANCH, "refs/tags/../main", List.of(change)).size());
  }

  // --- the changelog field (qits-893) -------------------------------------------------------------

  private static List<String> changelogProblems(String repository, List<String> versions) {
    Change change = gitlink("2026.1008.1", "webui");
    return BumpPayload.changelogProblems(Map.of(change, new ChangelogRange(repository, versions)));
  }

  /** A catalog name and the platform's own calvers, unpadded — the shapes a release really has. */
  @Test
  void aChangelogOfACatalogNameAndCalversIsAdmitted() {
    assertTrue(
        changelogProblems("qits-ci-frontend", List.of("2026.1007.61854", "2026.1007.171656", "2026.919.5"))
            .isEmpty());
    assertTrue(BumpPayload.changelogProblems(Map.of()).isEmpty(), "no changelog is no problem");
  }

  @Test
  void aChangelogRepositoryIsHeldToTheCatalogNameShape() {
    for (String bad :
        List.of("", "Qits-ci", "-qits", "qits/ci", "qits_ci", "../qits", "a".repeat(129))) {
      List<String> problems = changelogProblems(bad, List.of("2026.1008.1"));
      assertEquals(1, problems.size(), "'" + bad + "': " + problems);
      assertTrue(problems.get(0).contains("changelog repository"), problems.toString());
    }
    assertEquals(1, changelogProblems(null, List.of("2026.1008.1")).size());
    assertTrue(changelogProblems("a".repeat(128), List.of("2026.1008.1")).isEmpty());
  }

  @Test
  void aChangelogVersionIsACalver() {
    for (String bad :
        List.of("", "1.2.3", "26.1008.1", "2026.10080.1", "2026.1008", "2026.1008.1-SNAPSHOT",
            "2026.1008.1; rm -rf /", "v2026.1008.1")) {
      List<String> problems = changelogProblems("qits-ci", List.of("2026.1001.1", bad));
      assertEquals(1, problems.size(), "'" + bad + "': " + problems);
      assertTrue(problems.get(0).contains("changelog version"), problems.toString());
    }
  }

  @Test
  void aChangelogNamesAtLeastOneVersion() {
    List<String> problems = changelogProblems("qits-ci", List.of());
    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("names no version"), problems.toString());
  }
}

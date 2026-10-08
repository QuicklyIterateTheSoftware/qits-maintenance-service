package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.List;
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
}

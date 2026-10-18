package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.GitHostRefs;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.List;

/**
 * <b>What qits-maintenance asks qits-githost, and why</b> (ticket qits-1149): the manifests a scan
 * reads at one revision ({@link GitHostReader}: root tree, directory, blob, recursive tree, and
 * whether main contains a released commit), and the automation branches a sweep lists and deletes
 * ({@link GitHostRefs}).
 *
 * <p><b>Every tree read binds {@code Git-Commit-Sha}</b>: a listing without it is unreachable to
 * the reader, which pins nothing at a revision it cannot name. A blob is read as text, which the
 * git host records no body for, so its row binds the status alone.
 *
 * <p><b>A 404 on a path is asked twice</b> — the root tree at the same revision tells a missing path
 * (ABSENT) from a missing commit (GONE). Those rows carry the root-tree read as a companion, served
 * by the same mock, and wait for it: qits-githost records the 404s in a state without the root tree.
 */
final class GitHostContract {

  static final GoldenMasters GITHOST = GoldenMasters.of("qits-githost-service", "qits-githost");

  static final String FILES_ON_MAIN = "a repository with files on main";
  static final String FILES_ON_MAIN_RECURSIVELY = "a repository with files on main, listed recursively";
  static final String A_WRAPPER_WITH_A_SUBMODULE = "a wrapper repository with a submodule";
  static final String MISSING_THE_COMMIT = "a repository missing the requested commit";
  static final String MISSING_THE_PATH = "a repository missing the requested path";
  static final String MAIN_CONTAINS_A_COMMIT = "a repository whose main contains a commit";
  static final String A_SIDE_BRANCH = "a repository with a side branch";
  static final String WITHOUT_THE_BRANCH = "a repository without the given branch";

  private static final Trigger SCAN = Trigger.schedule("ScanService.run");
  private static final Trigger SWEEP = Trigger.schedule("AutomationBranchSweep.sweep");
  private static final Trigger FOLD = Trigger.operation("triggerReleaseRequestAutomations");

  /** What every tree read keeps of an entry: its name and type, and a gitlink's mode and sha. */
  static final List<String> ENTRIES = List.of("entries[].name", "entries[].type");

  /** The root tree of the missing-path state: what the reader asks after a 404 on a path. */
  static final GoldenInteraction ROOT_OF_THE_MISSING_PATH =
      GoldenInteraction.of(SCAN, MISSING_THE_PATH, "getRootTree").readsHeader("Git-Commit-Sha");

  static final List<PeerRow> ROWS =
      List.of(
          new PeerRow(
              GoldenInteraction.of(SCAN, FILES_ON_MAIN, "getRootTree")
                  .consumes(ENTRIES)
                  .anyLength("entries")
                  .readsHeader("Git-Commit-Sha"),
              (peers, p) -> {
                TreeLookup root = peers.git().head(p.get("projectId"), p.get("repoName"), p.get("sha"));
                assertTrue(root.found(), root.message());
                assertEquals(p.get("sha"), root.headSha());
                assertFalse(root.entries().isEmpty());
              }),
          new PeerRow(
              GoldenInteraction.of(SCAN, FILES_ON_MAIN, "getTree")
                  .consumes(ENTRIES)
                  .anyLength("entries")
                  .readsHeader("Git-Commit-Sha"),
              (peers, p) -> {
                TreeLookup tree =
                    peers.git().tree(p.get("projectId"), p.get("repoName"), p.get("rev"), p.get("directory"));
                assertTrue(tree.found(), tree.message());
                assertFalse(tree.entries().isEmpty());
              }),
          new PeerRow(
              GoldenInteraction.of(SCAN, A_WRAPPER_WITH_A_SUBMODULE, "getTree")
                  .consumes("entries[].name", "entries[].type", "entries[].mode", "entries[].sha")
                  .anyLength("entries")
                  .readsHeader("Git-Commit-Sha"),
              (peers, p) -> {
                TreeLookup tree =
                    peers.git().tree(p.get("projectId"), p.get("repoName"), p.get("rev"), p.get("directory"));
                assertTrue(tree.found(), tree.message());
                assertTrue(tree.entries().stream().allMatch(TreeLookup.TreeEntry::isGitlink));
              }),
          new PeerRow(
              GoldenInteraction.of(SCAN, FILES_ON_MAIN, "getBlob"),
              (peers, p) -> {
                FileLookup file =
                    peers.git().blob(p.get("projectId"), p.get("repoName"), p.get("rev"), p.get("path"));
                assertTrue(file.found(), file.message());
              }),
          new PeerRow(
              GoldenInteraction.of(SCAN, MISSING_THE_COMMIT, "getRootTree"),
              (peers, p) ->
                  assertEquals(
                      FileLookup.Status.GONE,
                      peers.git().head(p.get("projectId"), p.get("repoName"), p.get("sha")).status())),
          new PeerRow(
              GoldenInteraction.of(SCAN, MISSING_THE_PATH, "getBlob"),
              (peers, p) ->
                  assertEquals(
                      FileLookup.Status.ABSENT,
                      peers
                          .git()
                          .blob(p.get("projectId"), p.get("repoName"), p.get("rev"), p.get("path"))
                          .status()),
              List.of(ROOT_OF_THE_MISSING_PATH)),
          new PeerRow(
              GoldenInteraction.of(SCAN, MISSING_THE_PATH, "getTree"),
              (peers, p) ->
                  assertEquals(
                      FileLookup.Status.ABSENT,
                      peers
                          .git()
                          .tree(p.get("projectId"), p.get("repoName"), p.get("rev"), p.get("directory"))
                          .status()),
              List.of(ROOT_OF_THE_MISSING_PATH)),
          new PeerRow(
              ROOT_OF_THE_MISSING_PATH,
              (peers, p) ->
                  assertTrue(
                      peers.git().head(p.get("projectId"), p.get("repoName"), p.get("rev")).found())),
          new PeerRow(
              GoldenInteraction.of(FOLD, FILES_ON_MAIN_RECURSIVELY, "getRootTree")
                  .consumes("recursive", "entries[].name", "entries[].type", "entries[].path")
                  .anyLength("entries")
                  .readsHeader("Git-Commit-Sha"),
              (peers, p) -> {
                TreeLookup tree =
                    peers.git().recursiveTree(p.get("projectId"), p.get("repoName"), p.get("rev", "main"));
                assertTrue(tree.found(), tree.message());
                assertTrue(tree.recursive());
              }),
          new PeerRow(
              GoldenInteraction.of(SCAN, MAIN_CONTAINS_A_COMMIT, "containsCommit").consumes("contains"),
              (peers, p) -> {
                GitHostReader.Containment containment =
                    peers.git().contains(p.get("repositoryId"), p.get("commit"), p.get("in"));
                assertTrue(containment.readable(), containment.error());
                assertTrue(containment.contains());
              }),
          new PeerRow(
              GoldenInteraction.of(SWEEP, A_SIDE_BRANCH, "getRepository").consumes("branches[]").anyLength("branches"),
              (peers, p) -> {
                GitHostRefs.Branches branches = peers.refs().branches(p.get("repositoryId"));
                assertTrue(branches.ok(), branches.error());
                assertFalse(branches.branches().isEmpty());
              }),
          new PeerRow(
              GoldenInteraction.of(SWEEP, A_SIDE_BRANCH, "deleteBranch"),
              (peers, p) ->
                  assertEquals(
                      GitHostRefs.Deleted.DELETED,
                      peers
                          .refs()
                          .delete(p.get("repositoryId"), p.get("projectId"), p.get("repoName"), p.get("branch"))
                          .outcome())),
          new PeerRow(
              GoldenInteraction.of(SWEEP, WITHOUT_THE_BRANCH, "deleteBranch")
                  .consumes("error")
                  .exact("error"),
              (peers, p) ->
                  assertEquals(
                      GitHostRefs.Deleted.ALREADY_GONE,
                      peers
                          .refs()
                          .delete(p.get("repositoryId"), p.get("projectId"), p.get("repoName"), p.get("branch"))
                          .outcome())));

  static final PeerRow.Contract CONTRACT = new PeerRow.Contract(GITHOST, ROWS);

  private GitHostContract() {}
}

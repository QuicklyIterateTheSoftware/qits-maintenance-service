package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>{@link CliTapesAutomation#applicability}</b>, over a fake {@link GitHostReader} — no Quarkus,
 * no network, the same seam {@link EntityDiagramApplicabilityTest} reads the git host through. One
 * file settles it: {@code tapes/prepare.sh} present, absent or unreadable.
 */
class CliTapesApplicabilityTest {

  private static final String PROJECT = "qits";

  private static final String REPOSITORY = "repo-under-test";

  private static final String SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";

  private final CliTapesAutomation automation = new CliTapesAutomation();

  /** A git host holding one revision's blobs, absent for everything else. */
  private static final class Host extends GitHostReader {

    private final Map<String, FileLookup> blobs = new LinkedHashMap<>();

    Host blob(String path, String content) {
      blobs.put(path, FileLookup.found(content));
      return this;
    }

    Host blobUnreachable(String path, String message) {
      blobs.put(path, FileLookup.unreachable(message));
      return this;
    }

    @Override
    public FileLookup blob(String project, String repository, String revision, String path) {
      return blobs.getOrDefault(path, FileLookup.absent());
    }
  }

  private static Applicability applicabilityOf(CliTapesAutomation automation, Host host) {
    FoldReader fold = new FoldReader(host, PROJECT, REPOSITORY, SHA);
    MtRepository repository = new MtRepository();
    repository.name = REPOSITORY;
    repository.project = PROJECT;
    AutomationSubject subject =
        new AutomationSubject(
            repository, "request-1", SHA, "release/request-1", List.of("work"), null, fold);
    return automation.applicability(subject);
  }

  /** A repository that opted in by writing the hook: applies. */
  @Test
  void aRepositoryWithPrepareShApplies() {
    Host host = new Host().blob(CliTapesAutomation.PREPARE_SCRIPT, "#!/bin/sh\nset -eu\n");

    assertTrue(applicabilityOf(automation, host).applied());
  }

  /** A repository with no tapes to play: not applicable, and says why. */
  @Test
  void aRepositoryWithNoPrepareShIsNotApplicable() {
    Host host = new Host();

    Applicability applicability = applicabilityOf(automation, host);

    assertFalse(applicability.applied());
    assertEquals(
        "the fold has no " + CliTapesAutomation.PREPARE_SCRIPT + ", so the repository has no CLI tapes",
        applicability.reason());
  }

  /** A git-host fault settles nothing: UNKNOWN, never a guess. */
  @Test
  void aGitHostErrorIsUnknown() {
    Host host = new Host().blobUnreachable(CliTapesAutomation.PREPARE_SCRIPT, "connection refused");

    Applicability applicability = applicabilityOf(automation, host);

    assertEquals(Applicability.State.UNKNOWN, applicability.state());
    assertEquals("connection refused", applicability.reason());
  }

  @Test
  void committablePathsAreTheGoldensAndTheRecordings() {
    assertEquals(
        List.of(":(glob)tapes/golden/**", ":(glob)tapes/recordings/**"),
        automation.committablePaths());
  }

  @Test
  void isOwnBranchDerivedAndCheckedByQa() {
    assertEquals(Target.OWN_BRANCH, automation.target());
    assertEquals(Stage.DERIVED, automation.stage());
    assertTrue(automation.checkedByQa());
  }

  @Test
  void approvalRolesAdmitAnAgentOrAPerson() {
    assertEquals(java.util.Set.of("agent", "person"), automation.approvalRoles());
  }
}

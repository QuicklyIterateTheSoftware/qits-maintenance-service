package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>{@link EntityExposureAutomation#applicability}</b> (qits-1958), over a fake {@link
 * GitHostReader} — no Quarkus, no network. The fold's {@code .config/qits/release.yml} decides, by
 * its parsed {@code contracts.entities}: declared applies, everything short of a declaration is
 * NOT_APPLICABLE with a reason, and a git host that did not answer is UNKNOWN.
 */
class EntityExposureApplicabilityTest {

  private static final String PROJECT = "qits";

  private static final String REPOSITORY = "repo-under-test";

  private static final String SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";

  private static final String RELEASE_YML = ".config/qits/release.yml";

  private final EntityExposureAutomation automation = new EntityExposureAutomation();

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

  private static AutomationSubject subject(Host host) {
    FoldReader fold = new FoldReader(host, PROJECT, REPOSITORY, SHA);
    MtRepository repository = new MtRepository();
    repository.name = REPOSITORY;
    repository.project = PROJECT;
    return new AutomationSubject(
        repository, "request-1", SHA, "release/request-1", List.of("work"), null, fold);
  }

  private Applicability applicabilityOf(Host host) {
    return automation.applicability(subject(host));
  }

  /** qits-maintenance-service's own shape: the conventional path, beside golden masters and pacts. */
  @Test
  void aDeclaredEntitiesPathApplies() {
    Host host =
        new Host()
            .blob(
                RELEASE_YML,
                """
                archetype: java-service
                contracts:
                  application: qits-maintenance
                  golden-masters: { from: golden-masters/, packages: [maven, npm] }
                  entities: docs/entities.json
                  pacts:
                    qits-projects-service: { packages: [maven] }
                """);

    assertTrue(applicabilityOf(host).applied());
    assertEquals(List.of("docs/entities.json"), automation.committablePaths(subject(host)));
  }

  @Test
  void noReleaseYmlIsNotApplicable() {
    Applicability answer = applicabilityOf(new Host());

    assertEquals(Applicability.State.NOT_APPLICABLE, answer.state());
    assertTrue(answer.reason().contains("no .config/qits/release.yml"), answer.reason());
  }

  @Test
  void aReleaseYmlWithNoContractsIsNotApplicable() {
    Applicability answer = applicabilityOf(new Host().blob(RELEASE_YML, "archetype: java-service\n"));

    assertEquals(Applicability.State.NOT_APPLICABLE, answer.state());
    assertTrue(answer.reason().contains("declares no contracts.entities"), answer.reason());
  }

  /** Golden masters and pacts, but no map: what every service declared before qits-1945. */
  @Test
  void contractsWithNoEntitiesKeyIsNotApplicable() {
    Host host =
        new Host()
            .blob(
                RELEASE_YML,
                """
                archetype: java-service
                contracts:
                  application: qits-ci
                  golden-masters: { from: golden-masters/, packages: [maven] }
                  # entities: docs/entities.json
                """);

    Applicability answer = applicabilityOf(host);

    assertEquals(Applicability.State.NOT_APPLICABLE, answer.state());
    assertTrue(answer.reason().contains("declares no contracts.entities"), answer.reason());
  }

  @Test
  void aBlankEntitiesPathIsNotApplicable() {
    Host host = new Host().blob(RELEASE_YML, "contracts:\n  entities: \"  \"\n");

    Applicability answer = applicabilityOf(host);

    assertEquals(Applicability.State.NOT_APPLICABLE, answer.state());
    assertTrue(answer.reason().contains("not a non-blank path"), answer.reason());
    assertEquals(
        Applicability.State.NOT_APPLICABLE,
        applicabilityOf(new Host().blob(RELEASE_YML, "contracts:\n  entities:\n")).state());
  }

  /** A path qits-ci's postlude would refuse to stage is no map this kind can commit. */
  @Test
  void anImplausiblePathIsNotApplicable() {
    for (String path : List.of("/etc/entities.json", "../elsewhere/entities.json", "docs/*.json")) {
      Host host = new Host().blob(RELEASE_YML, "contracts:\n  entities: \"" + path + "\"\n");
      assertEquals(Applicability.State.NOT_APPLICABLE, applicabilityOf(host).state(), path);
    }
  }

  @Test
  void invalidYamlIsNotApplicable() {
    Host host = new Host().blob(RELEASE_YML, "contracts: [unclosed\n");

    assertEquals(Applicability.State.NOT_APPLICABLE, applicabilityOf(host).state());
  }

  @Test
  void aGitHostErrorIsUnknown() {
    Host host = new Host().blobUnreachable(RELEASE_YML, "git host timed out");

    Applicability answer = applicabilityOf(host);

    assertEquals(Applicability.State.UNKNOWN, answer.state());
    assertEquals("git host timed out", answer.reason());
  }

  /** The declared path is exactly what a run may commit; the convention only where none is read. */
  @Test
  void committablePathsAreTheDeclaredPath() {
    Host custom = new Host().blob(RELEASE_YML, "contracts:\n  entities: api/exposure/map.json\n");

    assertTrue(applicabilityOf(custom).applied());
    assertEquals(List.of("api/exposure/map.json"), automation.committablePaths(subject(custom)));
    assertEquals(List.of("docs/entities.json"), automation.committablePaths(subject(new Host())));
    assertEquals(
        List.of("docs/entities.json"),
        automation.committablePaths(
            subject(new Host().blobUnreachable(RELEASE_YML, "git host timed out"))));
    assertEquals(List.of("docs/entities.json"), automation.committablePaths());
  }

  /** Its output is never its input: the re-fold of its own commit carries over. */
  @Test
  void theMapIsNoInputAndTheKindRunsBeforeQa() {
    assertFalse(Pathspecs.matchesAny(automation.inputPaths(), "docs/entities.json"));
    assertTrue(Pathspecs.matchesAny(automation.inputPaths(), "service/pom.xml"));
    assertTrue(Pathspecs.matchesAny(automation.inputPaths(), "service/src/main/java/A.java"));
    assertTrue(Pathspecs.matchesAny(automation.inputPaths(), RELEASE_YML));
    assertEquals("entity-exposure", automation.kind());
    assertEquals(CiClient.AUTOMATION_EVENT_NAME, automation.pipeline());
    assertEquals(Target.OWN_BRANCH, automation.target());
    assertEquals(Stage.DERIVED, automation.stage());
    assertFalse(automation.checkedByQa());
    assertEquals(Set.of("agent", "person"), automation.approvalRoles());
  }
}

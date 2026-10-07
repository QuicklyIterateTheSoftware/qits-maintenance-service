package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>{@link EntityDiagramAutomation#applicability}</b>, over a fake {@link GitHostReader} — no
 * Quarkus, no network, the same seam {@code ManifestScannerTest} reads the git host through. Each
 * case is one shape off the epic's measured inventory: a multi-module service (qits-projects), a
 * library followed two levels deep (registries), a single-module service (qits-mirror), edge's
 * ORM-less shape, an npm repository, a stale generated file after the last entity went, a git-host
 * fault, and the one XML-comment trap a real parser — never a substring match — refuses.
 */
class EntityDiagramApplicabilityTest {

  private static final String PROJECT = "qits";

  private static final String REPOSITORY = "repo-under-test";

  private static final String SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";

  private static final String GENERATED_HEADER =
      EntityDiagramAutomation.HEADER_PREFIX
          + " (jpa). Do not edit: the entity-diagram automation rewrites this file on every"
          + " release request fold. -->\n"
          + "# Persistence unit `ci`\n";

  private final EntityDiagramAutomation automation = new EntityDiagramAutomation();

  /** A git host holding one revision's blobs and tree listings, absent for everything else. */
  private static final class Host extends GitHostReader {

    private final Map<String, FileLookup> blobs = new LinkedHashMap<>();
    private final Map<String, TreeLookup> trees = new LinkedHashMap<>();

    Host blob(String path, String content) {
      blobs.put(path, FileLookup.found(content));
      return this;
    }

    Host blobUnreachable(String path, String message) {
      blobs.put(path, FileLookup.unreachable(message));
      return this;
    }

    Host tree(String path, TreeLookup.TreeEntry... entries) {
      trees.put(path, TreeLookup.found(SHA, List.of(entries)));
      return this;
    }

    @Override
    public FileLookup blob(String project, String repository, String revision, String path) {
      return blobs.getOrDefault(path, FileLookup.absent());
    }

    @Override
    public TreeLookup tree(String project, String repository, String revision, String path) {
      return trees.getOrDefault(path, TreeLookup.absent());
    }
  }

  private static Applicability applicabilityOf(EntityDiagramAutomation automation, Host host) {
    FoldReader fold = new FoldReader(host, PROJECT, REPOSITORY, SHA);
    MtRepository repository = new MtRepository();
    repository.name = REPOSITORY;
    repository.project = PROJECT;
    AutomationSubject subject =
        new AutomationSubject(
            repository, "request-1", SHA, "release/request-1", List.of("work"), null, fold);
    return automation.applicability(subject);
  }

  /** qits-projects-service's shape: a multi-module repository, the ORM dependency one level down. */
  @Test
  void aMultiModuleRepositoryWithTheDependencyInAModulePomApplies() {
    Host host =
        new Host()
            .blob(
                "pom.xml",
                """
                <project>
                  <modules>
                    <module>domain</module>
                    <module>service</module>
                  </modules>
                  <dependencies>
                    <dependency>
                      <groupId>eu.wohlben.qits</groupId>
                      <artifactId>qits-eventstream</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """)
            .blob(
                "domain/pom.xml",
                """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-hibernate-orm-panache</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

    assertTrue(applicabilityOf(automation, host).applied());
  }

  /** A library's shape: the dependency sits two levels below the root, in a module's own module. */
  @Test
  void aLibraryWithTheDependencyTwoLevelsDownApplies() {
    Host host =
        new Host()
            .blob(
                "pom.xml",
                """
                <project>
                  <modules>
                    <module>blobstore</module>
                  </modules>
                </project>
                """)
            .blob(
                "blobstore/pom.xml",
                """
                <project>
                  <modules>
                    <module>maven</module>
                  </modules>
                </project>
                """)
            .blob(
                "blobstore/maven/pom.xml",
                """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.hibernate.orm</groupId>
                      <artifactId>hibernate-core</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

    assertTrue(applicabilityOf(automation, host).applied());
  }

  /** qits-mirror-service's shape: a single-module pom, the dependency at the root itself. */
  @Test
  void aSingleModulePomWithTheDependencyAtTheRootApplies() {
    Host host =
        new Host()
            .blob(
                "pom.xml",
                """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-hibernate-orm</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

    assertTrue(applicabilityOf(automation, host).applied());
  }

  /** edge's shape: a pom with only the eventstream dependency, and no generated diagram to delete. */
  @Test
  void edgesShapeWithOnlyTheEventstreamDependencyIsNotApplicable() {
    Host host = new Host().blob("pom.xml", eventstreamOnlyPom());

    Applicability applicability = applicabilityOf(automation, host);

    assertFalse(applicability.applied());
    assertEquals("no JPA/Hibernate dependency", applicability.reason());
  }

  /** An npm repository: no pom.xml, and no generated diagram either. */
  @Test
  void anNpmRepositoryWithNoPomXmlIsNotApplicable() {
    Host host = new Host();

    Applicability applicability = applicabilityOf(automation, host);

    assertFalse(applicability.applied());
    assertEquals(
        "no pom.xml: only JPA/Hibernate entity diagrams are generated", applicability.reason());
  }

  /**
   * The last entity's dependency is gone, but the tree still carries the generated diagram from
   * before: regenerating it one more time is how it is deleted, so this still applies.
   */
  @Test
  void aStaleGeneratedFileWithNoOrmDependencyStillApplies() {
    Host host =
        new Host()
            .blob("pom.xml", eventstreamOnlyPom())
            .tree("docs/database", new TreeLookup.TreeEntry("x.md", "blob"))
            .blob("docs/database/x.md", GENERATED_HEADER);

    assertTrue(applicabilityOf(automation, host).applied());
  }

  /** A git-host fault settles nothing: UNKNOWN, never a guess. */
  @Test
  void aGitHostErrorIsUnknown() {
    Host host = new Host().blobUnreachable("pom.xml", "connection refused");

    Applicability applicability = applicabilityOf(automation, host);

    assertEquals(Applicability.State.UNKNOWN, applicability.state());
    assertEquals("connection refused", applicability.reason());
  }

  /** An artifactId sitting only inside an XML comment is not a declared dependency. */
  @Test
  void anArtifactIdOnlyInsideAnXmlCommentDoesNotApply() {
    Host host =
        new Host()
            .blob(
                "pom.xml",
                """
                <project>
                  <!--
                  <dependencies>
                    <dependency>
                      <groupId>org.hibernate.orm</groupId>
                      <artifactId>hibernate-core</artifactId>
                    </dependency>
                  </dependencies>
                  -->
                  <dependencies>
                    <dependency>
                      <groupId>eu.wohlben.qits</groupId>
                      <artifactId>qits-eventstream</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

    Applicability applicability = applicabilityOf(automation, host);

    assertFalse(applicability.applied());
    assertEquals("no JPA/Hibernate dependency", applicability.reason());
  }

  private static String eventstreamOnlyPom() {
    return """
        <project>
          <dependencies>
            <dependency>
              <groupId>eu.wohlben.qits</groupId>
              <artifactId>qits-eventstream</artifactId>
            </dependency>
          </dependencies>
        </project>
        """;
  }
}

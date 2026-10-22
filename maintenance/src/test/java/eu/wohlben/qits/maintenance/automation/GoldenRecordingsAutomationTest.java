package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code golden-recordings-maven} and {@code golden-recordings-npm}</b> (qits-1516), over a fake
 * {@link GitHostReader} and a scripted {@link ManifestScanner} — no Quarkus, no network: the build
 * shape that splits the two, the golden-masters pin that makes either apply, the per-subject pacts
 * path, and the plan's comparison of the fold's pins against {@code main}'s.
 */
class GoldenRecordingsAutomationTest {

  private static final String PROJECT = "qits";

  private static final String REPOSITORY = "repo-under-test";

  private static final String SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";

  /** qits-maintenance-service's shape: the property in the root, the dependency in a module. */
  private static final String POM_WITH_PROPERTY =
      """
      <project>
        <properties>
          <qits.githost-golden-masters.version>2026.1020.204038</qits.githost-golden-masters.version>
        </properties>
        <modules><module>service</module></modules>
      </project>
      """;

  private static final String POM_WITHOUT =
      """
      <project>
        <properties><quarkus.version>3.34.6</quarkus.version></properties>
        <modules><module>service</module></modules>
      </project>
      """;

  private static final String MODULE_WITH_DEPENDENCY =
      """
      <project>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-idp-golden-masters</artifactId>
            <scope>test</scope>
          </dependency>
        </dependencies>
      </project>
      """;

  private static final String PACKAGE_WITH =
      "{\"name\":\"app\",\"devDependencies\":{\"@qits/projects-golden-masters\":\"2026.1022.1\"}}";

  private static final String PACKAGE_WITHOUT =
      "{\"name\":\"app\",\"devDependencies\":{\"@qits/angular\":\"2026.1022.1\"}}";

  private final GoldenRecordingsMavenAutomation maven = new GoldenRecordingsMavenAutomation();

  private final GoldenRecordingsNpmAutomation npm = new GoldenRecordingsNpmAutomation();

  /** A git host holding one revision's blobs, absent for everything else. */
  private static final class Host extends GitHostReader {

    private final Map<String, FileLookup> blobs = new LinkedHashMap<>();

    Host blob(String path, String content) {
      blobs.put(path, FileLookup.found(content));
      return this;
    }

    Host blobUnreachable(String path) {
      blobs.put(path, FileLookup.unreachable("connection refused"));
      return this;
    }

    @Override
    public FileLookup blob(String project, String repository, String revision, String path) {
      return blobs.getOrDefault(path, FileLookup.absent());
    }

    @Override
    public TreeLookup tree(String project, String repository, String revision, String path) {
      return TreeLookup.absent();
    }
  }

  /** The scan's discovery, scripted per revision: the fold's sha and {@code main}. */
  private static final class Scanner extends ManifestScanner {

    private final Map<String, ManifestScanner.Pins> byRevision = new HashMap<>();

    Scanner at(String revision, ParsedPin... pins) {
      byRevision.put(
          revision, new ManifestScanner.Pins(FileLookup.Status.FOUND, revision, List.of(pins), null));
      return this;
    }

    Scanner unreachable(String revision) {
      byRevision.put(
          revision,
          new ManifestScanner.Pins(FileLookup.Status.UNREACHABLE, null, List.of(), "refused"));
      return this;
    }

    @Override
    public Pins pinsAt(String project, String name, String revision) {
      return byRevision.getOrDefault(
          revision, new ManifestScanner.Pins(FileLookup.Status.GONE, null, List.of(), null));
    }
  }

  private static AutomationSubject subject(Host host) {
    MtRepository repository = new MtRepository();
    repository.name = REPOSITORY;
    repository.project = PROJECT;
    repository.mainBranch = "main";
    return new AutomationSubject(
        repository,
        "request-1",
        SHA,
        "release/request-1",
        List.of("work"),
        null,
        new FoldReader(host, PROJECT, REPOSITORY, SHA));
  }

  private static ParsedPin mavenGolden(String version) {
    return new ParsedPin(
        Ecosystem.MAVEN,
        "pom.xml",
        "eu.wohlben.qits:qits-githost-golden-masters",
        version,
        null,
        "property:qits.githost-golden-masters.version",
        false);
  }

  private static ParsedPin mavenOther(String version) {
    return new ParsedPin(
        Ecosystem.MAVEN,
        "pom.xml",
        "eu.wohlben.qits:qits-eventstream",
        version,
        null,
        "property:qits.eventstream.version",
        false);
  }

  private static ParsedPin npmGolden(String version) {
    return ParsedPin.of(
        Ecosystem.NPM, "package.json", "@qits/projects-golden-masters", version, "^" + version,
        "devDependencies");
  }

  // --- the static declarations ---------------------------------------------------------------

  @Test
  void bothKindsAreDerivedOwnBranchKindsWithNoStaticPathsAndNoApproval() {
    for (GoldenRecordingsAutomation kind : List.of(maven, npm)) {
      assertEquals(Stage.DERIVED, kind.stage(), kind.kind());
      assertEquals(Target.OWN_BRANCH, kind.target(), kind.kind());
      assertEquals(List.of(), kind.committablePaths(), kind.kind());
      assertEquals(Set.of(), kind.approvalRoles(), kind.kind());
      assertEquals(DependencyBumpAutomation.MANIFESTS, kind.inputPaths(), kind.kind());
      assertEquals(false, kind.planIsTheChangeList(), kind.kind());
      assertEquals(false, kind.checkedByQa(), kind.kind());
    }
    assertEquals("golden-recordings-maven", maven.kind());
    assertEquals("golden-recordings-npm", npm.kind());
  }

  // --- maven ---------------------------------------------------------------------------------

  @Test
  void aRootPomWithAGoldenMastersPropertyAppliesToMavenOnly() {
    AutomationSubject subject = subject(new Host().blob("pom.xml", POM_WITH_PROPERTY));

    assertTrue(maven.applicability(subject).applied());
    assertEquals(Applicability.State.NOT_APPLICABLE, npm.applicability(subject).state());
    assertEquals(List.of(":(glob)pacts/*.json"), maven.committablePaths(subject));
    assertEquals(List.of(), npm.committablePaths(subject));
  }

  @Test
  void aGoldenMastersDependencyInAModulePomApplies() {
    AutomationSubject subject =
        subject(
            new Host().blob("pom.xml", POM_WITHOUT).blob("service/pom.xml", MODULE_WITH_DEPENDENCY));

    assertTrue(maven.applicability(subject).applied());
  }

  @Test
  void aReactorThatPinsNoGoldenMastersIsNotApplicableButStillOwnsThePactsPath() {
    AutomationSubject subject =
        subject(new Host().blob("pom.xml", POM_WITHOUT).blob("service/pom.xml", "<project/>"));

    Applicability answer = maven.applicability(subject);
    assertEquals(Applicability.State.NOT_APPLICABLE, answer.state());
    assertTrue(answer.reason().contains("golden-masters"), answer.reason());
    assertEquals(List.of(":(glob)pacts/*.json"), maven.committablePaths(subject));
  }

  @Test
  void anUnreadablePomIsUnknownToBothKinds() {
    AutomationSubject subject = subject(new Host().blobUnreachable("pom.xml"));

    assertEquals(Applicability.State.UNKNOWN, maven.applicability(subject).state());
    assertEquals(Applicability.State.UNKNOWN, npm.applicability(subject).state());
    assertEquals(List.of(), maven.committablePaths(subject));
    assertEquals(List.of(), npm.committablePaths(subject));
  }

  @Test
  void anUnreadableModulePomIsUnknown() {
    AutomationSubject subject =
        subject(new Host().blob("pom.xml", POM_WITHOUT).blobUnreachable("service/pom.xml"));

    assertEquals(Applicability.State.UNKNOWN, maven.applicability(subject).state());
  }

  // --- npm -----------------------------------------------------------------------------------

  @Test
  void aRootPackageJsonWithAGoldenMastersDependencyAndNoPomAppliesToNpmOnly() {
    AutomationSubject subject = subject(new Host().blob("package.json", PACKAGE_WITH));

    assertTrue(npm.applicability(subject).applied());
    assertEquals(Applicability.State.NOT_APPLICABLE, maven.applicability(subject).state());
    assertEquals(List.of(":(glob)pacts/*.json"), npm.committablePaths(subject));
    assertEquals(List.of(), maven.committablePaths(subject));
  }

  @Test
  void aPackageJsonBesideARootPomIsMavensNotNpms() {
    AutomationSubject subject =
        subject(new Host().blob("pom.xml", POM_WITH_PROPERTY).blob("package.json", PACKAGE_WITH));

    Applicability answer = npm.applicability(subject);
    assertEquals(Applicability.State.NOT_APPLICABLE, answer.state());
    assertTrue(answer.reason().contains("pom.xml"), answer.reason());
    assertEquals(List.of(), npm.committablePaths(subject));
  }

  @Test
  void aPackageJsonWithNoGoldenMastersIsNotApplicable() {
    AutomationSubject subject = subject(new Host().blob("package.json", PACKAGE_WITHOUT));

    assertEquals(Applicability.State.NOT_APPLICABLE, npm.applicability(subject).state());
  }

  @Test
  void aFoldWithNeitherManifestIsNotApplicableToEither() {
    AutomationSubject subject = subject(new Host());

    assertEquals(Applicability.State.NOT_APPLICABLE, npm.applicability(subject).state());
    assertEquals(Applicability.State.NOT_APPLICABLE, maven.applicability(subject).state());
    assertEquals(List.of(), npm.committablePaths(subject));
    assertEquals(List.of(), maven.committablePaths(subject));
  }

  // --- the plan ------------------------------------------------------------------------------

  @Test
  void mavenPinsEqualToMainAreFreshWhateverElseMoved() {
    maven.scanner =
        new Scanner()
            .at(SHA, mavenGolden("2026.1020.204038"), mavenOther("2026.1022.2"))
            .at("main", mavenGolden("2026.1020.204038"), mavenOther("2026.1001.1"));

    Plan plan = maven.plan(subject(new Host().blob("pom.xml", POM_WITH_PROPERTY)));

    assertEquals(Plan.Kind.FRESH, plan.kind(), plan.reason());
  }

  @Test
  void aMovedMavenGoldenMastersPinRuns() {
    maven.scanner =
        new Scanner()
            .at(SHA, mavenGolden("2026.1022.1"))
            .at("main", mavenGolden("2026.1020.204038"));

    Plan plan = maven.plan(subject(new Host().blob("pom.xml", POM_WITH_PROPERTY)));

    assertEquals(Plan.Kind.RUN, plan.kind());
    assertEquals(1, plan.runs().size());
    assertEquals(Map.of(), plan.runs().getFirst().extras(), "commitPaths are the kind's own");
  }

  @Test
  void aGoldenMastersPinNewAtTheFoldRuns() {
    maven.scanner = new Scanner().at(SHA, mavenGolden("2026.1022.1")).at("main");

    assertEquals(
        Plan.Kind.RUN, maven.plan(subject(new Host().blob("pom.xml", POM_WITH_PROPERTY))).kind());
  }

  @Test
  void aMainWhosePinsCannotBeReadRuns() {
    maven.scanner = new Scanner().at(SHA, mavenGolden("2026.1022.1")).unreachable("main");

    assertEquals(
        Plan.Kind.RUN, maven.plan(subject(new Host().blob("pom.xml", POM_WITH_PROPERTY))).kind());
  }

  @Test
  void aFoldWhosePinsCannotBeReadIsUnknown() {
    maven.scanner = new Scanner().unreachable(SHA).at("main", mavenGolden("2026.1022.1"));

    assertEquals(
        Plan.Kind.UNKNOWN,
        maven.plan(subject(new Host().blob("pom.xml", POM_WITH_PROPERTY))).kind());
  }

  @Test
  void npmComparesOnlyItsOwnEcosystem() {
    npm.scanner =
        new Scanner()
            .at(SHA, npmGolden("2026.1022.1"), mavenGolden("2026.1022.9"))
            .at("main", npmGolden("2026.1022.1"), mavenGolden("2026.1001.1"));
    AutomationSubject subject = subject(new Host().blob("package.json", PACKAGE_WITH));

    assertEquals(Plan.Kind.FRESH, npm.plan(subject).kind());

    npm.scanner =
        new Scanner().at(SHA, npmGolden("2026.1022.2")).at("main", npmGolden("2026.1022.1"));
    assertEquals(Plan.Kind.RUN, npm.plan(subject).kind());
  }
}

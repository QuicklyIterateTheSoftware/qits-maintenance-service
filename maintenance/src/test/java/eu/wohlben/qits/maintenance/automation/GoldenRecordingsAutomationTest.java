package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code golden-recordings-maven} and {@code golden-recordings-npm}</b> (qits-1516), over a fake
 * {@link GitHostReader} and a scripted {@link ManifestScanner} — no Quarkus, no network: the build
 * shape that splits the two, the golden-masters pin that makes either apply, the per-subject pacts
 * path, and the plan's comparison of the fold's pins against {@code main}'s — and, since qits-1782,
 * the three-part library-kit pacts that make a library's pin a recording input too, and, since
 * qits-1957, the consumer's own recording sources compared tree against tree.
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

    private final Map<String, TreeLookup> trees = new LinkedHashMap<>();

    /** A {@code pacts/} directory holding exactly these blobs. */
    Host pacts(String... names) {
      trees.put(
          "pacts",
          TreeLookup.found(
              SHA,
              java.util.Arrays.stream(names)
                  .map(name -> new TreeLookup.TreeEntry(name, "blob"))
                  .toList()));
      return this;
    }

    Host pactsUnreachable() {
      trees.put("pacts", TreeLookup.unreachable("connection refused"));
      return this;
    }

    /** Each library repository's {@code release.yml} at its {@code main}, by repository. */
    private final Map<String, FileLookup> releaseYmls = new LinkedHashMap<>();

    /** Every {@code release.yml} read, as {@code "<project> <repository> <revision>"}. */
    final List<String> releaseYmlReads = new ArrayList<>();

    Host releaseYml(String repository, String content) {
      releaseYmls.put(repository, FileLookup.found(content));
      return this;
    }

    Host releaseYmlUnreachable(String repository) {
      releaseYmls.put(repository, FileLookup.unreachable("connection refused"));
      return this;
    }

    @Override
    public FileLookup blob(String project, String repository, String revision, String path) {
      if (QaChecks.RELEASE_YML.equals(path) && !REPOSITORY.equals(repository)) {
        releaseYmlReads.add(project + " " + repository + " " + revision);
        return releaseYmls.getOrDefault(repository, FileLookup.absent());
      }
      return blobs.getOrDefault(path, FileLookup.absent());
    }

    @Override
    public TreeLookup tree(String project, String repository, String revision, String path) {
      return trees.getOrDefault(path, TreeLookup.absent());
    }

    /**
     * Whole trees by revision (qits-1957). A revision with none is an empty recursive tree: no
     * sources on either side, so a test that is not about them compares equal sources.
     */
    private final Map<String, TreeLookup> wholeTrees = new HashMap<>();

    /** Every whole tree read, by revision. */
    final List<String> wholeTreeReads = new ArrayList<>();

    /** A recursive tree at {@code revision} holding these blobs, path to sha. */
    Host wholeTree(String revision, Map<String, String> blobs) {
      wholeTrees.put(
          revision,
          TreeLookup.foundRecursive(
              SHA,
              blobs.entrySet().stream()
                  .map(
                      blob -> {
                        String path = blob.getKey();
                        return new TreeLookup.TreeEntry(
                            path.substring(path.lastIndexOf('/') + 1), "blob", "100644",
                            blob.getValue(), path);
                      })
                  .toList()));
      return this;
    }

    Host wholeTree(String revision, TreeLookup tree) {
      wholeTrees.put(revision, tree);
      return this;
    }

    @Override
    public TreeLookup recursiveTree(String project, String repository, String revision) {
      wholeTreeReads.add(revision);
      return wholeTrees.getOrDefault(revision, TreeLookup.foundRecursive(SHA, List.of()));
    }
  }

  /** Who released what, scripted; counts its reads, since each one is a query. */
  private static final class Graph extends ArtifactGraph {

    private final Map<String, String> producers;

    int reads;

    Graph(Map<String, String> producers) {
      this.producers = producers;
    }

    @Override
    public Map<String, String> producers() {
      reads++;
      return producers;
    }
  }

  /** The inventory, holding just the library repositories it is told about. */
  private static final class Store extends MaintenanceStore {

    private final Map<String, MtRepository> rows = new HashMap<>();

    Store with(String name, String mainBranch) {
      MtRepository row = new MtRepository();
      row.name = name;
      row.project = PROJECT;
      row.mainBranch = mainBranch;
      rows.put(name, row);
      return this;
    }

    @Override
    public Optional<MtRepository> repository(String name) {
      return Optional.ofNullable(rows.get(name));
    }
  }

  /** qits-eventstream-javalib's declaration: the library and its pact kit, plus its docs. */
  private static final String EVENTSTREAM_RELEASE_YML =
      """
      archetype: maven-library
      artifacts:
        - { type: maven, name: "eu.wohlben.qits:qits-eventstream", path: qits-eventstream }
        - { type: maven, name: "eu.wohlben.qits:qits-eventstream-contracts", path: contracts }
        - { type: docs, name: "eu.wohlben.qits:qits-eventstream-docs" }
      """;

  private static final String UI_COMPONENTS_RELEASE_YML =
      """
      artifacts:
        - { type: npm, name: "@qits/ui-components", path: dist/ui-components, sbom: sbom.json }
        - { type: docs, name: "@qits/ui-components" }
      """;

  /** Nothing in the inventory and nothing on the host, unless a test says otherwise. */
  @BeforeEach
  void noLibraryDeclaresAnything() {
    for (GoldenRecordingsAutomation kind : List.of(maven, npm)) {
      kind.store = new Store();
      kind.gitHost = new Host();
    }
  }

  private static final String EVENTSTREAM_PACT =
      "x_qits-eventstream-javalib_qits-events-service.json";

  private static final Map<String, String> EVENTSTREAM_PRODUCERS =
      Map.of(
          ArtifactGraph.producerKey("maven", "eu.wohlben.qits:qits-eventstream-contracts"),
          "qits-eventstream-javalib");

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

  private static ParsedPin eventstreamContracts(String version) {
    return new ParsedPin(
        Ecosystem.MAVEN,
        "pom.xml",
        "eu.wohlben.qits:qits-eventstream-contracts",
        version,
        null,
        "property:qits.eventstream.version",
        false);
  }

  private static ParsedPin npmUiComponents(String version) {
    return ParsedPin.of(
        Ecosystem.NPM, "package.json", "@qits/ui-components", version, "^" + version,
        "dependencies");
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
      assertTrue(kind.inputPaths().containsAll(DependencyBumpAutomation.MANIFESTS), kind.kind());
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

  // --- library-kit pacts (qits-1782) ----------------------------------------------------------

  @Test
  void aMovedLibraryKitPinWithAThreePartPactRunsByTheProducers() {
    Graph graph = new Graph(EVENTSTREAM_PRODUCERS);
    maven.graph = graph;
    maven.scanner =
        new Scanner()
            .at(SHA, mavenGolden("2026.1020.204038"), eventstreamContracts("2026.1031.2"))
            .at("main", mavenGolden("2026.1020.204038"), eventstreamContracts("2026.1031.1"));
    AutomationSubject subject =
        subject(
            new Host()
                .blob("pom.xml", POM_WITH_PROPERTY)
                .pacts("x_qits-githost-service.json", EVENTSTREAM_PACT));

    Plan plan = maven.plan(subject);

    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
    assertEquals(1, graph.reads, "the producers are read once per plan");
  }

  @Test
  void aMovedLibraryKitPinNoReleaseAnnouncedRunsByTheLibrarysReleaseYml() {
    Host host = new Host().releaseYml("qits-eventstream-javalib", EVENTSTREAM_RELEASE_YML);
    maven.graph = new Graph(Map.of());
    maven.store = new Store().with("qits-eventstream-javalib", "trunk");
    maven.gitHost = host;
    maven.scanner =
        new Scanner()
            .at(SHA, eventstreamContracts("2026.1031.2"), mavenOther("2026.1031.2"))
            .at("main", eventstreamContracts("2026.1031.1"), mavenOther("2026.1031.1"));

    Plan plan =
        maven.plan(subject(new Host().blob("pom.xml", POM_WITHOUT).pacts(EVENTSTREAM_PACT)));

    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
    assertEquals(
        List.of(PROJECT + " qits-eventstream-javalib trunk"),
        host.releaseYmlReads,
        "read at the library's own main, and once per plan however many pins ask");
  }

  @Test
  void aCoordinateTheReleaseYmlDoesNotListIsNotClaimedEvenSharingTheName() {
    maven.graph = new Graph(Map.of());
    maven.store = new Store().with("qits-eventstream-javalib", "main");
    maven.gitHost =
        new Host()
            .releaseYml(
                "qits-eventstream-javalib",
                """
                artifacts:
                  - { type: maven, name: "eu.wohlben.qits:qits-eventstream" }
                  - { type: docker, name: "eu.wohlben.qits:qits-eventstream-contracts" }
                  - { type: npm, name: "eu.wohlben.qits:qits-eventstream-contracts" }
                """);
    maven.scanner =
        new Scanner()
            .at(SHA, eventstreamContracts("2026.1031.2"))
            .at("main", eventstreamContracts("2026.1031.1"));

    Plan plan =
        maven.plan(subject(new Host().blob("pom.xml", POM_WITHOUT).pacts(EVENTSTREAM_PACT)));

    assertEquals(Plan.Kind.FRESH, plan.kind(), plan.reason());
  }

  @Test
  void aLibraryWhoseReleaseYmlCannotBeReadClaimsNothing() {
    maven.graph = new Graph(Map.of());
    maven.scanner =
        new Scanner()
            .at(SHA, eventstreamContracts("2026.1031.2"))
            .at("main", eventstreamContracts("2026.1031.1"));
    AutomationSubject subject =
        subject(new Host().blob("pom.xml", POM_WITHOUT).pacts(EVENTSTREAM_PACT));

    maven.store = new Store().with("qits-eventstream-javalib", "main");
    maven.gitHost = new Host().releaseYmlUnreachable("qits-eventstream-javalib");
    assertEquals(Plan.Kind.FRESH, maven.plan(subject).kind(), "unreadable");

    maven.gitHost = new Host();
    assertEquals(Plan.Kind.FRESH, maven.plan(subject).kind(), "absent");

    maven.gitHost = new Host().releaseYml("qits-eventstream-javalib", "archetype: maven-library\n");
    assertEquals(Plan.Kind.FRESH, maven.plan(subject).kind(), "no artifacts");

    maven.gitHost = new Host().releaseYml("qits-eventstream-javalib", "artifacts: [ {");
    assertEquals(Plan.Kind.FRESH, maven.plan(subject).kind(), "not yaml");

    maven.store = new Store();
    maven.gitHost = new Host().releaseYml("qits-eventstream-javalib", EVENTSTREAM_RELEASE_YML);
    assertEquals(Plan.Kind.FRESH, maven.plan(subject).kind(), "not in the inventory");
  }

  @Test
  void aProducerNamingAnotherRepositoryOverridesTheReleaseYml() {
    Host host = new Host().releaseYml("qits-eventstream-javalib", EVENTSTREAM_RELEASE_YML);
    maven.store = new Store().with("qits-eventstream-javalib", "main");
    maven.gitHost = host;
    maven.graph =
        new Graph(
            Map.of(
                ArtifactGraph.producerKey("maven", "eu.wohlben.qits:qits-eventstream-contracts"),
                "somebody-else"));
    maven.scanner =
        new Scanner()
            .at(SHA, eventstreamContracts("2026.1031.2"))
            .at("main", eventstreamContracts("2026.1031.1"));

    Plan plan =
        maven.plan(subject(new Host().blob("pom.xml", POM_WITHOUT).pacts(EVENTSTREAM_PACT)));

    assertEquals(Plan.Kind.FRESH, plan.kind(), plan.reason());
    assertEquals(List.of(), host.releaseYmlReads, "the producers answered first");
  }

  @Test
  void libraryKitPinsEqualToMainAreFresh() {
    maven.graph = new Graph(EVENTSTREAM_PRODUCERS);
    maven.scanner =
        new Scanner()
            .at(SHA, mavenGolden("2026.1020.204038"), eventstreamContracts("2026.1031.1"))
            .at("main", mavenGolden("2026.1020.204038"), eventstreamContracts("2026.1031.1"));

    Plan plan =
        maven.plan(subject(new Host().blob("pom.xml", POM_WITH_PROPERTY).pacts(EVENTSTREAM_PACT)));

    assertEquals(Plan.Kind.FRESH, plan.kind(), plan.reason());
    assertTrue(plan.reason().contains("library-kit"), plan.reason());
  }

  @Test
  void aMovedLibraryPinWithNoThreePartPactIsFreshAndReadsNoProducers() {
    Graph graph = new Graph(EVENTSTREAM_PRODUCERS);
    maven.graph = graph;
    maven.scanner =
        new Scanner()
            .at(SHA, mavenGolden("2026.1020.204038"), eventstreamContracts("2026.1031.2"))
            .at("main", mavenGolden("2026.1020.204038"), eventstreamContracts("2026.1031.1"));

    Plan plan =
        maven.plan(
            subject(
                new Host()
                    .blob("pom.xml", POM_WITH_PROPERTY)
                    .pacts("x_qits-githost-service.json")));

    assertEquals(Plan.Kind.FRESH, plan.kind(), plan.reason());
    assertEquals(0, graph.reads);
  }

  @Test
  void aFoldWhosePactsCannotBeListedRuns() {
    maven.graph = new Graph(EVENTSTREAM_PRODUCERS);
    maven.scanner =
        new Scanner()
            .at(SHA, mavenGolden("2026.1020.204038"))
            .at("main", mavenGolden("2026.1020.204038"));

    assertEquals(
        Plan.Kind.RUN,
        maven
            .plan(subject(new Host().blob("pom.xml", POM_WITH_PROPERTY).pactsUnreachable()))
            .kind());
  }

  @Test
  void aReactorWithNoGoldenMastersButALibraryKitPactApplies() {
    AutomationSubject subject =
        subject(
            new Host()
                .blob("pom.xml", POM_WITHOUT)
                .blob("service/pom.xml", "<project/>")
                .pacts(EVENTSTREAM_PACT));

    assertTrue(maven.applicability(subject).applied());
  }

  @Test
  void aReactorWithNoGoldenMastersAndNoPactsDirectoryIsNotApplicable() {
    AutomationSubject subject =
        subject(new Host().blob("pom.xml", POM_WITHOUT).blob("service/pom.xml", "<project/>"));

    Applicability answer = maven.applicability(subject);
    assertEquals(Applicability.State.NOT_APPLICABLE, answer.state());
    assertTrue(answer.reason().contains("library-kit"), answer.reason());
  }

  @Test
  void aReactorWithNoGoldenMastersAndOnlyTwoPartPactsIsNotApplicable() {
    AutomationSubject subject =
        subject(
            new Host()
                .blob("pom.xml", POM_WITHOUT)
                .blob("service/pom.xml", "<project/>")
                .pacts("x_qits-githost-service.json", "x__y.json", "a_b_c_d.json", "a_b_c.txt"));

    assertEquals(Applicability.State.NOT_APPLICABLE, maven.applicability(subject).state());
  }

  @Test
  void anUnreadablePactsListingIsUnknownUnlessGoldenMastersAlreadyApply() {
    AutomationSubject without =
        subject(
            new Host()
                .blob("pom.xml", POM_WITHOUT)
                .blob("service/pom.xml", "<project/>")
                .pactsUnreachable());
    assertEquals(Applicability.State.UNKNOWN, maven.applicability(without).state());

    AutomationSubject with =
        subject(new Host().blob("pom.xml", POM_WITH_PROPERTY).pactsUnreachable());
    assertTrue(maven.applicability(with).applied());
  }

  @Test
  void npmAppliesAndRunsOnAMovedUiComponentsPinWithALibraryKitPact() {
    AutomationSubject subject =
        subject(
            new Host()
                .blob("package.json", PACKAGE_WITHOUT)
                .pacts("qits-ci-frontend_qits-ui-components-jslib_qits-ci-service.json"));
    assertTrue(npm.applicability(subject).applied());

    npm.scanner =
        new Scanner()
            .at(SHA, npmUiComponents("2026.1031.2"))
            .at("main", npmUiComponents("2026.1031.1"));
    npm.graph =
        new Graph(
            Map.of(
                ArtifactGraph.producerKey("npm", "@qits/ui-components"),
                "qits-ui-components-jslib"));
    assertEquals(Plan.Kind.RUN, npm.plan(subject).kind());

    npm.graph = new Graph(Map.of());
    assertEquals(
        Plan.Kind.FRESH, npm.plan(subject).kind(), "no producer and no release.yml claims it");

    npm.store = new Store().with("qits-ui-components-jslib", "main");
    npm.gitHost = new Host().releaseYml("qits-ui-components-jslib", UI_COMPONENTS_RELEASE_YML);
    assertEquals(Plan.Kind.RUN, npm.plan(subject).kind(), "by the npm name its release.yml lists");
  }

  // --- the recording sources (qits-1957) ------------------------------------------------------

  private static final Map<String, String> NPM_APP =
      Map.of(
          "package.json", "1111111111111111111111111111111111111111",
          "src/app/x/x.store.pact.spec.ts", "2222222222222222222222222222222222222222",
          "src/testing/golden-masters.ts", "3333333333333333333333333333333333333333",
          "src/testing/browser/routes.json", "4444444444444444444444444444444444444444",
          "src/testing/browser/renderer.txt", "5555555555555555555555555555555555555555",
          "src/app/x/__screenshots__/a.png", "6666666666666666666666666666666666666666",
          "pacts/app_qits-projects-service.json", "7777777777777777777777777777777777777777",
          "README.md", "8888888888888888888888888888888888888888");

  private static final Map<String, String> MAVEN_CONSUMER =
      Map.of(
          "pom.xml", "1111111111111111111111111111111111111111",
          "service/src/main/java/eu/wohlben/SomeClient.java",
          "2222222222222222222222222222222222222222",
          "service/src/test/java/eu/wohlben/SomeClientPactTest.java",
          "3333333333333333333333333333333333333333",
          "pacts/qits-maintenance-service_qits-githost-service.json",
          "4444444444444444444444444444444444444444",
          "docs/README.md", "5555555555555555555555555555555555555555");

  /** {@code base} with one path's sha changed: what a fold that edited only that file holds. */
  private static Map<String, String> edited(Map<String, String> base, String path) {
    Map<String, String> out = new HashMap<>(base);
    out.put(path, "9999999999999999999999999999999999999999");
    return out;
  }

  /**
   * An npm app whose pins agree with main's: the fold tree on the subject's host, main's on the
   * kind's own, the way the plan reads them.
   */
  private AutomationSubject npmWithEqualPins(Map<String, String> fold, Map<String, String> main) {
    npm.scanner =
        new Scanner().at(SHA, npmGolden("2026.1022.1")).at("main", npmGolden("2026.1022.1"));
    npm.gitHost = new Host().wholeTree("main", main);
    return subject(new Host().blob("package.json", PACKAGE_WITH).wholeTree(SHA, fold));
  }

  private AutomationSubject mavenWithEqualPins(Map<String, String> fold, Map<String, String> main) {
    maven.scanner =
        new Scanner().at(SHA, mavenGolden("2026.1022.1")).at("main", mavenGolden("2026.1022.1"));
    maven.gitHost = new Host().wholeTree("main", main);
    return subject(new Host().blob("pom.xml", POM_WITH_PROPERTY).wholeTree(SHA, fold));
  }

  /** The route ledger screenshot-baselines writes is what the golden masters stamp into the pacts. */
  @Test
  void npmPinsEqualButANewRouteLedgerRuns() {
    Plan plan =
        npm.plan(npmWithEqualPins(edited(NPM_APP, "src/testing/browser/routes.json"), NPM_APP));

    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
    assertEquals(Map.of(), plan.runs().getFirst().extras(), "commitPaths are the kind's own");
  }

  @Test
  void npmPinsEqualButAChangedPactSpecRuns() {
    Plan plan =
        npm.plan(npmWithEqualPins(edited(NPM_APP, "src/app/x/x.store.pact.spec.ts"), NPM_APP));

    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
  }

  @Test
  void npmPinsEqualButAnAddedOrRemovedSourceRuns() {
    Map<String, String> added = new HashMap<>(NPM_APP);
    added.put("src/app/y/y.store.pact.spec.ts", "aaaa000000000000000000000000000000000000");

    assertEquals(Plan.Kind.RUN, npm.plan(npmWithEqualPins(added, NPM_APP)).kind(), "added");
    assertEquals(Plan.Kind.RUN, npm.plan(npmWithEqualPins(NPM_APP, added)).kind(), "removed");
  }

  @Test
  void mavenPinsEqualButAChangedClientRuns() {
    Plan plan =
        maven.plan(
            mavenWithEqualPins(
                edited(MAVEN_CONSUMER, "service/src/main/java/eu/wohlben/SomeClient.java"),
                MAVEN_CONSUMER));

    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
  }

  @Test
  void equalPinsAndEqualSourcesAreFresh() {
    Plan npmPlan = npm.plan(npmWithEqualPins(NPM_APP, NPM_APP));
    assertEquals(Plan.Kind.FRESH, npmPlan.kind(), npmPlan.reason());
    assertTrue(npmPlan.reason().contains("every source"), npmPlan.reason());

    Plan mavenPlan = maven.plan(mavenWithEqualPins(MAVEN_CONSUMER, MAVEN_CONSUMER));
    assertEquals(Plan.Kind.FRESH, mavenPlan.kind(), mavenPlan.reason());
  }

  /**
   * What no pact spec reads is not compared: screenshot references and the renderer record, the
   * recorded pacts themselves, a README — nor, for maven, a change outside {@code src/}.
   */
  @Test
  void changesOutsideTheSourcesAreFresh() {
    Map<String, String> fold = edited(NPM_APP, "src/app/x/__screenshots__/a.png");
    fold = edited(fold, "src/testing/browser/renderer.txt");
    fold = edited(fold, "pacts/app_qits-projects-service.json");
    fold = edited(fold, "README.md");
    Plan plan = npm.plan(npmWithEqualPins(fold, NPM_APP));
    assertEquals(Plan.Kind.FRESH, plan.kind(), plan.reason());

    Plan mavenPlan =
        maven.plan(
            mavenWithEqualPins(
                edited(
                    edited(MAVEN_CONSUMER, "docs/README.md"),
                    "pacts/qits-maintenance-service_qits-githost-service.json"),
                MAVEN_CONSUMER));
    assertEquals(Plan.Kind.FRESH, mavenPlan.kind(), mavenPlan.reason());
  }

  /** Re-recording when nothing moved writes nothing: a tree that cannot be compared is a run. */
  @Test
  void anUnreadableOrNonRecursiveMainTreeRuns() {
    AutomationSubject subject = npmWithEqualPins(NPM_APP, NPM_APP);
    npm.gitHost = new Host().wholeTree("main", TreeLookup.unreachable("connection refused"));
    assertEquals(Plan.Kind.RUN, npm.plan(subject).kind(), "unreachable");

    npm.gitHost = new Host().wholeTree("main", TreeLookup.gone());
    assertEquals(Plan.Kind.RUN, npm.plan(subject).kind(), "gone");

    npm.gitHost = new Host().wholeTree("main", TreeLookup.found(SHA, List.of()));
    assertEquals(Plan.Kind.RUN, npm.plan(subject).kind(), "an older git host's plain listing");
  }

  @Test
  void anUnreadableOrNonRecursiveFoldTreeRuns() {
    npmWithEqualPins(NPM_APP, NPM_APP);
    Host unreachable =
        new Host()
            .blob("package.json", PACKAGE_WITH)
            .wholeTree(SHA, TreeLookup.unreachable("connection refused"));
    assertEquals(Plan.Kind.RUN, npm.plan(subject(unreachable)).kind(), "unreachable");

    Host plain =
        new Host()
            .blob("package.json", PACKAGE_WITH)
            .wholeTree(SHA, TreeLookup.found(SHA, List.of()));
    assertEquals(
        Plan.Kind.RUN, npm.plan(subject(plain)).kind(), "an older git host's plain listing");
  }

  /** A source the host names without its sha cannot be compared either. */
  @Test
  void aSourceWithoutItsShaRuns() {
    AutomationSubject subject = npmWithEqualPins(NPM_APP, NPM_APP);
    npm.gitHost =
        new Host()
            .wholeTree(
                "main",
                TreeLookup.foundRecursive(
                    SHA,
                    List.of(
                        new TreeLookup.TreeEntry(
                            "routes.json", "blob", null, null,
                            "src/testing/browser/routes.json"))));
    assertEquals(Plan.Kind.RUN, npm.plan(subject).kind());
  }

  /** The cheap answer first: a moved pin runs before either tree is read. */
  @Test
  void aMovedPinRunsWithoutReadingTheTrees() {
    Host fold = new Host().blob("package.json", PACKAGE_WITH).wholeTree(SHA, NPM_APP);
    Host main = new Host().wholeTree("main", NPM_APP);
    npm.gitHost = main;
    npm.scanner =
        new Scanner().at(SHA, npmGolden("2026.1022.2")).at("main", npmGolden("2026.1022.1"));

    assertEquals(Plan.Kind.RUN, npm.plan(subject(fold)).kind());
    assertEquals(List.of(), fold.wholeTreeReads, "the fold's tree is not read");
    assertEquals(List.of(), main.wholeTreeReads, "main's tree is not read");
  }

  /** The fold's tree is read once; main's, on the kind's own reader, at the target's main. */
  @Test
  void equalPinsReadBothTreesOnce() {
    Host fold = new Host().blob("package.json", PACKAGE_WITH).wholeTree(SHA, NPM_APP);
    Host main = new Host().wholeTree("main", NPM_APP);
    npm.gitHost = main;
    npm.scanner =
        new Scanner().at(SHA, npmGolden("2026.1022.1")).at("main", npmGolden("2026.1022.1"));

    assertEquals(Plan.Kind.FRESH, npm.plan(subject(fold)).kind());
    assertEquals(List.of(SHA), fold.wholeTreeReads);
    assertEquals(List.of("main"), main.wholeTreeReads);
  }

  /**
   * What each kind declares it reads (qits-1957): a fold that touched one of these is planned
   * again rather than carried, and the screenshot output under {@code src/} still carries.
   */
  @Test
  void theInputsAreTheManifestsAndTheKindsOwnSources() {
    AutomationSubject npmFold = subject(new Host().blob("package.json", PACKAGE_WITH));
    List<String> npmInputs = npm.inputPaths(npmFold);
    for (String read :
        List.of(
            "package.json", "package-lock.json", "src/testing/browser/routes.json",
            "src/app/x/x.store.pact.spec.ts", "src/testing/golden-masters.ts", "angular.json",
            "tsconfig.json", "tsconfig.spec.json", "vitest.config.ts")) {
      assertTrue(Pathspecs.matchesAny(npmInputs, read), read);
      assertTrue(Pathspecs.matchesAny(npm.inputPaths(), read), "static: " + read);
    }
    for (String unread :
        List.of(
            "src/app/x/__screenshots__/a.png", "src/testing/browser/renderer.txt",
            "pacts/app_qits-projects-service.json", "README.md")) {
      assertFalse(Pathspecs.matchesAny(npmInputs, unread), unread);
      assertFalse(Pathspecs.matchesAny(npm.inputPaths(), unread), "static: " + unread);
    }

    AutomationSubject mavenFold = subject(new Host().blob("pom.xml", POM_WITH_PROPERTY));
    List<String> mavenInputs = maven.inputPaths(mavenFold);
    for (String read :
        List.of(
            "pom.xml", "service/pom.xml", "service/src/main/java/eu/wohlben/SomeClient.java",
            "service/src/test/java/eu/wohlben/SomeClientPactTest.java")) {
      assertTrue(Pathspecs.matchesAny(mavenInputs, read), read);
      assertTrue(Pathspecs.matchesAny(maven.inputPaths(), read), "static: " + read);
    }
    for (String unread :
        List.of("pacts/qits-maintenance-service_qits-githost-service.json", "docs/README.md")) {
      assertFalse(Pathspecs.matchesAny(mavenInputs, unread), unread);
      assertFalse(Pathspecs.matchesAny(maven.inputPaths(), unread), "static: " + unread);
    }
    assertFalse(
        Pathspecs.matchesAny(mavenInputs, "src/testing/browser/routes.json"),
        "the maven kind's own list takes no npm source");
  }
}

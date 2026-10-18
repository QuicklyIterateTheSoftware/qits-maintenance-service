package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The GITLINK half of one repository's scan: {@code .gitmodules} for the name, the TREE for the
 * version.
 *
 * <p>No Quarkus and no network — a git host that answers out of two maps is the whole seam, which
 * is also what makes the one thing worth proving here provable: <b>a gitlink whose sha the host
 * does not report produces no pin at all</b>. The shipped git host is exactly that host today; it
 * collapses every non-directory entry to {@code blob} and has never reported an object name.
 *
 * <p>The same seam carries the two halves after it: which Dockerfiles the discovery reaches — the
 * root, and the {@code docker/} directory below it — and the {@code ignore:} question of which
 * ecosystems a repository is scanned for at all. Both are proved by the reads that are and are not
 * made.
 */
class ManifestScannerTest {

  private static final String PROJECT = "qits";
  private static final String REPOSITORY = "qits-artifacts-service";
  private static final String SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";
  private static final String PINNED = "aa11bb22cc33dd44ee55ff6677889900aabbccdd";

  private static final String GITMODULES =
      """
      [submodule "qits-artifacts-frontend"]
      \tpath = service/src/main/webui
      \turl = ../qits-artifacts-frontend.git
      \tignore = all
      """;

  /** A git host holding one revision's trees and blobs, and answering 404 for everything else. */
  private static final class Host extends GitHostReader {

    final Map<String, TreeLookup> trees = new LinkedHashMap<>();
    final Map<String, String> blobs = new LinkedHashMap<>();
    /** A blob read answered with something other than its content, whatever the tree says. */
    final Map<String, FileLookup> unserved = new LinkedHashMap<>();
    final List<String> read = new ArrayList<>();

    Host() {
      trees.put("", TreeLookup.found(SHA, List.of()));
    }

    Host tree(String path, TreeLookup.TreeEntry... entries) {
      trees.put(path, TreeLookup.found(SHA, List.of(entries)));
      return this;
    }

    Host blob(String path, String content) {
      blobs.put(path, content);
      return this;
    }

    Host unserved(String path, FileLookup answer) {
      unserved.put(path, answer);
      return this;
    }

    @Override
    public TreeLookup head(String project, String repository, String revision) {
      return tree(project, repository, revision, "");
    }

    @Override
    public TreeLookup tree(String project, String repository, String revision, String path) {
      read.add("tree " + path);
      TreeLookup found = trees.get(path);
      return found == null ? TreeLookup.absent() : found;
    }

    @Override
    public FileLookup blob(String project, String repository, String revision, String path) {
      read.add("blob " + path);
      if (unserved.containsKey(path)) {
        return unserved.get(path);
      }
      String content = blobs.get(path);
      return content == null ? FileLookup.absent() : FileLookup.found(content);
    }
  }

  private static ManifestScanner scanner(Host host) {
    ManifestScanner scanner = new ManifestScanner();
    scanner.gitHost = host;
    return scanner;
  }

  private static ManifestScanner.Read read(Host host) {
    return scanner(host).read(new CatalogEntry(PROJECT, REPOSITORY, "main", null, null));
  }

  /** A host that reports the mode and the object name: the submodule is one INTERNAL pin. */
  @Test
  void aSubmoduleWhoseGitlinkShaTheHostReportsIsOnePin() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"), new TreeLookup.TreeEntry("service", "tree"))
            .tree(
                "service/src/main",
                new TreeLookup.TreeEntry("webui", "blob", TreeLookup.TreeEntry.GITLINK_MODE, PINNED))
            .blob(GitmodulesParser.PATH, GITMODULES);

    ManifestScanner.Read read = read(host);

    assertEquals(RepositoryStatus.OK, read.status());
    assertEquals(1, read.pins().size());
    ParsedPin pin = read.pins().get(0);
    assertEquals(Ecosystem.GITLINK, pin.ecosystem());
    assertEquals("qits-artifacts-frontend", pin.name(), "the name is the url's, not the entry's");
    assertEquals(PINNED, pin.version(), "the version of a gitlink is the commit its tree entry records");
    assertEquals("service/src/main/webui", pin.manifestPath());
    assertEquals("gitlink:service/src/main/webui", pin.location());
  }

  /** A host that spells the git object type out instead of the mode says the same thing. */
  @Test
  void anEntryTypedCommitIsAGitlinkToo() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .tree("service/src/main", new TreeLookup.TreeEntry("webui", "commit", null, PINNED))
            .blob(GitmodulesParser.PATH, GITMODULES);

    assertEquals(1, read(host).pins().size());
  }

  /**
   * THE SHIPPED GIT HOST, and the reason this feature is inert on it: every non-directory entry is
   * a {@code blob} with no mode and no sha, so there is nothing to pin a submodule at. A pin
   * invented here would be compared by the pending rule and then applied by the bump step, into
   * somebody else's repository.
   */
  @Test
  void aHostThatReportsNoShaProducesNoGitlinkPinRatherThanAGuessedOne() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .tree("service/src/main", new TreeLookup.TreeEntry("webui", "blob"))
            .blob(GitmodulesParser.PATH, GITMODULES);

    assertTrue(read(host).pins().isEmpty());
  }

  /** A section left behind by a commit that removed the submodule names nothing in the tree. */
  @Test
  void aDeclarationTheTreeDoesNotHoldIsSkipped() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .tree("service/src/main", new TreeLookup.TreeEntry("something-else", "tree"))
            .blob(GitmodulesParser.PATH, GITMODULES);

    assertTrue(read(host).pins().isEmpty());
  }

  /** No file, no reads: a repository without submodules costs nothing. */
  @Test
  void aRepositoryWithNoGitmodulesIsNotAskedForOne() {
    Host host = new Host().tree("", new TreeLookup.TreeEntry("README.md", "blob"));

    assertTrue(read(host).pins().isEmpty());
    assertTrue(
        host.read.stream().noneMatch(call -> call.equals("blob " + GitmodulesParser.PATH)),
        "the root listing already says the file is not there");
  }

  /** Several submodules under one directory cost ONE listing, not one each. */
  @Test
  void submodulesSharingADirectoryShareTheListingThatAnswersForThem() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .tree(
                "components/qits-ci",
                new TreeLookup.TreeEntry("qits-ci-service", "blob", TreeLookup.TreeEntry.GITLINK_MODE, PINNED),
                new TreeLookup.TreeEntry("qits-ci-frontend", "blob", TreeLookup.TreeEntry.GITLINK_MODE, SHA))
            .blob(
                GitmodulesParser.PATH,
                """
                [submodule "qits-ci-service"]
                \tpath = components/qits-ci/qits-ci-service
                \turl = ../qits-ci-service.git
                [submodule "qits-ci-frontend"]
                \tpath = components/qits-ci/qits-ci-frontend
                \turl = ../qits-ci-frontend.git
                """);

    List<ParsedPin> pins = read(host).pins();

    assertEquals(2, pins.size());
    assertEquals(
        List.of("qits-ci-service", "qits-ci-frontend"), pins.stream().map(ParsedPin::name).toList());
    assertEquals(
        1,
        host.read.stream().filter(call -> call.equals("tree components/qits-ci")).count(),
        "one directory, one listing");
  }

  // --- docker: the root, and the one directory below it ---------------------------------------

  /**
   * THE BASE IMAGE OF A DAEMON IS PINNED IN AN ARG, IN A DIRECTORY, and both halves of that were
   * misses. qits-workspace-daemon builds from {@code docker/Dockerfile} — a path the root-only
   * discovery never read — and pins its base in an {@code ARG} default rather than on the FROM, so
   * even a file that was read produced nothing. The pin this makes is an ordinary internal one:
   * {@code qits/workspace-base}, the name {@code mt_latest} is keyed by, with the registry host the
   * {@code docker build} needs dropped from it.
   */
  @Test
  void aDockerfileInTheDockerDirectoryIsFoundAndItsArgDefaultIsThePin() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry("docker", "tree"))
            .tree("docker", new TreeLookup.TreeEntry("Dockerfile", "blob"))
            .blob(
                "docker/Dockerfile",
                """
                ARG WORKSPACE_BASE=registry.dev.localhost:8080/qits/workspace-base:2026.902.143920
                FROM ${WORKSPACE_BASE}
                """);

    List<ParsedPin> pins = read(host).pins();

    assertEquals(1, pins.size());
    ParsedPin pin = pins.get(0);
    assertEquals(Ecosystem.DOCKER, pin.ecosystem());
    assertEquals("qits/workspace-base", pin.name(), "the host is an address, not part of the name");
    assertEquals("2026.902.143920", pin.version());
    assertEquals("docker/Dockerfile", pin.manifestPath(), "the path the bump step edits");
    assertEquals("arg:WORKSPACE_BASE", pin.location());
  }

  /** The other spelling of a qualified build file, which qits-projects-daemon uses. */
  @Test
  void aQualifiedDockerfileIsReadUnderEitherSpelling() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry("node.Dockerfile", "blob"), new TreeLookup.TreeEntry("docker", "tree"))
            .tree("docker", new TreeLookup.TreeEntry("Dockerfile.projects", "blob"))
            .blob("node.Dockerfile", "FROM qits/build-images/node-base:2026.813.1\n")
            .blob("docker/Dockerfile.projects", "ARG BASE=qits/workspace-base:2026.902.143920\n");

    assertEquals(
        List.of("node.Dockerfile", "docker/Dockerfile.projects"),
        read(host).pins().stream().map(ParsedPin::manifestPath).toList());
  }

  /** A repository without the directory is not asked to list one: the root listing already says. */
  @Test
  void aRepositoryWithNoDockerDirectoryIsNotAskedForOne() {
    Host host = new Host().tree("", new TreeLookup.TreeEntry("Dockerfile", "blob"))
        .blob("Dockerfile", "FROM qits/build-images/maven-base:2026.813.1\n");

    assertEquals(1, read(host).pins().size());
    assertTrue(
        host.read.stream().noneMatch(call -> call.equals("tree docker")),
        "one listing is one round trip against another service");
  }

  // --- ignore: the repository takes a whole ecosystem off its own scan -------------------------

  /**
   * A repository carrying both a gitlink and a Dockerfile. The wrapper is this shape at scale: its
   * forty-seven gitlinks are bank markers it says outright are expected to lag, while everything
   * else in it is an ordinary pin worth bumping.
   */
  private static Host mixed(String maintenanceYml) {
    Host host =
        new Host()
            .tree(
                "",
                new TreeLookup.TreeEntry(".gitmodules", "blob"),
                new TreeLookup.TreeEntry("Dockerfile", "blob"),
                new TreeLookup.TreeEntry("service", "tree"))
            .tree(
                "service/src/main",
                new TreeLookup.TreeEntry("webui", "blob", TreeLookup.TreeEntry.GITLINK_MODE, PINNED))
            .blob(GitmodulesParser.PATH, GITMODULES)
            .blob("Dockerfile", "FROM qits/build-images/maven-base:2026.813.1\n");
    return maintenanceYml == null ? host : host.blob(GroupConfig.PATH, maintenanceYml);
  }

  /** The control: with no file, both ecosystems pin. */
  @Test
  void withoutAnIgnoreBothEcosystemsPin() {
    List<ParsedPin> pins = read(mixed(null)).pins();

    assertEquals(
        List.of(Ecosystem.DOCKER, Ecosystem.GITLINK),
        pins.stream().map(ParsedPin::ecosystem).toList());
  }

  /**
   * AN IGNORED ECOSYSTEM'S PINS SIMPLY DO NOT EXIST. Not filtered afterwards and not stored and
   * skipped later: there is no pin, and the reads that would have found one are never made either.
   */
  @Test
  void anIgnoredEcosystemIsNotParsedWhileTheOthersStillPin() {
    Host host = mixed("ignore: [gitlink]\n");

    ManifestScanner.Read read = read(host);

    assertEquals(RepositoryStatus.OK, read.status());
    assertEquals(1, read.pins().size());
    assertEquals(Ecosystem.DOCKER, read.pins().get(0).ecosystem());
    assertTrue(
        host.read.stream().noneMatch(call -> call.equals("blob " + GitmodulesParser.PATH)),
        "an ignored ecosystem costs no read at all");
    // The grouping is untouched: `ignore` is a different question from which branch a bump rides.
    assertEquals(
        List.of(GroupConfig.DEFAULT_GROUP, GroupConfig.EXTERNAL_GROUP),
        read.groups().stream().map(GroupConfig.Group::name).toList());
  }

  /** Every ecosystem may be named, and a repository that names them all pins nothing. */
  @Test
  void aRepositoryMayIgnoreEveryEcosystemItHas() {
    assertTrue(read(mixed("ignore: [maven, npm, docker, gitlink]\n")).pins().isEmpty());
  }

  /**
   * A misspelled ecosystem is CONFIG_ERROR, and the manifests are still read: `ignore` could not be
   * trusted to mean anything, so nothing is taken off the scan and the row says why.
   */
  @Test
  void anUnknownEcosystemNameIsAConfigErrorOnTheRow() {
    ManifestScanner.Read read = read(mixed("ignore: [gitlinks]\n"));

    assertEquals(RepositoryStatus.CONFIG_ERROR, read.status());
    assertTrue(read.message().contains("gitlinks"));
    assertEquals(2, read.pins().size(), "a broken file ignores nothing");
    assertTrue(read.groups().isEmpty(), "and nothing is bumped for it");
  }

  /** A file git wrote is not this service's configuration: an unreadable one is not CONFIG_ERROR. */
  @Test
  void anUnparseableGitmodulesLeavesTheRepositoryOK() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .blob(GitmodulesParser.PATH, "]]] this is not a config file [[[");

    ManifestScanner.Read read = read(host);

    assertEquals(RepositoryStatus.OK, read.status());
    assertTrue(read.pins().isEmpty());
  }

  // --- a manifest the tree lists and the host does not serve (qits-1172) ---------------------

  private static final String ROOT_POM =
      """
      <project>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-artifacts</artifactId>
        <version>2026.821.1</version>
        <modules>
          <module>service</module>
        </modules>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-eventstream</artifactId>
            <version>2026.811.1</version>
          </dependency>
        </dependencies>
      </project>
      """;

  /**
   * THE HOLE: the root lists {@code pom.xml}, the blob read fails, and the scan used to answer OK
   * with the maven half simply missing — a reduced set the scan then wrote over the good one, and
   * the GC read as "unreferenced". It is UNREACHABLE now, which is the path that keeps the pins.
   */
  @Test
  void aListedPomTheHostFailsToServeMakesTheScanUnreachable() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry("pom.xml", "blob"), new TreeLookup.TreeEntry("Dockerfile", "blob"))
            .unserved("pom.xml", FileLookup.unreachable("connection reset"))
            .blob("Dockerfile", "FROM qits/build-images/maven-base:2026.813.1\n");

    ManifestScanner.Read read = read(host);

    assertEquals(RepositoryStatus.UNREACHABLE, read.status());
    assertTrue(read.pins().isEmpty(), "no partial set: the Dockerfile's pin is not a scan result");
    assertTrue(read.message().contains("pom.xml"), read.message());
  }

  /** A 404 on a blob the listing at the same sha names is a contradiction, not an absence. */
  @Test
  void aListedManifestAnsweredAbsentIsUnservedToo() {
    // The lock is unscripted: an ABSENT, at a sha whose root lists it. Without the lock a manifest
    // pins no version at all, so it is as much a manifest as package.json is.
    Host host =
        new Host()
            .tree(
                "",
                new TreeLookup.TreeEntry("package.json", "blob"),
                new TreeLookup.TreeEntry(ManifestScanner.PACKAGE_LOCK, "blob"))
            .blob("package.json", "{\"dependencies\":{\"@qits/ui-components\":\"2026.8.1\"}}");

    assertEquals(RepositoryStatus.UNREACHABLE, read(host).status());
  }

  /** The same for a Dockerfile, and for the one directory the root names. */
  @Test
  void anUnservedDockerfileOrDockerDirectoryIsUnreachable() {
    Host file =
        new Host()
            .tree("", new TreeLookup.TreeEntry("Dockerfile", "blob"))
            .unserved("Dockerfile", FileLookup.invalid("Dockerfile is larger than the git host serves"));
    assertEquals(RepositoryStatus.UNREACHABLE, read(file).status());

    // The root lists docker/ and the listing of it is unscripted — ABSENT, against the root's word.
    Host directory = new Host().tree("", new TreeLookup.TreeEntry("docker", "tree"));
    assertEquals(RepositoryStatus.UNREACHABLE, read(directory).status());
  }

  /**
   * A module the pom NAMES and the commit does not carry is genuinely absent: no listing said it was
   * there, so ABSENT is the git host's answer about the path and the root's pins stand. A module pom
   * the host fails to serve is a failure to ask, and is UNREACHABLE.
   */
  @Test
  void anAbsentModulePomIsAbsentWhileAnUnservedOneIsUnreachable() {
    Host absent =
        new Host().tree("", new TreeLookup.TreeEntry("pom.xml", "blob")).blob("pom.xml", ROOT_POM);
    ManifestScanner.Read read = read(absent);
    assertEquals(RepositoryStatus.OK, read.status());
    assertEquals(
        List.of("eu.wohlben.qits:qits-eventstream"),
        read.pins().stream().map(ParsedPin::name).toList());

    Host unserved =
        new Host()
            .tree("", new TreeLookup.TreeEntry("pom.xml", "blob"))
            .blob("pom.xml", ROOT_POM)
            .unserved("service/pom.xml", FileLookup.unreachable("502"));
    assertEquals(RepositoryStatus.UNREACHABLE, read(unserved).status());
  }

  /** The ledger's and the gitlink read's seam says the same, as a retryable status. */
  @Test
  void pinsAtAnUnservedManifestIsUnreachableNotAnEmptyRelease() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry("pom.xml", "blob"))
            .unserved("pom.xml", FileLookup.unreachable("connection reset"));

    ManifestScanner.Pins pins = scanner(host).pinsAt(PROJECT, REPOSITORY, "refs/tags/2026.1010.1");

    assertEquals(FileLookup.Status.UNREACHABLE, pins.status());
    assertTrue(pins.pins().isEmpty());
  }
}

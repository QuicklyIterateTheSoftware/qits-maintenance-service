package eu.wohlben.qits.maintenance.stories.support;

import eu.wohlben.qits.maintenance.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * <b>The platform this catalogue scans</b> — two repositories, as the six peers would describe
 * them, armed onto the {@link StoryPeers} stand-ins once before any story runs.
 *
 * <p>It is deliberately a WHOLE platform rather than a minimal one. Every rule this service has is
 * a rule about the seams between manifests, and a fixture carrying one pin would exercise none of
 * them: a reactor with a module, a groupId written as an expression, a property defined in the root
 * pom and referenced from a module, a parent that IS this repository's own root, a parent that is
 * not, a dependency at {@code ${project.version}}, an expression nobody declared, an npm lock beside
 * its manifest, and a Dockerfile with one internal image and one external one.
 *
 * <h2>Two repositories, because the stories need namespaces</h2>
 *
 * <p>A bump holds its (repository, group) lock until it ends, so two bump stories sharing one
 * repository would have the second answered 409 by the first's leftovers — the same hazard the
 * surefire suite solves with {@code InventoryReset}, which a launched process has no equivalent of.
 * Here the answer is <b>namespacing</b>: {@link #REPOSITORY} carries the rich reactor and every
 * story about reading, and {@link #SECOND_REPOSITORY} exists so the bump that ends
 * {@code NOTHING_TO_DO} has a branch of its own. It is also the honest shape of a catalog — a scan
 * that read one repository would not show that a scan reads them all.
 *
 * <h2>What is deliberately NOT armed</h2>
 *
 * <p>{@link #SECOND_REPOSITORY} carries no {@code .config/qits/maintenance.yml}, and that absence is
 * a fixture doing work: a 404 on a blob is followed by a read of the ROOT TREE at the same sha,
 * because the git host spells "no such revision" and "no such path" identically. Both calls are in
 * the diagram, which makes "a 404 is not an answer until it has been asked twice" a shape a reader
 * can see rather than a sentence in a README.
 *
 * <p>Nothing arms a branch here either. {@code maintenance/dependencies} does not exist until a
 * bump story says it does, and an unregistered path is a 404 — which is exactly what the git host
 * says about a branch nobody has pushed.
 */
public final class StoryCatalog {

  /** The project every repository in this catalog belongs to. */
  public static final String PROJECT = "qits";

  /** The rich reactor: a pom with modules, an npm client, a Dockerfile and a grouping file. */
  public static final String REPOSITORY = "qits-ci";

  /**
   * {@link #REPOSITORY}'s row id in the catalog, and the ONE thing a release ask is addressed by:
   * qits-projects resolves that route's path parameter against its own repository table. A word
   * rather than a uuid, so no label is scrubbed as a generated id.
   */
  public static final String CATALOG_ID = "r1";

  /**
   * What qits-projects classifies {@link #REPOSITORY} as. The catalog answers an archetype per row
   * and this service caches it, so a release train can decide placement over the whole inventory
   * without one HTTP call per repository.
   */
  public static final String ARCHETYPE = "SERVICE";

  /** A small library repository, so the second bump story has a branch of its own. */
  public static final String SECOND_REPOSITORY = "qits-eventstream";

  /** The commit {@link #REPOSITORY}'s manifests are all read at. Forty lowercase hex: {@code
   * Labels} rewrites it to {@link StoryTarget#DIGEST}, so a label stays template-shaped. */
  public static final String HEAD_SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";

  /** …and {@link #SECOND_REPOSITORY}'s. */
  public static final String SECOND_HEAD_SHA = "bb22cc33dd44ee55ff6677889900aabbccddeeff";

  /**
   * The INTERNAL half of the fallback grouping, and where this platform's own releases land. A
   * group's name IS its branch.
   */
  public static final String DEFAULT_GROUP = "dependencies";

  /** The EXTERNAL half: everybody else's upgrades, on a branch of their own. */
  public static final String EXTERNAL_GROUP = "external";

  /** The group {@link #REPOSITORY}'s own configuration declares, ahead of the fallback pair. */
  public static final String ANGULAR_GROUP = "angular";

  /** The branch {@link #DEFAULT_GROUP} is bumped on, as the git host route encodes it. */
  public static final String BRANCH = "maintenance/" + DEFAULT_GROUP;

  /** …and the branch {@link #EXTERNAL_GROUP} is bumped on. */
  public static final String EXTERNAL_BRANCH = "maintenance/" + EXTERNAL_GROUP;

  /** …and the same, percent-encoded, because a revision is ONE path segment on that route. */
  public static final String BRANCH_SEGMENT = "maintenance%2F" + DEFAULT_GROUP;

  /** Where {@link #REPOSITORY}'s branch is after the step pushed it. */
  public static final String BUMPED_SHA = "aa11bb22cc33dd44ee55ff6677889900aabbccdd";

  /** Where {@link #SECOND_REPOSITORY}'s branch is — before the run, and after it. */
  public static final String UNMOVED_SHA = "cc33dd44ee55ff6677889900aabbccddeeff0011";

  /** The run qits-ci names for {@link #REPOSITORY}'s bump. A word, so no label is scrubbed. */
  public static final String RUN = "run-alpha";

  /** …and for {@link #SECOND_REPOSITORY}'s. */
  public static final String SECOND_RUN = "run-beta";

  /**
   * The release request qits-projects opens for {@link #REPOSITORY}'s pushed branch. A word, so no
   * label and no assertion is scrubbed as a generated id.
   */
  public static final String RELEASE_REQUEST = "rr-alpha";

  // --- the peers' own routes ---------------------------------------------------------------------

  /** The whole catalog, in one call. It takes no paging and no filter. */
  public static final String CATALOG_PATH = "/projects/api/repositories";

  /** Where a bump is asked for. */
  public static final String TRIGGER_PATH = "/ci/api/events/trigger";

  /** …and where the branch it pushed is asked to be released. */
  public static final String RELEASE_REQUESTS_PATH = StoryTarget.releaseRequests(CATALOG_ID);

  /**
   * {@link #SECOND_REPOSITORY}'s own, at its own catalog id.
   *
   * <p>It is a second constant and not a parameter because the id is the whole of what the route
   * carries: a release ask addressed at {@link #CATALOG_ID} would reach the wrong repository, and
   * the story that needs this one is precisely the story about a branch that ends NOTHING_TO_DO and
   * is asked to be released anyway.
   */
  public static final String SECOND_RELEASE_REQUESTS_PATH = StoryTarget.releaseRequests("r2");

  // --- an unmerged release, for the base a bump is cut from (qits-1081) -------------------------

  /** The branch {@link #ANGULAR_GROUP} is bumped on. */
  public static final String ANGULAR_BRANCH = "maintenance/" + ANGULAR_GROUP;

  /** Where {@link #ANGULAR_BRANCH} stands before the rebuild — cut from main, long ago. */
  public static final String ANGULAR_BRANCH_SHA = "dd44ee55ff6677889900aabbccddeeff00112233";

  /** …and where it stands once the step rebuilt it on the tag. */
  public static final String REBUILT_SHA = "ee55ff6677889900aabbccddeeff001122334455";

  /** {@link #REPOSITORY}'s newest release that has not reached main. */
  public static final String TAG_VERSION = "2026.1007.171656";

  /** …and the commit it points at. */
  public static final String TAG_SHA = "7a9e5c3b1d0f2e4a6c8b0d1f3e5a7c9b2d4f6e80";

  /**
   * The git host's ancestry door for {@link #REPOSITORY}, under its storage id — which is the
   * catalog id. The stand-in matches without the query, so one answer serves both questions a
   * rebuild asks (is the tag on main, is it on the branch).
   */
  public static final String CONTAINS_PATH = "/githost/api/repositories/" + CATALOG_ID + "/contains";

  /** The run of the bump that is rebuilt on the tag. */
  public static final String THIRD_RUN = "run-gamma";

  /** …and of the one whose base could not be decided. */
  public static final String FOURTH_RUN = "run-delta";

  // --- the changelogs a bump carries (qits-893) --------------------------------------------------

  /**
   * The docs store's listing of every changelog one repository published, as the stand-in for
   * qits-artifacts matches it — the {@code @} and the slash literal, as {@code ChangelogClient}
   * sends them.
   */
  public static String changelogPath(String repository) {
    return "/artifacts/docs/docs/@changelog/" + repository;
  }

  /**
   * WHO PUBLISHES EACH INTERNAL PIN of {@link #REPOSITORY}, written straight into the launched
   * process's store — the rows a {@code SoftwareRelease} frame would have left, which no story can
   * send because the bus is dark here.
   *
   * <p>A bump resolves every internal change's source repository before it is sent, to find the
   * changelogs it pulls in, and an internal coordinate nobody is known to publish FAILS the bump.
   * {@code qits-eventstream} is {@link #SECOND_REPOSITORY}, which is what it is; the other four are
   * named for what they are. MISSING, so no sweep ever asks qits-artifacts for their documents.
   * Idempotent: a second call meets the identity index and writes nothing.
   */
  public static void seedProducers() {
    String[][] rows = {
      {"maven", "eu.wohlben.qits:qits-eventstream", "2026.821.3", SECOND_REPOSITORY},
      {"maven", "eu.wohlben.qits:qits-parent", "2026.820.1", "qits-parent"},
      {"maven", "eu.wohlben.qits:qits-arch-rules", "2026.822.1", "qits-arch-rules"},
      {"npm", "@qits/ui-components", "2026.8.4", "qits-ui-components-jslib"},
      {"docker", "qits/build-images/maven-base", "2026.821.2", "qits-build-images"},
    };
    String url = EmbeddedPg.url(StoryProfile.DATABASE);
    try (Connection connection =
            DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into mt_artifact (id, ecosystem, name, version, repository, occurred_at,"
                    + " sbom_status) values (?, ?, ?, ?, ?, ?, 'MISSING')"
                    + " on conflict (ecosystem, name, version) do nothing")) {
      for (String[] row : rows) {
        insert.setObject(1, UUID.randomUUID());
        insert.setString(2, row[0]);
        insert.setString(3, row[1]);
        insert.setString(4, row[2]);
        insert.setString(5, row[3]);
        insert.setTimestamp(6, Timestamp.from(Instant.parse("2026-08-21T00:00:00Z")));
        insert.executeUpdate();
      }
    } catch (Exception unwritable) {
      throw new IllegalStateException("could not seed the artifact producers", unwritable);
    }
  }

  /** {@code GET …/release-requests?state=all} as it goes out: the listing a base is chosen from. */
  public static String releaseListingWire(String releaseRequestsPath) {
    return releaseRequestsPath + "?state=all";
  }

  private StoryCatalog() {}

  // --- routes, in two spellings ------------------------------------------------------------------
  //
  // A path is armed by its DECODED form and recorded in its RAW one, and for three of the routes
  // below those differ. `%2f` is one path segment to a registry and to the git host — which is the
  // whole reason both encode — and exactly two path segments to java.net.URI.getPath(), which is
  // what the stand-in matches on. So `…Path` is what a story ARMS and `…Wire` is what a label reads,
  // and a route whose two spellings are the same has only the one method.

  /** {@code /git/<project>/<repo>/tree/<rev>[/<path>]}, as {@code GitHostReader} builds it. */
  public static String tree(String repository, String revision) {
    return "/git/" + PROJECT + "/" + repository + "/tree/" + revision;
  }

  /** The same route with the revision percent-encoded — a revision is ONE segment over there. */
  public static String treeWire(String repository, String revision) {
    return tree(repository, revision.replace("/", "%2F"));
  }

  /** {@code /git/<project>/<repo>/blob/<rev>/<path>}. One spelling: no segment here is encoded. */
  public static String blob(String repository, String revision, String path) {
    return "/git/" + PROJECT + "/" + repository + "/blob/" + revision + "/" + path;
  }

  /** One ci run's read route. */
  public static String runPath(String runId) {
    return "/ci/api/runs/" + runId;
  }

  /** A maven coordinate's metadata document, under whichever registry prefix answers for it. */
  public static String metadataPath(String prefix, String groupId, String artifactId) {
    return prefix + "/" + groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
  }

  /** A scoped npm package as the stand-in matches it: the slash decoded back. */
  public static String packumentPath(String prefix, String name) {
    return prefix + "/" + name;
  }

  /** …and as {@code LatestResolver} spells it on the wire, with the slash encoded. */
  public static String packumentWire(String prefix, String name) {
    return prefix + "/" + name.replace("/", "%2f");
  }

  /** An OCI tag listing. The paging query is not part of the path a stand-in matches. */
  public static String tagsPath(String image) {
    return StoryTarget.OCI_REGISTRY_PREFIX + "/" + image + "/tags/list";
  }

  /** …and as it goes out, first page, as {@code LatestResolver} asks for it. */
  public static String tagsWire(String image) {
    return tagsPath(image) + "?n=1000";
  }

  // --- the documents -----------------------------------------------------------------------------

  /**
   * A REAL REACTOR, because a single-module pom exercises none of what broke on the first live scan:
   * a parent that is the repository's own root, a groupId written as an expression, a module pinned
   * at {@code ${project.version}}, and a property nobody declared.
   */
  private static final String POM =
      """
      <project>
        <parent>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>qits-parent</artifactId>
          <version>2026.800.1</version>
        </parent>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-ci</artifactId>
        <version>2026.821.1</version>
        <properties>
          <qits.eventstream.version>2026.811.1</qits.eventstream.version>
          <qits.arch-rules.version>2026.817.175344</qits.arch-rules.version>
          <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
          <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
          <quarkus.platform.version>3.34.5</quarkus.platform.version>
        </properties>
        <modules>
          <module>service</module>
        </modules>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-eventstream</artifactId>
            <version>${qits.eventstream.version}</version>
          </dependency>
          <dependency>
            <groupId>${quarkus.platform.group-id}</groupId>
            <artifactId>${quarkus.platform.artifact-id}</artifactId>
            <version>${quarkus.platform.version}</version>
          </dependency>
        </dependencies>
      </project>
      """;

  /** The module: its parent IS this repository's root, and one dependency is a sibling module. */
  private static final String MODULE_POM =
      """
      <project>
        <parent>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>qits-ci</artifactId>
          <version>2026.821.1</version>
        </parent>
        <artifactId>qits-ci-service</artifactId>
        <dependencies>
          <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>qits-ci-domain</artifactId>
            <version>${project.version}</version>
          </dependency>
          <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>qits-arch-rules</artifactId>
            <version>${qits.arch-rules.version}</version>
          </dependency>
          <dependency>
            <groupId>g</groupId>
            <artifactId>mystery</artifactId>
            <version>${nobody.declared.this}</version>
          </dependency>
        </dependencies>
      </project>
      """;

  private static final String PACKAGE_JSON =
      """
      {"name":"client",
       "dependencies":{"@qits/ui-components":"2026.8.1","@angular/core":"^21.0.0"}}
      """;

  private static final String PACKAGE_LOCK =
      """
      {"lockfileVersion":3,
       "packages":{"":{"name":"client"},
                   "node_modules/@qits/ui-components":{"version":"2026.8.1"},
                   "node_modules/@angular/core":{"version":"21.0.4"}}}
      """;

  private static final String DOCKERFILE =
      """
      FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
      FROM qits/build-images/maven-base:2026.813.1
      """;

  /** The grouping file. {@code angular} is declared, {@code dependencies} is appended after it. */
  private static final String MAINTENANCE_YML =
      """
      groups:
        - name: angular
          deps: ["@angular/*"]
      """;

  /**
   * The second repository: one EXTERNAL pin behind a property, and nothing else at all — so its
   * whole pending list belongs to the external half of the fallback, which is the branch the second
   * bump story writes.
   */
  private static final String SECOND_POM =
      """
      <project>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-eventstream</artifactId>
        <version>2026.821.3</version>
        <properties>
          <jackson.version>2.18.0</jackson.version>
        </properties>
        <dependencies>
          <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
          </dependency>
        </dependencies>
      </project>
      """;

  private static final String ROOT_TREE =
      "{\"entries\":["
          + "{\"name\":\"pom.xml\",\"type\":\"blob\"},"
          + "{\"name\":\"package.json\",\"type\":\"blob\"},"
          + "{\"name\":\"package-lock.json\",\"type\":\"blob\"},"
          + "{\"name\":\"Dockerfile\",\"type\":\"blob\"},"
          + "{\"name\":\"service\",\"type\":\"tree\"}]}";

  private static final String SECOND_ROOT_TREE =
      "{\"entries\":[{\"name\":\"pom.xml\",\"type\":\"blob\"}]}";

  /**
   * Arms every document a scan of this platform reads.
   *
   * <p>Called from {@link StoryProfile}, once, before the artifact is launched — so the very first
   * scan a story starts finds a platform already there. It is idempotent by construction: every call
   * REPLACES an armed answer, so a second invocation from a second classloader is a no-op with extra
   * steps rather than a second fixture.
   */
  public static void arm() {
    StoryPeers projects = StoryPeers.named(StoryTarget.PROJECTS);
    StoryPeers githost = StoryPeers.named(StoryTarget.GITHOST);
    StoryPeers artifacts = StoryPeers.named(StoryTarget.ARTIFACTS);
    StoryPeers mirror = StoryPeers.named(StoryTarget.MIRROR);
    // qits-ci is started here too, so its address is in the launch command; what it ANSWERS is armed
    // by the bump stories, because a trigger's answer is the thing those stories move. The release
    // ask is armed there too, by the one story whose branch moved — and it lands on qits-projects,
    // which is already started for the catalog.
    StoryPeers.named(StoryTarget.CI);

    // THE CATALOG, with a row that has no name. qits-projects lists rows whose alias is unset, every
    // read this service makes is name-addressed, and a scan must skip such a row rather than fail on
    // it — so the fixture carries one.
    //
    // AND THE ARCHETYPE IS ON EXACTLY ONE ROW, deliberately. The key is nullable over there and an
    // older answer carries none at all, so the fixture holds both shapes at once: the first
    // repository is classified and the second is not, which is what makes "a repository nobody
    // classified is still scanned, still listed and still pins things" a thing the story shows
    // rather than a sentence in a comment.
    projects.json(
        CATALOG_PATH,
        "{\"repositories\":["
            + "{\"id\":\"" + CATALOG_ID + "\",\"projectId\":\"" + PROJECT + "\",\"name\":\"" + REPOSITORY
            + "\",\"mainBranch\":\"main\",\"archetype\":\"" + ARCHETYPE + "\"},"
            + "{\"id\":\"r2\",\"projectId\":\"" + PROJECT + "\",\"name\":\"" + SECOND_REPOSITORY
            + "\",\"mainBranch\":\"main\",\"archetype\":null},"
            + "{\"id\":\"r3\",\"projectId\":\"" + PROJECT + "\",\"name\":null,"
            + "\"mainBranch\":\"main\"}]}");

    // THE GIT HOST. The head is resolved once per repository — a read of the root tree at `main`
    // that both resolves the branch and proves the repository is readable — and every manifest is
    // then read at the SHA. The same listing is armed at the sha as well, because it is what a 404
    // on a blob is checked against: answered means ABSENT, 404 means GONE.
    Map<String, String> sha = Map.of("Git-Commit-Sha", HEAD_SHA);
    githost.json(tree(REPOSITORY, "main"), ROOT_TREE, sha);
    githost.json(tree(REPOSITORY, HEAD_SHA), ROOT_TREE, sha);
    githost.text(blob(REPOSITORY, HEAD_SHA, "pom.xml"), POM, sha);
    githost.text(blob(REPOSITORY, HEAD_SHA, "service/pom.xml"), MODULE_POM, sha);
    githost.text(blob(REPOSITORY, HEAD_SHA, "package.json"), PACKAGE_JSON, sha);
    githost.text(blob(REPOSITORY, HEAD_SHA, "package-lock.json"), PACKAGE_LOCK, sha);
    githost.text(blob(REPOSITORY, HEAD_SHA, "Dockerfile"), DOCKERFILE, sha);
    githost.text(blob(REPOSITORY, HEAD_SHA, ".config/qits/maintenance.yml"), MAINTENANCE_YML, sha);

    Map<String, String> secondSha = Map.of("Git-Commit-Sha", SECOND_HEAD_SHA);
    githost.json(tree(SECOND_REPOSITORY, "main"), SECOND_ROOT_TREE, secondSha);
    githost.json(tree(SECOND_REPOSITORY, SECOND_HEAD_SHA), SECOND_ROOT_TREE, secondSha);
    githost.text(blob(SECOND_REPOSITORY, SECOND_HEAD_SHA, "pom.xml"), SECOND_POM, secondSha);
    // …and NO .config/qits/maintenance.yml, deliberately. See the class comment.

    // THE INTERNAL REGISTRIES. Which address answers is decided by the pin's KIND, not by the
    // ecosystem alone: `eu.wohlben.qits` and `@qits` and `qits/` are this platform's own, so they go
    // to qits-artifacts and never through the mirror.
    artifacts.xml(
        metadataPath(StoryTarget.MAVEN_REGISTRY_PREFIX, "eu.wohlben.qits", "qits-eventstream"),
        metadata("2026.811.1", "2026.821.3", "2026.900.1-SNAPSHOT"));
    artifacts.xml(
        metadataPath(StoryTarget.MAVEN_REGISTRY_PREFIX, "eu.wohlben.qits", "qits-parent"),
        metadata("2026.800.1", "2026.820.1"));
    artifacts.xml(
        metadataPath(StoryTarget.MAVEN_REGISTRY_PREFIX, "eu.wohlben.qits", "qits-arch-rules"),
        metadata("2026.817.175344", "2026.822.1"));
    artifacts.json(
        packumentPath(StoryTarget.NPM_REGISTRY_PREFIX, "@qits/ui-components"),
        packument("2026.8.1", "2026.8.4"));
    artifacts.json(
        tagsPath("qits/build-images/maven-base"),
        "{\"name\":\"qits/build-images/maven-base\","
            + "\"tags\":[\"latest\",\"2026.813.1\",\"2026.821.2\"]}");

    // THE MIRROR. An EXTERNAL pin is asked of Maven Central and npmjs through the cache, and asking
    // the wrong one of the two would answer 404 — which reads exactly like "up to date".
    mirror.xml(
        metadataPath(StoryTarget.MAVEN_MIRROR_PREFIX, "io.quarkus.platform", "quarkus-bom"),
        metadata("3.34.5", "3.34.6", "3.35.0.CR1"));
    mirror.xml(
        metadataPath(StoryTarget.MAVEN_MIRROR_PREFIX, "com.fasterxml.jackson.core", "jackson-databind"),
        metadata("2.18.0", "2.19.1"));
    mirror.json(
        packumentPath(StoryTarget.NPM_MIRROR_PREFIX, "@angular/core"), packument("21.0.4", "21.1.0"));
  }

  /**
   * A {@code maven-metadata.xml} carrying a versions list.
   *
   * <p>The document's own {@code <latest>} is left out on purpose: this service ranks the published
   * list rather than trusting a field the last deploy wrote, and a fixture that supplied one would
   * hide whether it does.
   */
  private static String metadata(String... versions) {
    StringBuilder xml = new StringBuilder("<metadata><versioning><versions>");
    for (String version : versions) {
      xml.append("<version>").append(version).append("</version>");
    }
    return xml.append("</versions></versioning></metadata>").toString();
  }

  /** A packument: the version set, and a {@code dist-tags.latest} pointing at the newest. */
  private static String packument(String... versions) {
    StringBuilder json = new StringBuilder("{\"versions\":{");
    for (int i = 0; i < versions.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append(versions[i]).append("\":{}");
    }
    return json.append("},\"dist-tags\":{\"latest\":\"")
        .append(versions[versions.length - 1])
        .append("\"}}")
        .toString();
  }
}

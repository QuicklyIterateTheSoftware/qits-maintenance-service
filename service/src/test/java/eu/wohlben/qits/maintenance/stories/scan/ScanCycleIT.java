package eu.wohlben.qits.maintenance.stories.scan;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.maintenance.stories.support.StoryCatalog;
import eu.wohlben.qits.maintenance.stories.support.StoryIdentities;
import eu.wohlben.qits.maintenance.stories.support.StoryNetwork;
import eu.wohlben.qits.maintenance.stories.support.StoryPeers;
import eu.wohlben.qits.maintenance.stories.support.StoryProfile;
import eu.wohlben.qits.maintenance.stories.support.StoryTarget;
import eu.wohlben.qits.maintenance.stories.support.StoryWaits;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>Where the inventory comes from</b> — the one flow in this service that touches every peer it
 * has, and the one whose failure mode is the whole reason the inventory is a store.
 *
 * <p>Pressing <i>Scan</i> is a single POST and 202. What it starts is a read of the catalog, one
 * head resolution and six manifest reads per repository, and a registry lookup per DEPENDENCY —
 * and the diagram beside the first story is that whole list, drawn from what the five stand-in
 * peers were really asked and what they really answered. A diagram of the near side alone would say
 * a scan is a POST.
 *
 * <p><b>The two stories are the two halves of what a scan has to survive</b>, and the second is the
 * one that costs something if it is wrong:
 *
 * <ul>
 *   <li><b>the read</b>: every manifest at ONE commit, every ecosystem parsed, and each pin sent to
 *       the registry its KIND names — an internal artifact to qits-artifacts, an external one
 *       through the mirror's Central cache. Asking the wrong one answers 404, which reads exactly
 *       like "up to date".
 *   <li><b>the outage</b>: a git host that is not there costs ONE repository its status and never
 *       its pins. A peer that could not be asked is not evidence that a repository stopped pinning
 *       anything, and wiping on every hiccup would make "pending" flicker to zero whenever the git
 *       host restarted. That claim can only be made by watching the old behaviour STOP and then
 *       start again, which is what the second story does.
 * </ul>
 *
 * <p><b>The method order is load-bearing rather than tidy.</b> Every far side here is a cumulative
 * recording attributed by a cursor, so what a story sees is what was recorded since the last drain.
 * The outage story's central claim — that the mirror was never asked — is a claim about requests
 * that were not made, and it is only checkable once the story that DID ask has drained.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ScanCycleIT {

  static final String CATEGORY = "the scan";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String READ = "A scan reads every manifest at one commit and asks the registries what is newest";

  static final String READ_SLUG = Slugs.slug(READ);

  static final String OUTAGE = "A git host that is not there costs one repository its status and never its pins";

  static final String OUTAGE_SLUG = Slugs.slug(OUTAGE);

  @BeforeAll
  static void tapEverySideOfThisService() {
    StoryNetwork.install();
  }

  /**
   * The git host goes back on whatever the outage story did, and it goes back on here rather than
   * only in that story's own {@code finally}: an assertion that fails mid-story skips the rest of
   * the method, and a peer left dark would take every story after it with it.
   */
  @AfterEach
  void theGitHostIsReachableAgain() {
    StoryPeers.attach(StoryTarget.GITHOST).reachable(true);
  }

  @UserStory(value = READ, category = CATEGORY)
  @UserStoryDescription(
      """
      An operator presses Scan. What comes back at once is a 202 and an id, because a scan of the
      whole catalog is one git-host read per repository plus a registry lookup per dependency and
      an HTTP request is the wrong place to hold that. What happens behind it is the whole of this
      service's reading: qits-projects is asked which repositories exist, each one's main branch is
      resolved to a commit ONCE and every manifest is then read at that commit — so a repository's
      pins correspond to a tree that really existed rather than to whatever moved while the scan
      ran — and every pin that has a version to compare is asked about at the registry its kind
      names. When the row closes, the question "who is still on last month's release" has an
      answer that needs no peer at all.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void aScanFillsTheInventoryFromEveryPeerItHas(Interactions story, Network network) {
    // The tap sees a request and never a narrative role, so the actor is named before the first
    // call rather than described afterwards. A scan is a person's decision here: no scan bumps
    // anything, and pressing Scan asks what is out of date rather than for a branch.
    NetworkCapture.actor(StoryIdentities.OPERATOR);

    String id =
        StoryIdentities.operator(given())
            .contentType(ContentType.JSON)
            .body("{\"scope\":\"ALL\"}")
            .post(StoryTarget.SCANS)
            .then()
            .statusCode(202)
            .contentType(ContentType.JSON)
            .extract()
            .path("id");
    story
        .note("Scan answers 202 with the id of a row, and does not wait: the work is minutes of"
            + " other services' reads")
        .as("scan-accepted");

    // The row is how a client follows it — and every poll is a fresh request, which is exactly what
    // the client's own progress spinner does. The id is never written into a note: it is generated
    // per run, and a note carrying it would move this story's definition on every build.
    assertEquals("SUCCEEDED", StoryWaits.scan(id), "the scan did not finish cleanly");
    StoryIdentities.operator(given())
        .get(StoryTarget.SCANS + "/" + id)
        .then()
        .statusCode(200)
        .body("scope", equalTo("ALL"))
        .body("trigger", equalTo("MANUAL"))
        .body("repository", nullValue())
        .body("finishedAt", not(nullValue()))
        .body("message", containsString("2 repositories"));
    story
        .note("the row closes SUCCEEDED over both repositories the catalog names — and the third"
            + " row it lists, the one with no name, has no address and was skipped")
        .as("scan-finished");

    // --- what the peers were really asked ----------------------------------------------------------
    //
    // The diagram carries the whole list; these are the three claims a picture cannot make on its
    // own, each one a rule this service has rather than an implementation detail.
    StoryPeers githost = StoryPeers.attach(StoryTarget.GITHOST);
    assertEquals(
        1,
        githost.requestsTo(StoryCatalog.tree(StoryCatalog.REPOSITORY, "main")),
        "the branch must be resolved to a commit exactly once per repository");
    assertTrue(
        githost.recordedRequests().stream()
            .filter(request -> request.path().contains("/blob/"))
            .allMatch(request -> request.path().contains(StoryCatalog.HEAD_SHA)
                || request.path().contains(StoryCatalog.SECOND_HEAD_SHA)),
        "every manifest must be read at the resolved sha, never at a branch name");
    story
        .note("each repository's branch was resolved to a commit ONCE and every manifest was read"
            + " at that commit, so its pins are a snapshot of one tree")
        .as("one-commit-per-repository");

    // A 404 IS NOT AN ANSWER UNTIL IT HAS BEEN ASKED TWICE. The git host spells "no such revision"
    // and "no such path" identically, so a 404 on a file is followed by a read of the ROOT tree at
    // the same sha: answered means the file is ABSENT, 404 means the commit is GONE. The second
    // repository carries no .config/qits/maintenance.yml, so both calls are in the diagram.
    assertEquals(
        1,
        githost.requestsTo(
            StoryCatalog.blob(
                StoryCatalog.SECOND_REPOSITORY,
                StoryCatalog.SECOND_HEAD_SHA,
                ".config/qits/maintenance.yml")),
        "the missing grouping file must have been asked for");
    assertEquals(
        1,
        githost.requestsTo(
            StoryCatalog.tree(StoryCatalog.SECOND_REPOSITORY, StoryCatalog.SECOND_HEAD_SHA)),
        "a 404 on a file must be followed by a read of the root tree at the same sha");
    story
        .note("a repository with no grouping file 404s and is then asked about a second time — the"
            + " root tree at the same commit is what tells an absent FILE from a missing COMMIT")
        .as("absent-is-not-gone");

    // OUTBOUND, THIS SERVICE IS A MACHINE AND NOTHING ELSE. Every call above carried the
    // forward-auth pair, and the role on it is qits:system — never qits:admin, which is the human
    // role and one this service holds nowhere, not even when the scan was a person's button.
    assertTrue(
        githost.recordedRequests().stream()
            .allMatch(request -> StoryIdentities.MACHINE_ROLE.equals(request.roles())),
        "every outbound call must assert qits:system and only that");
    story
        .note("and every one of those reads went out as a MACHINE: the operator pressed the button,"
            + " and what reached the git host asserted qits:system rather than their own role")
        .as("outbound-is-a-machine");

    // --- what the inventory now holds ----------------------------------------------------------
    StoryIdentities.operator(given())
        .get(StoryTarget.REPOSITORIES)
        .then()
        .statusCode(200)
        .body("name", hasItem(StoryCatalog.REPOSITORY))
        .body("name", hasItem(StoryCatalog.SECOND_REPOSITORY))
        // The nameless catalog row has no address, so it is not a repository here at all.
        .body("name", not(hasItem(nullValue())))
        .body("find { it.name == '" + StoryCatalog.REPOSITORY + "' }.status", equalTo("OK"))
        .body(
            "find { it.name == '" + StoryCatalog.REPOSITORY + "' }.headSha",
            equalTo(StoryCatalog.HEAD_SHA))
        // The repository's file still declares `angular` under the retired `groups:` key, which is
        // ignored (qits-1133 R5): every repository's pins split into the two KIND groups.
        .body(
            "find { it.name == '" + StoryCatalog.REPOSITORY + "' }.groups.name",
            equalTo(List.of(StoryCatalog.DEFAULT_GROUP, StoryCatalog.EXTERNAL_GROUP)))
        // The second repository carries no configuration, so the fallback IS its whole grouping —
        // and the fallback is the split: our own releases on one branch, everybody else's on the
        // other.
        .body(
            "find { it.name == '" + StoryCatalog.SECOND_REPOSITORY + "' }.groups.name",
            equalTo(List.of(StoryCatalog.DEFAULT_GROUP, StoryCatalog.EXTERNAL_GROUP)))
        .body(
            "find { it.name == '" + StoryCatalog.SECOND_REPOSITORY + "' }.groups.kind",
            equalTo(List.of("INTERNAL", "EXTERNAL")));
    story
        .note("both repositories are in the inventory, each with the grouping it configured — and"
            + " the one that configured none gets the fallback, which is the internal/external"
            + " split rather than one branch for everything")
        .as("inventory-filled");

    String repository = StoryTarget.REPOSITORIES + "/" + StoryCatalog.REPOSITORY;
    StoryIdentities.operator(given())
        .get(repository)
        .then()
        .statusCode(200)
        // maven through a property, which is where the LINE is — rewriting the dependency element
        // would replace an expression with a literal.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.location",
            equalTo("property:qits.eventstream.version"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.kind", equalTo("INTERNAL"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.latest",
            equalTo("2026.821.3"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pending", equalTo(true))
        // npm's version is the LOCK's, and the manifest's range rides beside it.
        .body("pins.find { it.name == '@angular/core' }.version", equalTo("21.0.4"))
        .body("pins.find { it.name == '@angular/core' }.range", equalTo("^21.0.0"))
        .body("pins.find { it.name == '@angular/core' }.group", equalTo(StoryCatalog.EXTERNAL_GROUP))
        // docker, anchored on a line.
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.location", equalTo("line:2"))
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.latest",
            equalTo("2026.821.2"))
        // THE TWO KINDS THAT ARE NEVER LOOKED UP AND ARE STILL SHOWN. A sibling module moves with
        // this repository's own release train and no line anywhere holds it; an expression nobody
        // declared never became a version and must never become a URL.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-ci-domain' }.kind", equalTo("REACTOR"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-ci-domain' }.latest", nullValue())
        .body("pins.find { it.name == 'g:mystery' }.kind", equalTo("UNRESOLVED"))
        .body("pins.find { it.name == 'g:mystery' }.latest", nullValue())
        // …and the repository's own root pom is not a dependency of its own module at all.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-ci' }", nullValue())
        // A prerelease is offered only to a pin that is one too: the metadata carries 3.35.0.CR1
        // and what this pin is offered is the highest RELEASE.
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }.latest", equalTo("3.34.6"))
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }.kind", equalTo("EXTERNAL"))
        .body("pending", greaterThan(0));
    story
        .note("every ecosystem is parsed and each pin records WHERE its version is set, because a"
            + " wrong location is a wrong edit in somebody else's repository")
        .as("pins-carry-their-location");
    story
        .note("two kinds are shown and never asked about: a sibling module has no line to edit, and"
            + " an expression that never became a version must never become a URL")
        .as("reactor-and-unresolved-are-never-looked-up");

    // THE ONE DECLARATION. Every arrow above is evidence; this one cannot be, because the
    // connection is opened inside the launched process between it and a postgres neither the
    // RestAssured tap nor any peer's recording ever sees. It renders muted and marked [declared]
    // for exactly that reason.
    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "one repository's whole inventory is replaced in one transaction");
  }

  @UserStory(value = OUTAGE, category = CATEGORY)
  @UserStoryDescription(
      """
      The question this service exists to answer has to survive its peers being away. A scan reads
      every repository in the catalog, so one unreachable git host must cost one row's status and
      not the run — and, more than that, it must not cost that repository its PINS. A peer that
      could not be asked is not evidence that a repository stopped pinning anything, and an
      inventory that emptied on every hiccup would make "pending" flicker to zero whenever the git
      host restarted. So the git host goes dark between two scans and the story watches what the
      inventory does about it: the row says UNREACHABLE and carries the sentence, the pins are
      exactly the ones that were there, and the next scan that reaches the git host puts the row
      back where it was. Nothing is asked of Maven Central along the way — an INTERNAL scan
      refreshes the internal registry's half and nothing else.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(2)
  void anUnreachableGitHostKeepsThePinsItCouldNotReRead(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    String repository = StoryTarget.REPOSITORIES + "/" + StoryCatalog.REPOSITORY;
    StoryPeers githost = StoryPeers.attach(StoryTarget.GITHOST);

    int pinsBefore =
        StoryIdentities.operator(given())
            .get(repository)
            .then()
            .statusCode(200)
            .body("status", equalTo("OK"))
            .extract()
            .path("pins.size()");
    assertTrue(pinsBefore > 0, "the earlier scan must have left pins to lose");
    story
        .note("the repository is OK and its pins are what the last scan read")
        .as("inventory-before");

    try {
      // AN OUTAGE IS NOT A 500. The connection goes away with no status and no body, which is what
      // PeerClient.send really sees and the branch every PeerAnswer.error exists for. A 500 would
      // be a git host having an opinion; this is a git host that is not there.
      githost.reachable(false);
      String id = scan("INTERNAL", StoryCatalog.REPOSITORY);
      assertEquals("SUCCEEDED", StoryWaits.scan(id), "one dark peer must not fail the scan itself");
      story
          .note("with the git host dark, the scan still SUCCEEDS: one unreachable repository is that"
              + " repository's status, never the run's")
          .as("the-scan-survives-it");

      StoryIdentities.operator(given())
          .get(repository)
          .then()
          .statusCode(200)
          .body("status", equalTo("UNREACHABLE"))
          .body("message", not(nullValue()))
          // THE CLAIM, AND IT IS THE OLD BEHAVIOUR NOT STOPPING: the pins are still there, and
          // still the same number of them.
          .body("pins.size()", equalTo(pinsBefore));
      story
          .note("the row says UNREACHABLE and carries the sentence — and every pin it had is still"
              + " there, because a peer that could not be asked is not evidence that a repository"
              + " stopped pinning anything")
          .as("unreachable-keeps-its-pins");
    } finally {
      githost.reachable(true);
    }

    // …AND THE OTHER HALF, which is what makes this a proof rather than an anecdote: the same scan
    // against a git host that answers puts the row back exactly where it was. Note the scope is
    // INTERNAL again and the manifests are re-read anyway — that is the rule, and it is what keeps
    // the inventory from reporting changes against pins somebody removed yesterday.
    String recovery = scan("INTERNAL", StoryCatalog.REPOSITORY);
    assertEquals("SUCCEEDED", StoryWaits.scan(recovery), "the recovery scan did not finish cleanly");
    StoryIdentities.operator(given())
        .get(repository)
        .then()
        .statusCode(200)
        .body("status", equalTo("OK"))
        .body("headSha", equalTo(StoryCatalog.HEAD_SHA))
        .body("pins.size()", equalTo(pinsBefore));
    story
        .note("the git host comes back and the next scan puts the row where it was, at the same"
            + " commit and with the same pins: nothing was lost by being away")
        .as("the-row-comes-back");

    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "an unreachable repository's status is written and its inventory is left alone");
  }

  /** Starts one scan of one repository at one scope, and answers with the row's id. */
  private static String scan(String scope, String repository) {
    return StoryIdentities.operator(given())
        .contentType(ContentType.JSON)
        .body("{\"scope\":\"" + scope + "\",\"repository\":\"" + repository + "\"}")
        .post(StoryTarget.SCANS)
        .then()
        .statusCode(202)
        .extract()
        .path("id");
  }

  @AfterAll
  static void bothScanStoriesAreComplete() {
    // --- the read -------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, READ_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, READ_SLUG, "scan-accepted");
    ReportAssertions.assertStepId(CATEGORY_SLUG, READ_SLUG, "scan-finished");
    ReportAssertions.assertStepId(CATEGORY_SLUG, READ_SLUG, "one-commit-per-repository");
    ReportAssertions.assertStepId(CATEGORY_SLUG, READ_SLUG, "absent-is-not-gone");
    ReportAssertions.assertStepId(CATEGORY_SLUG, READ_SLUG, "outbound-is-a-machine");
    ReportAssertions.assertStepId(CATEGORY_SLUG, READ_SLUG, "inventory-filled");
    ReportAssertions.assertStepId(CATEGORY_SLUG, READ_SLUG, "pins-carry-their-location");
    ReportAssertions.assertStepId(
        CATEGORY_SLUG, READ_SLUG, "reactor-and-unresolved-are-never-looked-up");

    in(READ_SLUG, "POST " + StoryTarget.SCANS + " -> 202");
    in(READ_SLUG, "GET " + StoryTarget.SCANS + "/" + StoryTarget.ID + " -> 200");
    in(READ_SLUG, "GET " + StoryTarget.REPOSITORIES + " -> 200");
    in(READ_SLUG, "GET " + StoryTarget.REPOSITORIES + "/" + StoryCatalog.REPOSITORY + " -> 200");

    out(READ_SLUG, StoryTarget.PROJECTS, "GET " + StoryCatalog.CATALOG_PATH + " -> 200");

    // The git host, in the order a scan makes them: one head resolution per repository, then every
    // manifest at that commit — and the pair that tells an absent FILE from a missing COMMIT.
    out(READ_SLUG, StoryTarget.GITHOST, tree(StoryCatalog.REPOSITORY, "main", "200"));
    out(READ_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "pom.xml", "200"));
    out(READ_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "service/pom.xml", "200"));
    out(READ_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "package.json", "200"));
    out(READ_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "package-lock.json", "200"));
    out(READ_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "Dockerfile", "200"));
    out(
        READ_SLUG,
        StoryTarget.GITHOST,
        blob(StoryCatalog.REPOSITORY, ".config/qits/maintenance.yml", "200"));
    out(READ_SLUG, StoryTarget.GITHOST, tree(StoryCatalog.SECOND_REPOSITORY, "main", "200"));
    out(READ_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.SECOND_REPOSITORY, "pom.xml", "200"));
    out(
        READ_SLUG,
        StoryTarget.GITHOST,
        blob(StoryCatalog.SECOND_REPOSITORY, ".config/qits/maintenance.yml", "404"));
    out(
        READ_SLUG,
        StoryTarget.GITHOST,
        tree(StoryCatalog.SECOND_REPOSITORY, StoryTarget.DIGEST, "200"));

    // The internal registries: one lookup per DEPENDENCY, not per pin.
    out(READ_SLUG, StoryTarget.ARTIFACTS, internalMetadata("qits-parent"));
    out(READ_SLUG, StoryTarget.ARTIFACTS, internalMetadata("qits-eventstream"));
    out(READ_SLUG, StoryTarget.ARTIFACTS, internalMetadata("qits-arch-rules"));
    out(
        READ_SLUG,
        StoryTarget.ARTIFACTS,
        "GET "
            + StoryCatalog.packumentWire(StoryTarget.NPM_REGISTRY_PREFIX, "@qits/ui-components")
            + " -> 200");
    out(
        READ_SLUG,
        StoryTarget.ARTIFACTS,
        "GET " + StoryCatalog.tagsWire("qits/build-images/maven-base") + " -> 200");

    // …and the mirror, for the pins this platform did not publish.
    out(
        READ_SLUG,
        StoryTarget.MIRROR,
        "GET "
            + StoryCatalog.metadataPath(
                StoryTarget.MAVEN_MIRROR_PREFIX, "io.quarkus.platform", "quarkus-bom")
            + " -> 200");
    out(
        READ_SLUG,
        StoryTarget.MIRROR,
        "GET "
            + StoryCatalog.metadataPath(
                StoryTarget.MAVEN_MIRROR_PREFIX, "com.fasterxml.jackson.core", "jackson-databind")
            + " -> 200");
    out(
        READ_SLUG,
        StoryTarget.MIRROR,
        "GET "
            + StoryCatalog.packumentWire(StoryTarget.NPM_MIRROR_PREFIX, "@angular/core")
            + " -> 200");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        READ_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "one repository's whole inventory is replaced in one transaction");

    // FOUR REQUESTS IN, TWENTY OUT, AND ONE WRITE THIS TAP CANNOT SEE. The count is what makes the
    // list above a closure rather than a sample: a lookup this service should not have made — an
    // UNRESOLVED expression turned into a URL, a REACTOR sibling offered as an upgrade, an external
    // image ranked against a vendor's tags — would be a twenty-sixth edge, and no presence check
    // could see it.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, READ_SLUG, 25);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, READ_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
    // A person's identity arrives in a header the edge asserted, so nothing here re-asks the idp
    // who they are — not once, not on a cache miss, not at all.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, READ_SLUG, "qits-platform-idp");
    // AND NOTHING WAS ASKED OF qits-ci. A scan finds out what is out of date; asking for a branch
    // is a release request's pre-run, and the dispatcher's own is a different schedule.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, READ_SLUG, StoryTarget.CI);

    // --- the outage -----------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, OUTAGE_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, OUTAGE_SLUG, "inventory-before");
    ReportAssertions.assertStepId(CATEGORY_SLUG, OUTAGE_SLUG, "the-scan-survives-it");
    ReportAssertions.assertStepId(CATEGORY_SLUG, OUTAGE_SLUG, "unreachable-keeps-its-pins");
    ReportAssertions.assertStepId(CATEGORY_SLUG, OUTAGE_SLUG, "the-row-comes-back");

    in(OUTAGE_SLUG, "POST " + StoryTarget.SCANS + " -> 202");
    in(OUTAGE_SLUG, "GET " + StoryTarget.SCANS + "/" + StoryTarget.ID + " -> 200");
    in(OUTAGE_SLUG, "GET " + StoryTarget.REPOSITORIES + "/" + StoryCatalog.REPOSITORY + " -> 200");

    out(OUTAGE_SLUG, StoryTarget.PROJECTS, "GET " + StoryCatalog.CATALOG_PATH + " -> 200");
    // THE EDGE THE WHOLE STORY IS ABOUT, and it is labelled with a WORD: no status code was ever on
    // the wire, and writing 000 would put a number in a diagram where none was sent.
    out(
        OUTAGE_SLUG,
        StoryTarget.GITHOST,
        tree(StoryCatalog.REPOSITORY, "main", StoryPeers.DROPPED));
    out(OUTAGE_SLUG, StoryTarget.GITHOST, tree(StoryCatalog.REPOSITORY, "main", "200"));
    out(OUTAGE_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "pom.xml", "200"));
    out(OUTAGE_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "service/pom.xml", "200"));
    out(OUTAGE_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "package.json", "200"));
    out(OUTAGE_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "package-lock.json", "200"));
    out(OUTAGE_SLUG, StoryTarget.GITHOST, blob(StoryCatalog.REPOSITORY, "Dockerfile", "200"));
    out(
        OUTAGE_SLUG,
        StoryTarget.GITHOST,
        blob(StoryCatalog.REPOSITORY, ".config/qits/maintenance.yml", "200"));

    out(OUTAGE_SLUG, StoryTarget.ARTIFACTS, internalMetadata("qits-parent"));
    out(OUTAGE_SLUG, StoryTarget.ARTIFACTS, internalMetadata("qits-eventstream"));
    out(OUTAGE_SLUG, StoryTarget.ARTIFACTS, internalMetadata("qits-arch-rules"));
    out(
        OUTAGE_SLUG,
        StoryTarget.ARTIFACTS,
        "GET "
            + StoryCatalog.packumentWire(StoryTarget.NPM_REGISTRY_PREFIX, "@qits/ui-components")
            + " -> 200");
    out(
        OUTAGE_SLUG,
        StoryTarget.ARTIFACTS,
        "GET " + StoryCatalog.tagsWire("qits/build-images/maven-base") + " -> 200");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        OUTAGE_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "an unreachable repository's status is written and its inventory is left alone");

    // THE SECOND HALF OF THE TITLE, ASSERTED AS A SHAPE. Both scans here were INTERNAL, and the
    // scope governs the REGISTRY half: the manifests are re-read either way, and Maven Central is
    // not asked at all. An arrow to the mirror would be the scope not being honoured, and no
    // presence check could see its absence.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, OUTAGE_SLUG, StoryTarget.MIRROR);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, OUTAGE_SLUG, StoryTarget.CI);
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, OUTAGE_SLUG, 18);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, OUTAGE_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
  }

  private static void in(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        slug,
        NetworkEdge.HTTP,
        StoryIdentities.OPERATOR,
        StoryTarget.SERVICE,
        label);
  }

  private static void out(String slug, String peer, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, StoryTarget.SERVICE, peer, label);
  }

  private static String tree(String repository, String revision, String status) {
    return "GET " + StoryCatalog.tree(repository, revision) + " -> " + status;
  }

  /** A blob read, with the resolved sha as {@link eu.wohlben.qits.userflows.Labels} rewrites it. */
  private static String blob(String repository, String path, String status) {
    return "GET "
        + StoryCatalog.blob(repository, StoryTarget.DIGEST, path)
        + " -> "
        + status;
  }

  private static String internalMetadata(String artifactId) {
    return "GET "
        + StoryCatalog.metadataPath(
            StoryTarget.MAVEN_REGISTRY_PREFIX, "eu.wohlben.qits", artifactId)
        + " -> 200";
  }
}

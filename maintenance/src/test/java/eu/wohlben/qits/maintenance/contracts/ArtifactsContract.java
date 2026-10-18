package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.changelog.ChangelogClient;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.sbom.SbomClient;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.List;

/**
 * <b>What qits-maintenance asks qits-artifacts, and why</b> (ticket qits-1149): an artifact's SBOM
 * ({@link SbomClient}), a repository's published changelogs ({@link ChangelogClient}), and what the
 * hosted registries hold — the latest version of an internal pin ({@code LatestResolver}) and every
 * version of a released artifact ({@code ArtifactPresence}).
 *
 * <p><b>The registry reads</b> bind qits-artifacts' recordings of its maven, npm and OCI mounts,
 * each read in a state that holds the name and one that does not: a 404 (or, for OCI, an empty
 * {@code tags}) is what {@code ArtifactPresence} counts as collected. {@code maven-metadata.xml} is
 * XML, which the consumer helper binds no paths of, so its 200 rows bind the status alone — and
 * wait ({@link PeerRow#readingBody}) while qits-artifacts records that 200 without the document,
 * since the {@code <versions>} the client reads is all the answer is for.
 */
final class ArtifactsContract {

  static final GoldenMasters ARTIFACTS = GoldenMasters.of("qits-artifacts-service", "qits-artifacts");

  static final String AN_SBOM_PUBLISHED = "an sbom published for the coordinate";
  static final String NO_SBOM_PUBLISHED = "no sbom published for the coordinate";
  static final String A_REPOSITORY_WITH_PUBLISHED_CHANGELOGS =
      "a repository with published changelogs";
  static final String A_REPOSITORY_WITH_NO_CHANGELOG = "a repository with no changelog";
  static final String A_DAEMON_WITH_TWO_VERSIONS = "a daemon with two versions";
  static final String A_MAVEN_ARTIFACT_PUBLISHED = "a maven artifact published at a version";
  static final String NO_MAVEN_ARTIFACT_PUBLISHED = "no maven artifact published at the version";
  static final String AN_NPM_PACKAGE_PUBLISHED = "an npm package published at a version";
  static final String NO_NPM_PACKAGE = "no npm package with the given name";
  static final String AN_IMAGE_PUSHED = "an image pushed under a tag";
  static final String NO_IMAGE = "no image with the given name";

  private static final Trigger SCAN = Trigger.schedule("ScanService.run");
  private static final Trigger PROBE = Trigger.schedule("SbomCheckService.probe");

  /** The operations qits-artifacts' registry mounts would be recorded under. */
  static final String GET_MAVEN_METADATA = "getMavenMetadata";

  static final String GET_NPM_PACKUMENT = "getNpmPackument";
  static final String LIST_IMAGE_TAGS = "listImageTags";

  /** A maven name the clients will address: a groupId:artifactId. */
  static final String MAVEN_NAME = "eu.wohlben.qits:contract-artifact";

  /** {@code SbomIngestService}: the graph an SBOM is parsed into. */
  static final List<String> SBOM =
      List.of(
          "metadata.component.bom-ref",
          "components[].bom-ref",
          "components[].purl",
          "components[].name",
          "components[].version",
          "dependencies[].ref",
          "dependencies[].dependsOn[]");

  static final List<PeerRow> ROWS =
      List.of(
          new PeerRow(
              GoldenInteraction.of(Trigger.event("SoftwareRelease"), AN_SBOM_PUBLISHED, "getSbom")
                  .consumes(SBOM)
                  .anyLength("components", "dependencies", "dependencies[].dependsOn"),
              (peers, p) -> {
                SbomClient.SbomAnswer answer =
                    peers.sboms().fetch(p.get("packageType"), p.get("packageName"), p.get("version"));
                assertEquals(SbomClient.Outcome.FOUND, answer.outcome(), answer.reason());
              }),
          new PeerRow(
              GoldenInteraction.of(Trigger.event("SoftwareRelease"), NO_SBOM_PUBLISHED, "getSbom"),
              (peers, p) ->
                  assertEquals(
                      SbomClient.Outcome.MISSING,
                      peers
                          .sboms()
                          .fetch(p.get("packageType"), p.get("packageName"), p.get("version"))
                          .outcome())),
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("BumpDispatcher.tick"),
                      A_REPOSITORY_WITH_PUBLISHED_CHANGELOGS,
                      "listDocsVersions")
                  .consumes("versions[].version")
                  .anyLength("versions"),
              (peers, p) -> {
                ChangelogClient.Result result = peers.changelogs().versions(p.get("repository"));
                assertEquals(ChangelogClient.Outcome.PUBLISHED, result.outcome(), result.reason());
                assertFalse(result.versions().isEmpty());
              }),
          new PeerRow(
              GoldenInteraction.of(
                  Trigger.schedule("BumpDispatcher.tick"),
                  A_REPOSITORY_WITH_NO_CHANGELOG,
                  "listDocsVersions"),
              (peers, p) ->
                  assertEquals(
                      ChangelogClient.Outcome.NONE,
                      peers.changelogs().versions(p.get("repository")).outcome())),
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("SbomCheckService.probe"),
                      A_DAEMON_WITH_TWO_VERSIONS,
                      "listDaemonVersions")
                  .consumes("versions[].version")
                  .anyLength("versions"),
              (peers, p) ->
                  assertFalse(
                      peers.presence().versions(Ecosystem.DAEMON_WIRE_NAME, p.get("daemon")).isEmpty())),
          // --- the hosted registries ------------------------------------------------------
          PeerRow.readingBody(
              GoldenInteraction.of(
                  SCAN, A_MAVEN_ARTIFACT_PUBLISHED, GET_MAVEN_METADATA),
              (peers, p) -> {
                LatestLookup latest =
                    peers.latest().resolve(Ecosystem.MAVEN, PinKind.INTERNAL, p.get("name", MAVEN_NAME));
                assertTrue(latest.ok(), latest.error());
              }),
          PeerRow.readingBody(
              GoldenInteraction.of(PROBE, A_MAVEN_ARTIFACT_PUBLISHED, GET_MAVEN_METADATA),
              (peers, p) -> assertFalse(peers.presence().versions("maven", p.get("name", MAVEN_NAME)).isEmpty())),
          new PeerRow(
              GoldenInteraction.of(SCAN, NO_MAVEN_ARTIFACT_PUBLISHED, GET_MAVEN_METADATA),
              (peers, p) ->
                  assertFalse(
                      peers.latest().resolve(Ecosystem.MAVEN, PinKind.INTERNAL, p.get("name", MAVEN_NAME)).ok())),
          new PeerRow(
              GoldenInteraction.of(PROBE, NO_MAVEN_ARTIFACT_PUBLISHED, GET_MAVEN_METADATA),
              (peers, p) -> assertTrue(peers.presence().versions("maven", p.get("name", MAVEN_NAME)).isEmpty())),
          new PeerRow(
              GoldenInteraction.of(SCAN, AN_NPM_PACKAGE_PUBLISHED, GET_NPM_PACKUMENT)
                  .consumes("versions", "dist-tags.latest"),
              (peers, p) -> {
                LatestLookup latest =
                    peers.latest().resolve(Ecosystem.NPM, PinKind.INTERNAL, p.get("package"));
                assertTrue(latest.ok(), latest.error());
                assertEquals(p.get("version"), latest.latest());
              }),
          new PeerRow(
              GoldenInteraction.of(PROBE, AN_NPM_PACKAGE_PUBLISHED, GET_NPM_PACKUMENT)
                  .consumes("versions"),
              (peers, p) ->
                  assertTrue(peers.presence().versions("npm", p.get("package")).contains(p.get("version")))),
          new PeerRow(
              GoldenInteraction.of(SCAN, NO_NPM_PACKAGE, GET_NPM_PACKUMENT),
              (peers, p) ->
                  assertFalse(peers.latest().resolve(Ecosystem.NPM, PinKind.INTERNAL, p.get("package")).ok())),
          new PeerRow(
              GoldenInteraction.of(PROBE, NO_NPM_PACKAGE, GET_NPM_PACKUMENT),
              (peers, p) -> assertTrue(peers.presence().versions("npm", p.get("package")).isEmpty())),
          new PeerRow(
              GoldenInteraction.of(SCAN, AN_IMAGE_PUSHED, LIST_IMAGE_TAGS)
                  .consumes("tags[]")
                  .anyLength("tags"),
              (peers, p) -> {
                LatestLookup latest =
                    peers.latest().resolve(Ecosystem.DOCKER, PinKind.INTERNAL, p.get("name"));
                assertTrue(latest.ok(), latest.error());
                assertEquals(p.get("tag"), latest.latest());
              }),
          new PeerRow(
              GoldenInteraction.of(PROBE, AN_IMAGE_PUSHED, LIST_IMAGE_TAGS)
                  .consumes("tags[]")
                  .anyLength("tags"),
              (peers, p) ->
                  assertTrue(peers.presence().versions("docker", p.get("name")).contains(p.get("tag")))),
          new PeerRow(
              GoldenInteraction.of(SCAN, NO_IMAGE, LIST_IMAGE_TAGS).consumes("tags"),
              (peers, p) ->
                  assertFalse(peers.latest().resolve(Ecosystem.DOCKER, PinKind.INTERNAL, p.get("name")).ok())),
          new PeerRow(
              GoldenInteraction.of(PROBE, NO_IMAGE, LIST_IMAGE_TAGS).consumes("tags"),
              (peers, p) -> assertTrue(peers.presence().versions("docker", p.get("name")).isEmpty())));

  static final PeerRow.Contract CONTRACT = new PeerRow.Contract(ARTIFACTS, ROWS);

  private ArtifactsContract() {}
}

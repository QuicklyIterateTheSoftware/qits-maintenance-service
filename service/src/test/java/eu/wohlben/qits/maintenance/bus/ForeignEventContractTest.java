package eu.wohlben.qits.maintenance.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The four foreign events this service consumes, checked against the wire form they have to
 * match.</b>
 *
 * <p>Both listeners here read their payloads through {@code CanonicalJson.payloadTo} into records of
 * their own, and every one of those records is a hand-kept copy of a record in another repository.
 * That is the right split — see below — and it leaves exactly one thing to go wrong: the publisher
 * renames the event or a field, this service compiles, and a listener silently stops acting. For
 * {@code SoftwareRelease} that is every internal release ceasing to move {@code mt_latest}; for the
 * SCM three it is branch state that never changes and pushes that never rescan. Neither failure has
 * a symptom anybody would look for in a log.
 *
 * <h2>Why a transcription rather than a dependency</h2>
 *
 * <p><b>Neither publisher ships a jar this repository may take.</b> qits-workspaces publishes no
 * vocabulary jar at all — measured by qits-ci on 2026-08-12 against the platform Maven registry,
 * which serves {@code qits-githost-events} and {@code qits-eventstream} and answers "nothing is
 * deployed" for {@code qits-workspaces-events} — so a dependency on it would compile from a
 * developer's {@code ~/.m2} and fail to resolve in a release pipeline's own step container.
 * {@code qits-ci-events} and {@code qits-githost-events} are published and could be taken; they are
 * not, because this repository's rule is that what crosses from another context is a wire contract
 * and not a type, and taking three jars for four field lists would put three other repositories'
 * releases in front of this one's build.
 *
 * <h2>So the TRANSCRIPTIONS below are the contract, and this file is where they are kept</h2>
 *
 * <p>Each record here transcribes the component list, order and types of one record in another
 * repository, named at its declaration. It is <b>not</b> a fixture of expected JSON: the bytes are
 * produced by {@link CanonicalJson}, the same serializer the real publisher runs the real record
 * through, so every rule about the wire form — alphabetical keys, the {@code QitsEvent} accessors the
 * mix-in hides — stays the library's rather than something this file guessed. What a person keeps in
 * step is one list of component names per event.
 *
 * <p>The tests then drive those canonical bytes through the listeners' OWN payload records, so the
 * claim is not "the field is present" but "the field this listener reads binds to the value the
 * publisher put there".
 *
 * <p><b>Read that as the standing instruction it is:</b> a change to one of those records in
 * qits-ci, qits-workspaces or qits-githost is a change to this transcription, in the same campaign. A
 * rename that lands there and not here leaves this suite green and a listener deaf — the one failure
 * this file cannot prevent, and why it says so out loud.
 */
class ForeignEventContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant WHEN = Instant.parse("2026-09-01T10:00:00Z");

  // --- the transcriptions ----------------------------------------------------------------------

  /**
   * qits-ci's {@code SoftwareRelease}, from
   * {@code components/qits-ci/qits-ci-service/ci-events/src/main/java/eu/wohlben/qits/ci/events/SoftwareRelease.java}.
   *
   * <p>The NAME is transcribed too, which is what makes the signature assertion below say anything:
   * {@code signature()} is the simple class name, so this record has to be spelled exactly as qits-ci
   * spells it.
   *
   * <p><b>{@code section} and {@code runId} are transcribed AHEAD of the publisher</b> (qits-621 /
   * qits-668): qits-ci is adding them, appended last and nullable, in the same campaign. Nullable on
   * the wire means absent — {@code CanonicalJson} leaves a null key out — so every helper below that
   * passes null for them is also the proof that a frame from a qits-ci without the fields decodes.
   */
  record SoftwareRelease(
      UUID eventId,
      String repository,
      String projectId,
      String repoId,
      String repoName,
      String version,
      String packageType,
      String packageName,
      Instant occurredAt,
      String priority,
      String section,
      UUID runId)
      implements QitsEvent {}

  /**
   * qits-workspaces' {@code SCMRelease}, from
   * {@code components/qits-workspaces/qits-workspaces-service/workspaces-events/src/main/java/eu/wohlben/qits/workspaces/events/SCMRelease.java}.
   */
  record SCMRelease(
      UUID eventId,
      String projectId,
      String repository,
      String repositoryName,
      String branch,
      String version,
      Instant occurredAt)
      implements QitsEvent {}

  /**
   * qits-githost's {@code SCMDeleteBranch}, from
   * {@code components/qits-githost/qits-githost-service/githost-events/src/main/java/eu/wohlben/qits/githost/events/SCMDeleteBranch.java}.
   *
   * <p>Its {@code occurredAt} is an override returning {@code receivedAt} — the component is named
   * {@code receivedAt} and the accessor the mix-in hides is {@code occurredAt}, so both are spelled
   * here exactly as they are there.
   */
  record SCMDeleteBranch(
      UUID eventId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String sha,
      Instant receivedAt)
      implements QitsEvent {

    @Override
    public Instant occurredAt() {
      return receivedAt;
    }
  }

  /**
   * qits-githost's {@code SCMPublishCommit}, from
   * {@code components/qits-githost/qits-githost-service/githost-events/src/main/java/eu/wohlben/qits/githost/events/SCMPublishCommit.java}.
   *
   * <p><b>The whole component list is transcribed, not only the five this service consumes.</b> The
   * listener's own record carries five; this one carries fourteen, because the point of the canonical
   * bytes below is that they are what a real publisher emits — including every field the listener
   * ignores, which is exactly what proves the mapper ignores them rather than refusing the payload.
   */
  record SCMPublishCommit(
      UUID eventId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String oldSha,
      String sha,
      List<String> parents,
      String authorName,
      String authorEmail,
      Instant authoredAt,
      Instant committedAt,
      String message,
      Instant receivedAt)
      implements QitsEvent {

    @Override
    public Instant occurredAt() {
      return receivedAt;
    }
  }

  // --- what a publisher would send ---------------------------------------------------------------

  static String softwareReleasePayload(String packageType, String packageName, String version) {
    return softwareReleasePayload("qits-eventstream-javalib", packageType, packageName, version);
  }

  /**
   * The same payload with the {@code repository} field said out loud.
   *
   * <p><b>What that field actually carries is qits-projects' repository ROW ID</b>, measured live on
   * 2026-09-02 — a uuid, not the catalog name the default above spells. It stays a parameter rather
   * than becoming a uuid everywhere, because the listener has to answer for both: a name is passed
   * through unchanged and an id is resolved.
   */
  static String softwareReleasePayload(
      String repository, String packageType, String packageName, String version) {
    return CanonicalJson.payload(
        new SoftwareRelease(
            UUID.randomUUID(),
            repository,
            null,
            null,
            null,
            version,
            packageType,
            packageName,
            WHEN,
            null,
            null,
            null));
  }

  /** A frame carrying all three facts the SBOM check reads: project, section and run. */
  static String softwareReleasePayload(
      String packageType,
      String packageName,
      String version,
      String projectId,
      String section,
      UUID runId) {
    return CanonicalJson.payload(
        new SoftwareRelease(
            UUID.randomUUID(),
            "qits-eventstream-javalib",
            projectId,
            "storage-1",
            "qits-eventstream-javalib",
            version,
            packageType,
            packageName,
            WHEN,
            "NORMAL",
            section,
            runId));
  }

  static String scmReleasePayload(String repositoryName, String branch, String version) {
    return CanonicalJson.payload(
        new SCMRelease(
            UUID.randomUUID(), "qits", repositoryName, repositoryName, branch, version, WHEN));
  }

  static String scmDeleteBranchPayload(String repoName, String branch) {
    return CanonicalJson.payload(
        new SCMDeleteBranch(
            UUID.randomUUID(), "storage-1", "qits", repoName, branch, "deadbeef", WHEN));
  }

  static String scmPublishCommitPayload(String repoName, String branch, String sha) {
    return CanonicalJson.payload(
        new SCMPublishCommit(
            UUID.randomUUID(),
            "storage-1",
            "qits",
            repoName,
            branch,
            "0000000",
            sha,
            List.of("1111111"),
            "A Person",
            "person@example.test",
            WHEN,
            WHEN,
            "a commit",
            WHEN));
  }

  /** A frame as the funnel hands one over: identity and time on the envelope, payload as bytes. */
  static EventFrame frame(String name, String payload) {
    return new EventFrame(UUID.randomUUID().toString(), name, WHEN, payload, null, null, null);
  }

  // --- the names ----------------------------------------------------------------------------------

  @Test
  void theEventNamesTheListenersSubscribeToAreTheOnesTheseEventsRideUnder() {
    assertEquals(SoftwareRelease.class.getSimpleName(), SoftwareReleaseListener.SIGNATURE);
    assertEquals(SCMRelease.class.getSimpleName(), ScmEventListener.RELEASE_SIGNATURE);
    assertEquals(SCMDeleteBranch.class.getSimpleName(), ScmEventListener.DELETE_SIGNATURE);
    assertEquals(SCMPublishCommit.class.getSimpleName(), ScmEventListener.PUSH_SIGNATURE);
  }

  /**
   * The consumer ids are STORAGE keys and are pinned as literals here for the same reason the event
   * names are: a change to either is a brand-new consumer initializing at the head of the log and
   * silently skipping everything in between, so it has to be a diff somebody reviewed.
   */
  @Test
  void theConsumerIdsAreStorageKeysAndAreSpelledOut() {
    assertEquals("maintenance-internal-latest", SoftwareReleaseListener.CONSUMER_ID);
    assertEquals("maintenance-branch-tracking", ScmEventListener.CONSUMER_ID);
  }

  /**
   * <b>THE ABANDONED ID, pinned so it is never reused.</b>
   *
   * <p>{@code maintenance-release-trains} was a third durable consumer of {@code SoftwareRelease}.
   * The release trains were retired (V8) and the listener went with them — but its {@code
   * consumed_event} rows and its {@code consumer_watermark} are still in the eventstream database
   * and are deliberately left alone, which is the abandoned-consumer rule: a consumer id is STORAGE,
   * and deleting a watermark and deleting a consumer are not the same operation.
   *
   * <p><b>Which makes reuse the hazard, and it is a silent one.</b> A future listener adopting this
   * string would inherit a watermark saying it had already handled every release up to the day the
   * trains were removed — it would start deaf, with nothing in any log. So the string is pinned here
   * as a literal, beside the two that are live, and the assertion is that no live listener claims
   * it.
   */
  @Test
  void theAbandonedConsumerIdIsClaimedByNoLiveListener() {
    String abandoned = "maintenance-release-trains";
    assertNotEquals(abandoned, SoftwareReleaseListener.CONSUMER_ID);
    assertNotEquals(abandoned, ScmEventListener.CONSUMER_ID);
  }

  // --- the field lists ----------------------------------------------------------------------------

  @Test
  void aSoftwareReleaseBindsIntoTheRecordThisServiceReads() throws Exception {
    String payload = softwareReleasePayload("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1");

    SoftwareReleaseListener.SoftwareReleasePayload read =
        CanonicalJson.payloadTo(payload, SoftwareReleaseListener.SoftwareReleasePayload.class);
    assertEquals("qits-eventstream-javalib", read.repository());
    assertEquals("2026.901.1", read.version());
    assertEquals("maven", read.packageType());
    assertEquals("eu.wohlben.qits:qits-eventstream", read.packageName());

    // And the four names really are on the wire, so a rename cannot be hidden by a null-tolerant
    // binding.
    JsonNode json = MAPPER.readTree(payload);
    for (String field : List.of("repository", "version", "packageType", "packageName")) {
      assertTrue(json.has(field), "the canonical payload carries no " + field);
    }

    // A publisher that states no project, section or run leaves the keys OUT, and the record still
    // binds — the three are optional, and absence reads as null rather than as a refusal.
    for (String field : List.of("projectId", "section", "runId")) {
      assertFalse(json.has(field), "a null " + field + " is absent on the wire");
    }
    assertNull(read.projectId());
    assertNull(read.section());
    assertNull(read.runId());
  }

  /**
   * The three facts the daily SBOM check reads (qits-668): the project a ticket is filed in, the
   * release.yml section, and the release run — bound by name from the publisher's own components.
   */
  @Test
  void aSoftwareReleaseCarryingProjectSectionAndRunBindsThemToo() throws Exception {
    UUID run = UUID.randomUUID();
    String payload =
        softwareReleasePayload(
            "maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1", "qits", "contracts", run);

    SoftwareReleaseListener.SoftwareReleasePayload read =
        CanonicalJson.payloadTo(payload, SoftwareReleaseListener.SoftwareReleasePayload.class);
    assertEquals("qits", read.projectId());
    assertEquals("contracts", read.section());
    assertEquals(run.toString(), read.runId());

    JsonNode json = MAPPER.readTree(payload);
    for (String field : List.of("projectId", "section", "runId")) {
      assertTrue(json.has(field), "the canonical payload carries no " + field);
    }
  }

  @Test
  void anScmReleaseBindsIntoTheRecordThisServiceReads() throws Exception {
    String payload = scmReleasePayload("qits-ci-service", "maintenance/dependencies", "2026.901.1");

    ScmEventListener.ScmReleasePayload read =
        CanonicalJson.payloadTo(payload, ScmEventListener.ScmReleasePayload.class);
    assertEquals("qits", read.projectId());
    assertEquals("qits-ci-service", read.repository());
    assertEquals("qits-ci-service", read.repositoryName());
    assertEquals("maintenance/dependencies", read.branch());
    assertEquals("2026.901.1", read.version());

    JsonNode json = MAPPER.readTree(payload);
    for (String field :
        List.of("projectId", "repository", "repositoryName", "branch", "version")) {
      assertTrue(json.has(field), "the canonical payload carries no " + field);
    }
  }

  @Test
  void anScmDeleteBranchBindsIntoTheRecordThisServiceReads() throws Exception {
    String payload = scmDeleteBranchPayload("qits-ci-service", "maintenance/dependencies");

    ScmEventListener.ScmDeleteBranchPayload read =
        CanonicalJson.payloadTo(payload, ScmEventListener.ScmDeleteBranchPayload.class);
    assertEquals("storage-1", read.repoId());
    assertEquals("qits", read.projectId());
    assertEquals("qits-ci-service", read.repoName());
    assertEquals("maintenance/dependencies", read.branch());
    assertEquals("deadbeef", read.sha());

    JsonNode json = MAPPER.readTree(payload);
    for (String field : List.of("repoId", "projectId", "repoName", "branch", "sha")) {
      assertTrue(json.has(field), "the canonical payload carries no " + field);
    }
  }

  /**
   * The push event is the one whose transcription is deliberately PARTIAL, so this is also the test
   * that the partiality is safe: fourteen components go onto the wire and the listener's five-field
   * record binds without complaining about the nine it does not know.
   */
  @Test
  void anScmPublishCommitBindsIntoTheFiveFieldRecordDespiteCarryingNineFieldsMore()
      throws Exception {
    String payload = scmPublishCommitPayload("qits-ci-service", "main", "abc1234");

    ScmEventListener.ScmPublishCommitPayload read =
        CanonicalJson.payloadTo(payload, ScmEventListener.ScmPublishCommitPayload.class);
    assertEquals("storage-1", read.repoId());
    assertEquals("qits", read.projectId());
    assertEquals("qits-ci-service", read.repoName());
    assertEquals("main", read.branch());
    assertEquals("abc1234", read.sha());

    JsonNode json = MAPPER.readTree(payload);
    for (String field : List.of("repoId", "projectId", "repoName", "branch", "sha")) {
      assertTrue(json.has(field), "the canonical payload carries no " + field);
    }
    // The ones this service ignores are really there — which is what makes the binding above a
    // statement about the mapper's tolerance rather than about a payload that happened to be small.
    assertTrue(json.has("parents"), "the publisher really does send the fields we ignore");
    assertTrue(json.has("message"), "the publisher really does send the fields we ignore");
  }

  /**
   * The two the payloads must NOT carry, which is why both listeners read them off the frame.
   *
   * <p>{@code eventId} and {@code occurredAt} are {@code QitsEvent}'s own accessors and the canonical
   * mix-in hides every one of them by signature — identity and time travel in the envelope. A payload
   * that started carrying them would not break a binding, but it would mean the library's wire
   * contract had changed under this service, and {@code frame.id()} — which is what
   * {@code mt_latest.source_url} records as {@code event:<id>} — would be reading the wrong one of
   * two.
   */
  @Test
  void identityAndTimeTravelInTheEnvelopeAndNeverInAPayload() throws Exception {
    List<String> payloads =
        List.of(
            softwareReleasePayload("docker", "qits/qits-ci", "2026.901.1"),
            scmReleasePayload("qits-ci-service", "maintenance/dependencies", "2026.901.1"),
            scmDeleteBranchPayload("qits-ci-service", "maintenance/dependencies"),
            scmPublishCommitPayload("qits-ci-service", "main", "abc1234"));
    for (String payload : payloads) {
      JsonNode json = MAPPER.readTree(payload);
      assertFalse(json.has("eventId"), "identity travels in the envelope, never in the payload");
      assertFalse(json.has("occurredAt"), "and so does the timestamp");
    }
  }
}

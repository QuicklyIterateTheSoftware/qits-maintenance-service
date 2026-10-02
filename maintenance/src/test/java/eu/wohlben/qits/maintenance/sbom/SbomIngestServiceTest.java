package eu.wohlben.qits.maintenance.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtArtifactComponent;
import eu.wohlben.qits.maintenance.entity.MtArtifactEdge;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerCall;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ingest, against a real PostgreSQL and a stubbed qits-artifacts.
 *
 * <p><b>The store is the real one</b> — the graph replacement is one transaction over three tables
 * with a foreign key, and a stand-in map would prove nothing about the only thing that can go wrong
 * there. What is faked is the PEER, at the seam {@code LatestResolverTest} fakes it: a {@link
 * PeerClient} subclass, at no port and with no server, so a wrong path fails here rather than in a
 * deployment.
 *
 * <p><b>The outcomes are the subject.</b> A document read into a graph; a 404 that is MISSING and
 * is never asked again; a failure that keeps its sentence; a re-ingest that REPLACES; and a
 * redelivered announcement that changes nothing.
 *
 * <p><b>Every method has a coordinate of its own.</b> This module has no {@code InventoryReset} —
 * that is the service module's — and an artifact row is keyed by {@code (ecosystem, name, version)},
 * so two methods sharing a name would share a row and the second would find the first's answer.
 */
@QuarkusTest
class SbomIngestServiceTest {

  /** qits-artifacts, at no port. Scripted by path, and an unscripted path answers 404. */
  private static final class StubArtifacts extends PeerClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    final Map<String, PeerAnswer> script = new ConcurrentHashMap<>();
    final List<String> asked = new ArrayList<>();

    @Override
    public String url(PeerTarget target, String path) {
      return "http://qits-artifacts:8080" + path;
    }

    @Override
    public PeerExchange get(PeerTarget target, String path) {
      asked.add(path);
      PeerCall call = new PeerCall("GET", url(target, path), null);
      // 404 is the ORDINARY answer from this route during the rollout, so it is also what an
      // unscripted path says here — a fixture kept to what it means to say.
      return new PeerExchange(
          call, script.getOrDefault(path, new PeerAnswer(404, "", null, Map.of(), null)));
    }

    void answer(String path, int status, String body) {
      script.put(path, new PeerAnswer(status, body, parse(body), Map.of(), null));
    }

    void unreachable(String path, String sentence) {
      script.put(path, new PeerAnswer(null, null, null, Map.of(), sentence));
    }

    private static JsonNode parse(String body) {
      try {
        return JSON.readTree(body);
      } catch (Exception notJson) {
        return null;
      }
    }
  }

  private static final String VERSION = "2026.901.1";

  private static final String DOCUMENT =
      """
      {"bomFormat":"CycloneDX","specVersion":"1.5",
       "metadata":{"component":{"bom-ref":"self","name":"the-artifact","version":"2026.901.1",
                                "purl":"pkg:maven/eu.wohlben.qits/the-artifact@2026.901.1"}},
       "components":[
         {"bom-ref":"c-databind","name":"jackson-databind","version":"2.18.2",
          "purl":"pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.18.2"},
         {"bom-ref":"c-annotations","name":"jackson-annotations","version":"2.18.2",
          "purl":"pkg:maven/com.fasterxml.jackson.core/jackson-annotations@2.18.2"}],
       "dependencies":[
         {"ref":"self","dependsOn":["c-databind"]},
         {"ref":"c-databind","dependsOn":["c-annotations"]}]}
      """;

  /** The same coordinate read again, with the transitive gone — the re-ingest fixture. */
  private static final String NARROWER =
      """
      {"bomFormat":"CycloneDX","specVersion":"1.5",
       "metadata":{"component":{"bom-ref":"self","name":"the-artifact","version":"2026.901.1",
                                "purl":"pkg:maven/eu.wohlben.qits/the-artifact@2026.901.1"}},
       "components":[
         {"bom-ref":"c-databind","name":"jackson-databind","version":"2.19.0",
          "purl":"pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.19.0"}],
       "dependencies":[{"ref":"self","dependsOn":["c-databind"]}]}
      """;

  private static final String DATABIND = "com.fasterxml.jackson.core:jackson-databind";
  private static final String ANNOTATIONS = "com.fasterxml.jackson.core:jackson-annotations";

  @Inject MaintenanceStore store;

  @Inject WorkQueue queue;

  private StubArtifacts artifacts;
  private SbomIngestService ingest;
  private String name;
  private String path;

  @BeforeEach
  void setUp() {
    artifacts = new StubArtifacts();
    SbomClient client = new SbomClient();
    client.peers = artifacts;
    ingest = new SbomIngestService();
    ingest.store = store;
    ingest.client = client;
    ingest.queue = queue;
    name = "eu.wohlben.qits:qits-sbom-" + UUID.randomUUID();
    path = "/artifacts/sboms/maven/" + name + "/-/" + VERSION;
  }

  private UUID pending() {
    return store.upsertArtifact(
        Ecosystem.MAVEN, name, VERSION, "qits-eventstream-javalib", Instant.now());
  }

  /**
   * <b>The session is CLEARED before every read, and that is the rock this suite's notes name.</b> A
   * {@code @QuarkusTest} holds ONE request context for the whole method, so every read below is
   * answered by one Hibernate session — while every store WRITE runs in {@code DbRetry.inNewTx} on a
   * session bound to its own transaction. Without the clear, a row the ingest closed in another
   * transaction looks untouched here for ever, and the assertion fails against a store that is
   * perfectly correct.
   */
  private void detached() {
    store.getEntityManager().clear();
  }

  private MtArtifact reload(UUID id) {
    detached();
    return store.artifact(id).orElseThrow();
  }

  private List<MtArtifactComponent> components(UUID id) {
    detached();
    return store.components(id);
  }

  private List<MtArtifactEdge> edges(UUID id) {
    detached();
    return store.edges(id);
  }

  private List<MtArtifact> pendingArtifacts() {
    detached();
    return store.pendingArtifacts();
  }

  // --- the document -----------------------------------------------------------------------------

  @Test
  void aDocumentIsReadIntoTheGraphAndTheRowGoesIngested() {
    artifacts.answer(path, 200, DOCUMENT);
    UUID id = pending();

    ingest.ingest(id);

    MtArtifact row = reload(id);
    assertEquals(SbomStatus.INGESTED.name(), row.sbomStatus);
    assertNull(row.sbomError);
    assertNotNull(row.ingestedAt);

    List<MtArtifactComponent> components = components(id);
    assertEquals(2, components.size());
    MtArtifactComponent databind = named(components, DATABIND);
    MtArtifactComponent annotations = named(components, ANNOTATIONS);
    assertTrue(databind.direct, "the root declares it");
    assertFalse(annotations.direct, "it arrived behind jackson-databind and no line names it");
    assertEquals("maven", databind.ecosystem);
    assertEquals("2.18.2", databind.version);

    // Two edges: root -> databind, databind -> annotations. The second is what `via` is read from.
    List<MtArtifactEdge> edges = edges(id);
    assertEquals(2, edges.size());
    assertTrue(
        edges.stream()
            .anyMatch(
                edge -> edge.parentComponentId == null && edge.childComponentId.equals(databind.id)),
        "the root's own dependency has a null parent, because the root is the artifact row");
    assertTrue(
        edges.stream()
            .anyMatch(
                edge ->
                    databind.id.equals(edge.parentComponentId)
                        && edge.childComponentId.equals(annotations.id)));
  }

  /** And the address it went to is the shipped one, the name literal and its separators intact. */
  @Test
  void theNameGoesIntoThePathLiterallyWithItsSeparators() {
    artifacts.answer(path, 200, DOCUMENT);
    ingest.ingest(pending());

    assertEquals(List.of(path), artifacts.asked);
    assertTrue(path.contains(":"), "a maven name carries a colon and it is not encoded");
  }

  // --- 404 --------------------------------------------------------------------------------------

  /**
   * THE ORDINARY STATE DURING THE ROLLOUT, and it is terminal on purpose: a released version is
   * immutable, so asking again tomorrow asks about the same bytes.
   */
  @Test
  void aFourOhFourIsMissingAndNothingAsksAgain() {
    UUID id = pending();

    ingest.ingest(id);

    assertEquals(SbomStatus.MISSING.name(), reload(id).sbomStatus);
    assertNull(reload(id).sbomError, "no document is not an error and carries no sentence");
    assertEquals(1, artifacts.asked.size());

    assertTrue(
        pendingArtifacts().stream().noneMatch(row -> row.id.equals(id)),
        "a MISSING row is not pending, so the sweep can never re-queue it");
    ingest.ingest(id);
    assertEquals(1, artifacts.asked.size(), "a row that is not PENDING is already answered");
  }

  // --- failure ----------------------------------------------------------------------------------

  @Test
  void anUnreachableArtifactsStoreIsFailedWithTheSentence() {
    artifacts.unreachable(path, "connection refused");
    UUID id = pending();

    ingest.ingest(id);

    MtArtifact row = reload(id);
    assertEquals(SbomStatus.FAILED.name(), row.sbomStatus);
    assertEquals("connection refused", row.sbomError);
  }

  @Test
  void aNonJsonAnswerIsFailedRatherThanAnEmptyGraph() {
    artifacts.answer(path, 200, "<html>a proxy error page</html>");
    UUID id = pending();

    ingest.ingest(id);

    MtArtifact row = reload(id);
    assertEquals(SbomStatus.FAILED.name(), row.sbomStatus);
    assertTrue(row.sbomError.contains("did not parse"), row.sbomError);
    assertTrue(components(id).isEmpty(), "a failure must not look like an artifact with no contents");
  }

  @Test
  void aFiveHundredIsFailedAndTheStatusIsInTheSentence() {
    artifacts.answer(path, 500, "");
    UUID id = pending();

    ingest.ingest(id);

    assertEquals(SbomStatus.FAILED.name(), reload(id).sbomStatus);
    assertTrue(reload(id).sbomError.contains("500"), reload(id).sbomError);
  }

  /** An ecosystem word this build does not know is FAILED rather than a throw on the worker. */
  @Test
  void anEcosystemThisBuildCannotAddressIsFailedRatherThanThrown() {
    UUID id = pending();
    forceEcosystem(id, "cargo");

    ingest.ingest(id);

    assertEquals(SbomStatus.FAILED.name(), reload(id).sbomStatus);
    assertTrue(reload(id).sbomError.contains("cargo"), reload(id).sbomError);
    assertTrue(artifacts.asked.isEmpty(), "nothing is asked about a name that cannot be addressed");
  }

  // --- the daemon binary (qits-703) ------------------------------------------------------------

  /**
   * <b>A DAEMON RELEASE IS READ LIKE ANY OTHER.</b> The announcement writes the row PENDING and
   * queues the fetch; the fetch goes to the {@code daemon} segment of the SBOM route — the row's own
   * stored word, not an {@code Ecosystem}, which {@code daemon} is not — and the graph lands.
   */
  @Test
  void aDaemonReleaseIsPendingThenIngestedWithItsComponents() {
    String daemon = "qits-daemon-" + UUID.randomUUID();
    String daemonPath = "/artifacts/sboms/daemon/" + daemon + "/-/" + VERSION;
    artifacts.answer(daemonPath, 200, DOCUMENT);
    // The worker must not race the PENDING assertion: hold the queue behind a latch.
    java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
    queue.submit(
        "hold the queue",
        () -> {
          try {
            hold.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    UUID id;
    try {
      id = ingest.announcedDaemon(daemon, VERSION, "qits-platform-access-cli", Instant.now());

      MtArtifact pending = reload(id);
      assertEquals(Ecosystem.DAEMON_WIRE_NAME, pending.ecosystem);
      assertEquals(SbomStatus.PENDING.name(), pending.sbomStatus);
    } finally {
      hold.countDown();
    }
    assertTrue(queue.awaitIdle(Duration.ofSeconds(30)), "the fetch runs on the worker thread");

    assertEquals(List.of(daemonPath), artifacts.asked);
    MtArtifact row = reload(id);
    assertEquals(SbomStatus.INGESTED.name(), row.sbomStatus);
    assertNull(row.sbomError);
    List<MtArtifactComponent> components = components(id);
    assertEquals(2, components.size());
    assertTrue(named(components, DATABIND).direct);
    assertEquals(2, edges(id).size());
  }

  /** And a daemon with no document is MISSING — the ordinary answer, the same as any other type. */
  @Test
  void aDaemonWithNoDocumentIsMissing() {
    String daemon = "qits-daemon-" + UUID.randomUUID();
    UUID id = store.upsertDaemonArtifact(daemon, VERSION, "qits-cli", Instant.now());

    ingest.ingest(id);

    assertEquals(
        List.of("/artifacts/sboms/daemon/" + daemon + "/-/" + VERSION), artifacts.asked);
    assertEquals(SbomStatus.MISSING.name(), reload(id).sbomStatus);
    assertNull(reload(id).sbomError);
  }

  // --- re-ingest --------------------------------------------------------------------------------

  /**
   * A re-ingest REPLACES the graph rather than merging into it. A document is one reading of one
   * immutable release; a merge would leave behind components from a parse this build has since
   * corrected, and nothing would ever say which reading a row came from.
   */
  @Test
  void aReIngestReplacesTheGraphWholesaleRatherThanMerging() {
    artifacts.answer(path, 200, DOCUMENT);
    UUID id = pending();
    ingest.ingest(id);
    assertEquals(2, components(id).size());

    artifacts.answer(path, 200, NARROWER);
    UUID again = ingest.requeue(Ecosystem.MAVEN, name, VERSION, null, Instant.now());
    assertEquals(id, again, "the coordinate is the identity — a re-ingest is not a second row");
    assertTrue(queue.awaitIdle(Duration.ofSeconds(30)), "the re-ingest runs on the worker thread");

    List<MtArtifactComponent> components = components(id);
    assertEquals(1, components.size(), "the transitive that is gone must not survive the replace");
    assertEquals("2.19.0", components.get(0).version);
    assertEquals(1, edges(id).size());
    assertEquals(SbomStatus.INGESTED.name(), reload(id).sbomStatus);
  }

  /** And a re-queue is the only thing that moves a terminal row, because nothing else does. */
  @Test
  void aRequeueIsTheOnlyThingThatMovesAMissingRow() {
    UUID id = pending();
    ingest.ingest(id);
    assertEquals(SbomStatus.MISSING.name(), reload(id).sbomStatus);

    store.requeueArtifact(Ecosystem.MAVEN, name, VERSION, null, Instant.now());

    assertEquals(SbomStatus.PENDING.name(), reload(id).sbomStatus);
    assertTrue(pendingArtifacts().stream().anyMatch(row -> row.id.equals(id)));
  }

  // --- the outbox -------------------------------------------------------------------------------

  /**
   * A second announcement of one release leaves the answered row exactly as it was — status, error
   * and graph. That is what makes a redelivered frame a read and a return, by construction rather
   * than by a flag somebody remembered to check.
   */
  @Test
  void aSecondAnnouncementOfOneReleaseLeavesAnAnsweredRowAlone() {
    artifacts.answer(path, 200, DOCUMENT);
    UUID id = pending();
    ingest.ingest(id);
    assertEquals(SbomStatus.INGESTED.name(), reload(id).sbomStatus);

    UUID second =
        store.upsertArtifact(Ecosystem.MAVEN, name, VERSION, "somewhere-else", Instant.now());

    assertEquals(id, second, "one row per released version");
    MtArtifact row = reload(id);
    assertEquals(SbomStatus.INGESTED.name(), row.sbomStatus);
    assertEquals(
        "qits-eventstream-javalib",
        row.repository,
        "a redelivery is not new evidence about a release that is already recorded");
    assertEquals(2, components(id).size(), "and the graph is not touched either");
  }

  /** The sweep is a re-queue of what is PENDING and of nothing else. */
  @Test
  void theSweepQueuesEveryPendingRowAndLeavesTheTerminalOnesAlone() {
    artifacts.answer(path, 200, DOCUMENT);
    UUID id = pending();

    ingest.sweep();
    assertTrue(queue.awaitIdle(Duration.ofSeconds(30)));

    assertEquals(SbomStatus.INGESTED.name(), reload(id).sbomStatus);
    assertTrue(pendingArtifacts().stream().noneMatch(row -> row.id.equals(id)));
  }

  /** Writes a word no enum constant carries, the way a row from a newer build would arrive. */
  private void forceEcosystem(UUID id, String word) {
    DbRetry.runInNewTx(
        "force an unknown ecosystem",
        () -> MtArtifact.update("ecosystem = ?1 where id = ?2", word, id));
  }

  private static MtArtifactComponent named(List<MtArtifactComponent> components, String name) {
    return components.stream()
        .filter(component -> name.equals(component.name))
        .findFirst()
        .orElseThrow(() -> new AssertionError(name + " is not among the " + components.size()));
  }
}

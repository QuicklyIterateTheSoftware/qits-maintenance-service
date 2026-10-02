package eu.wohlben.qits.maintenance.sbomcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.catalog.CatalogReader;
import eu.wohlben.qits.maintenance.dto.SbomCheckReportDto;
import eu.wohlben.qits.maintenance.entity.MtSbomTicket;
import eu.wohlben.qits.maintenance.error.NoSbomCheckRunException;
import eu.wohlben.qits.maintenance.error.SbomCheckFailedException;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.ReleaseOrigin;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The daily SBOM check against a real PostgreSQL, with qits-artifacts' presence probe and
 * qits-projects' ticket doors faked at the client.
 *
 * <p><b>The check reads EVERY row</b> — no cut-off — so each test starts from an empty artifact
 * table and an empty ticket table rather than from unique names, which is what the rest of this
 * module's suite does. Nothing else in the module reads across rows the way this does.
 */
@QuarkusTest
class SbomCheckServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-02T02:15:00Z");
  private static final String PROJECT = "qits";
  private static final UUID RUN = UUID.fromString("11111111-2222-4333-8444-555555555555");

  /** qits-artifacts' listings, by "type name"; an unscripted artifact holds every version asked. */
  static final class FakePresence extends ArtifactPresence {
    final Map<String, Set<String>> held = new HashMap<>();
    final Set<String> failing = new HashSet<>();
    final List<String> asked = new ArrayList<>();

    @Override
    public Set<String> versions(String type, String name) {
      asked.add(type + " " + name);
      if (failing.contains(type + " " + name)) {
        throw new SbomCheckFailedException("HTTP 503 from qits-artifacts for " + name);
      }
      return held.getOrDefault(type + " " + name, Set.of());
    }

    void holds(String type, String name, String... versions) {
      held.put(type + " " + name, Set.of(versions));
    }
  }

  /** qits-projects' entity doors, recorded. A filed ticket starts REPORTED and MAINTENANCE. */
  static final class FakeTickets extends TicketClient {
    final List<String> calls = new ArrayList<>();
    final Map<UUID, TicketState> states = new HashMap<>();
    final Map<UUID, List<String>> comments = new HashMap<>();
    final List<String> descriptions = new ArrayList<>();
    int filed;

    @Override
    public Filed file(String projectId, String title, String impetus, String description) {
      UUID id = UUID.randomUUID();
      filed++;
      calls.add("file " + projectId + " " + title);
      descriptions.add(description);
      states.put(id, new TicketState("REPORTED", "MAINTENANCE"));
      return new Filed(id, "qits-" + (900 + filed));
    }

    @Override
    public Optional<TicketState> read(UUID ticketId) {
      calls.add("read " + ticketId);
      return Optional.ofNullable(states.get(ticketId));
    }

    @Override
    public void comment(UUID ticketId, String text) {
      calls.add("comment " + ticketId);
      comments.computeIfAbsent(ticketId, k -> new ArrayList<>()).add(text);
    }

    @Override
    public void drop(UUID ticketId) {
      calls.add("drop " + ticketId);
      TicketState state = states.get(ticketId);
      states.put(ticketId, new TicketState("DROPPED", state == null ? null : state.ticketType()));
    }

    long count(String verb) {
      return calls.stream().filter(call -> call.startsWith(verb + " ")).count();
    }
  }

  /** qits-projects' catalog listing, scripted; counts its reads. */
  static final class FakeCatalog extends CatalogReader {
    final List<CatalogEntry> entries = new ArrayList<>();
    String error;
    int reads;

    @Override
    public Result read() {
      reads++;
      return new Result(List.copyOf(entries), error);
    }

    void lists(String project, String name, String catalogId) {
      entries.add(new CatalogEntry(project, name, "main", catalogId, "LIBRARY"));
    }
  }

  @Inject MaintenanceStore store;

  @Inject ObjectMapper json;

  private FakePresence presence;
  private FakeTickets tickets;
  private FakeCatalog catalog;
  private SbomCheckService check;

  @BeforeEach
  void setUp() {
    DbRetry.runInNewTx(
        "empty the sbom check's tables",
        () -> {
          var em = store.getEntityManager();
          for (String table :
              List.of(
                  "mt_sbom_ticket_version",
                  "mt_sbom_ticket",
                  "mt_sbom_check_run",
                  "mt_artifact_edge",
                  "mt_artifact_component",
                  "mt_artifact")) {
            em.createNativeQuery("delete from " + table).executeUpdate();
          }
        });
    presence = new FakePresence();
    tickets = new FakeTickets();
    check = new SbomCheckService();
    check.store = store;
    check.presence = presence;
    check.tickets = tickets;
    catalog = new FakeCatalog();
    check.catalog = catalog;
    check.json = json;
    check.pendingGrace = Duration.ofHours(24);
    check.fileTickets = false;
  }

  private void detached() {
    store.getEntityManager().clear();
  }

  /** A released maven row, PENDING as the listener writes it, held by the store by default. */
  private UUID released(String name, String version, Duration ago, ReleaseOrigin origin) {
    presence.held.merge(
        "maven " + name,
        Set.of(version),
        (a, b) -> {
          Set<String> both = new HashSet<>(a);
          both.addAll(b);
          return both;
        });
    return store.upsertArtifact(
        Ecosystem.MAVEN, name, version, "qits-lib", NOW.minus(ago), origin);
  }

  private UUID missing(String name, String version) {
    UUID id = released(name, version, Duration.ofDays(3), origin("artifacts"));
    store.markArtifactMissing(id);
    return id;
  }

  private static ReleaseOrigin origin(String section) {
    return new ReleaseOrigin(PROJECT, section, RUN);
  }

  private void ingested(UUID id) {
    store.replaceGraph(id, List.of(), List.of(), NOW);
  }

  private static List<String> versions(SbomCheckReportDto report) {
    return report.entries().stream().map(e -> e.name() + "@" + e.version()).sorted().toList();
  }

  // --- selection ------------------------------------------------------------------------------

  /**
   * THE SELECTION MATRIX. Counted: MISSING, FAILED, PENDING past its grace, a row from before
   * sections, a daemon. Not counted: a contract, a collected version, PENDING inside its grace, an
   * INGESTED row. A row with no project is a warning and nothing else.
   */
  @Test
  void theSelectionCountsExactlyTheRowsWithoutAUsableSbomStillInTheStore() {
    missing("g:missing", "1");
    UUID failed = released("g:failed", "1", Duration.ofDays(3), origin("artifacts"));
    store.markArtifactFailed(failed, "the document did not parse as json");
    released("g:pending-23h", "1", Duration.ofHours(23), origin("artifacts"));
    released("g:pending-25h", "1", Duration.ofHours(25), origin("artifacts"));
    UUID contract = released("g:contract", "1", Duration.ofDays(3), origin("contracts"));
    store.markArtifactMissing(contract);
    UUID collected = missing("g:collected", "1");
    presence.holds("maven", "g:collected", "2");
    UUID unsectioned = released("g:unsectioned", "1", Duration.ofDays(3), origin(null));
    store.markArtifactMissing(unsectioned);
    ingested(released("g:ingested", "1", Duration.ofDays(3), origin("artifacts")));
    UUID orphan =
        released("g:orphan", "1", Duration.ofDays(3), new ReleaseOrigin(null, "artifacts", RUN));
    store.markArtifactMissing(orphan);
    UUID daemon =
        store.upsertDaemonArtifact(
            "qits-cli", "1", "qits-cli", NOW.minus(Duration.ofDays(3)), origin("artifacts"));
    store.markArtifactMissing(daemon);
    presence.holds("daemon", "qits-cli", "1");
    assertNotNull(collected);

    SbomCheckReportDto report = check.run(NOW);

    assertEquals(
        List.of("g:failed@1", "g:missing@1", "g:pending-25h@1", "g:unsectioned@1", "qits-cli@1"),
        versions(report));
    assertEquals(
        "FAILED",
        report.entries().stream().filter(e -> e.name().equals("g:failed")).findFirst().orElseThrow().reason());
    assertEquals(
        "PENDING",
        report.entries().stream()
            .filter(e -> e.name().equals("g:pending-25h"))
            .findFirst()
            .orElseThrow()
            .reason());
    assertEquals(1, report.warnings().size(), report.warnings().toString());
    assertTrue(report.warnings().get(0).contains("g:orphan"), report.warnings().toString());
    assertTrue(report.entries().stream().allMatch(e -> PROJECT.equals(e.project())));
    assertEquals(false, report.filed());

    // REPORT-ONLY: not one call into qits-projects.
    assertTrue(tickets.calls.isEmpty(), tickets.calls.toString());

    // And the report is what the door serves from now on.
    detached();
    assertEquals(report, check.lastReport());
  }

  /** "Could not ask" is never read as "collected" or as "still there": the run fails, nothing is stored. */
  @Test
  void aProbeThatIsNotAListingOrAFourOhFourFailsTheRunAndStoresNothing() {
    missing("g:missing", "1");
    presence.failing.add("maven g:missing");
    check.fileTickets = true;

    assertThrows(SbomCheckFailedException.class, () -> check.run(NOW));

    assertTrue(tickets.calls.isEmpty());
    detached();
    assertThrows(NoSbomCheckRunException.class, () -> check.lastReport());
  }

  /** Report-only with a ticketable finding still files nothing, not even a read. */
  @Test
  void reportOnlyCallsNothingInQitsProjectsEvenWithOpenTicketRows() {
    missing("g:a", "1");
    store.openSbomTicket(PROJECT, "maven", "g:b", UUID.randomUUID(), "qits-1", NOW);

    SbomCheckReportDto report = check.run(NOW);

    assertEquals(List.of("g:a@1"), versions(report));
    assertTrue(tickets.calls.isEmpty(), tickets.calls.toString());
  }

  /** A row with no project is never ticketed, with filing on. */
  @Test
  void aRowWithNoProjectIsAWarningAndNeverATicket() {
    UUID orphan =
        released("g:orphan", "1", Duration.ofDays(3), new ReleaseOrigin(null, null, null));
    store.markArtifactMissing(orphan);
    check.fileTickets = true;

    SbomCheckReportDto report = check.run(NOW);

    assertTrue(report.entries().isEmpty());
    assertEquals(1, report.warnings().size());
    assertEquals(0, tickets.count("file"));
  }

  // --- a pre-change row's project, resolved from its repository ---------------------------------

  /** A row from before the listener stored a project, released from a mt_artifact.repository name. */
  private UUID preChange(String name, String repository) {
    presence.held.put("maven " + name, Set.of("1"));
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN, name, "1", repository, NOW.minus(Duration.ofDays(30)), ReleaseOrigin.NONE);
    store.markArtifactMissing(id);
    return id;
  }

  /**
   * THE OLD PINNED VERSIONS ARE THE POINT: a counted row with no project is resolved through the
   * catalog by an exact, unique repository match — in report-only mode too, since it is a read —
   * and the project is written back so the next run does not ask again.
   */
  @Test
  void aRowWithNoProjectIsResolvedFromItsRepositoryAndPersisted() {
    catalog.lists("proj-x", "qits-old-lib", "11111111-0000-4000-8000-000000000001");
    catalog.lists("proj-y", "qits-other", null);
    UUID byName = preChange("g:by-name", "qits-old-lib");
    UUID byId = preChange("g:by-id", "11111111-0000-4000-8000-000000000001");

    SbomCheckReportDto report = check.run(NOW);

    assertEquals(List.of("g:by-id@1", "g:by-name@1"), versions(report));
    assertTrue(report.entries().stream().allMatch(e -> "proj-x".equals(e.project())));
    assertTrue(report.warnings().isEmpty(), report.warnings().toString());
    assertTrue(tickets.calls.isEmpty(), "report-only still calls nothing in the ticket doors");
    assertEquals(1, catalog.reads, "one catalog read per run, however many rows need it");
    detached();
    assertEquals("proj-x", store.artifact(byName).orElseThrow().projectId);
    assertEquals("proj-x", store.artifact(byId).orElseThrow().projectId);

    check.run(NOW.plusSeconds(60));
    assertEquals(1, catalog.reads, "resolved once: the next run reads the row, not the catalog");
  }

  /** Ambiguous, unknown, or a catalog that cannot be read: a warning, never a ticket. */
  @Test
  void anAmbiguousOrUnknownRepositoryStaysAWarningAndIsNeverTicketed() {
    check.fileTickets = true;
    catalog.lists("proj-x", "qits-twice", null);
    catalog.lists("proj-y", "qits-twice", null);
    UUID ambiguous = preChange("g:ambiguous", "qits-twice");
    preChange("g:unknown", "qits-nobody-lists");

    SbomCheckReportDto report = check.run(NOW);

    assertTrue(report.entries().isEmpty(), report.entries().toString());
    assertEquals(2, report.warnings().size(), report.warnings().toString());
    assertTrue(report.warnings().stream().anyMatch(w -> w.contains("ambiguous")));
    assertTrue(report.warnings().stream().anyMatch(w -> w.contains("not in the catalog")));
    assertEquals(0, tickets.count("file"));
    detached();
    assertEquals(null, store.artifact(ambiguous).orElseThrow().projectId);

    catalog.entries.clear();
    catalog.lists("proj-x", "qits-twice", null);
    catalog.error = "the catalog could not be read: HTTP 503";
    SbomCheckReportDto failed = check.run(NOW.plusSeconds(60));
    assertTrue(failed.entries().isEmpty());
    assertTrue(failed.warnings().stream().allMatch(w -> w.contains("HTTP 503")), failed.warnings().toString());
    assertEquals(0, tickets.count("file"));
  }

  // --- filing and dedup -----------------------------------------------------------------------

  /**
   * One ticket per (project, ecosystem, name): the oldest version is the description, the others
   * comments; a later run comments the NEW version only; a version is never reported twice.
   */
  @Test
  void anOpenTicketTakesNewVersionsAsCommentsAndNoVersionIsReportedTwice() {
    check.fileTickets = true;
    missing("g:a", "1");
    UUID second = released("g:a", "2", Duration.ofDays(2), origin("artifacts"));
    store.markArtifactMissing(second);

    SbomCheckReportDto first = check.run(NOW);

    assertEquals(1, tickets.count("file"));
    assertEquals(1, tickets.count("comment"), "the second version rides as a comment");
    assertTrue(tickets.descriptions.get(0).contains("- **Version:** `1`"));
    assertTrue(tickets.descriptions.get(0).contains("MISSING — qits-artifacts answers 404"));
    assertTrue(tickets.descriptions.get(0).contains("`" + RUN + "`"));
    assertEquals(1, first.tickets().size());
    assertEquals(List.of("1", "2"), first.tickets().get(0).versions().stream().sorted().toList());

    // Nothing new: nothing asked, nothing said.
    tickets.calls.clear();
    check.run(NOW.plusSeconds(86_400));
    assertTrue(tickets.calls.isEmpty(), tickets.calls.toString());

    // A third version: the ticket is read, found open, and commented — once.
    UUID third = released("g:a", "3", Duration.ofDays(1), origin("artifacts"));
    store.markArtifactFailed(third, "not CycloneDX");
    check.run(NOW.plusSeconds(2 * 86_400));
    assertEquals(0, tickets.count("file"));
    assertEquals(1, tickets.count("read"));
    assertEquals(1, tickets.count("comment"));
    List<String> thread = tickets.comments.values().iterator().next();
    assertTrue(thread.get(thread.size() - 1).contains("`3`"), thread.toString());
    assertTrue(thread.get(thread.size() - 1).contains("FAILED: not CycloneDX"), thread.toString());

    tickets.calls.clear();
    check.run(NOW.plusSeconds(3 * 86_400));
    assertTrue(tickets.calls.isEmpty(), "and is never reported twice: " + tickets.calls);
  }

  /** A ticket somebody closed (or deleted) is replaced by a new one, and the row with it. */
  @Test
  void aClosedTicketIsReplacedByANewOneAndTheRowWithIt() {
    check.fileTickets = true;
    missing("g:a", "1");
    check.run(NOW);
    detached();
    MtSbomTicket before = store.sbomTicket(PROJECT, "maven", "g:a").orElseThrow();
    tickets.states.put(before.ticketId, new TicketClient.TicketState("DONE", "MAINTENANCE"));

    UUID later = released("g:a", "2", Duration.ofDays(1), origin("artifacts"));
    store.markArtifactMissing(later);
    tickets.calls.clear();
    check.run(NOW.plusSeconds(86_400));

    assertEquals(1, tickets.count("file"), tickets.calls.toString());
    detached();
    MtSbomTicket after = store.sbomTicket(PROJECT, "maven", "g:a").orElseThrow();
    assertEquals(before.id, after.id, "the row is replaced in place — the key names one ticket");
    assertNotEquals(before.ticketId, after.ticketId);
    assertEquals(null, after.closedAt);
    assertEquals(
        List.of("1", "2"),
        store.sbomTicketVersions(after.id).stream().map(v -> v.version).sorted().toList(),
        "the new ticket carries every counted version, each once");
  }

  // --- closing --------------------------------------------------------------------------------

  /** Every listed version INGESTED or collected: dropped, told why, closed here. */
  @Test
  void aTicketWhoseVersionsAreAllIngestedOrCollectedIsDroppedWithAComment() {
    check.fileTickets = true;
    UUID one = missing("g:a", "1");
    missing("g:a", "2");
    check.run(NOW);
    detached();
    MtSbomTicket ticket = store.sbomTicket(PROJECT, "maven", "g:a").orElseThrow();

    ingested(one);
    presence.holds("maven", "g:a", "1"); // 2 was collected
    tickets.calls.clear();
    SbomCheckReportDto report = check.run(NOW.plusSeconds(86_400));

    assertEquals(1, tickets.count("drop"), tickets.calls.toString());
    assertEquals("DROPPED", tickets.states.get(ticket.ticketId).status());
    List<String> thread = tickets.comments.get(ticket.ticketId);
    String closing = thread.get(thread.size() - 1);
    assertTrue(closing.startsWith("Resolved: every version listed here"), closing);
    assertTrue(closing.contains("1: INGESTED"), closing);
    assertTrue(closing.contains("2: collected"), closing);
    assertTrue(report.tickets().isEmpty());
    detached();
    assertNotNull(store.sbomTicket(PROJECT, "maven", "g:a").orElseThrow().closedAt);
  }

  /** A retyped ticket is a person's now: told, never dropped, and never commented on again. */
  @Test
  void aRetypedTicketIsOnlyToldAndNeverCommentedOnAgain() {
    check.fileTickets = true;
    UUID one = missing("g:a", "1");
    check.run(NOW);
    detached();
    MtSbomTicket ticket = store.sbomTicket(PROJECT, "maven", "g:a").orElseThrow();
    tickets.states.put(ticket.ticketId, new TicketClient.TicketState("REFINED", "BUG"));

    ingested(one);
    tickets.calls.clear();
    check.run(NOW.plusSeconds(86_400));

    assertEquals(0, tickets.count("drop"));
    assertEquals(1, tickets.count("comment"));
    assertTrue(
        tickets.comments.get(ticket.ticketId).get(0).contains("no longer MAINTENANCE"),
        tickets.comments.toString());
    detached();
    assertNotNull(store.sbomTicket(PROJECT, "maven", "g:a").orElseThrow().closedAt);

    tickets.calls.clear();
    check.run(NOW.plusSeconds(2 * 86_400));
    assertTrue(tickets.calls.isEmpty(), tickets.calls.toString());
  }

  /** A version neither counted nor resolved (re-queued, inside its grace) keeps the ticket open. */
  @Test
  void aTicketWithAVersionStillInFlightStaysOpen() {
    check.fileTickets = true;
    // Released two hours ago, so a re-queue puts it back INSIDE the pending grace.
    store.markArtifactMissing(released("g:a", "1", Duration.ofHours(2), origin("artifacts")));
    check.run(NOW);
    assertEquals(1, tickets.count("file"));
    store.requeueArtifact(Ecosystem.MAVEN, "g:a", "1", null, NOW);
    tickets.calls.clear();

    check.run(NOW.plusSeconds(3_600));

    assertEquals(0, tickets.count("drop"));
    assertEquals(0, tickets.count("comment"));
  }
}

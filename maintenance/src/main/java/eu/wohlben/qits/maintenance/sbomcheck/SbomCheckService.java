package eu.wohlben.qits.maintenance.sbomcheck;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.catalog.CatalogReader;
import eu.wohlben.qits.maintenance.dto.SbomCheckReportDto;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtSbomTicket;
import eu.wohlben.qits.maintenance.entity.MtSbomTicketVersion;
import eu.wohlben.qits.maintenance.error.NoSbomCheckRunException;
import eu.wohlben.qits.maintenance.error.SbomCheckFailedException;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.SbomClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The daily SBOM check</b> (qits-621 / qits-668): every released software artifact still in
 * qits-artifacts that has no usable bill of materials, reported — and, once {@code
 * qits.maintenance.sbom.check.file-tickets} is on, ticketed in its project and closed again when it
 * stops being true.
 *
 * <h2>What counts</h2>
 *
 * <p>An {@code mt_artifact} row, with NO cut-off on age, when all of these hold:
 *
 * <ul>
 *   <li>its type is one the SBOM route keys ({@link SbomClient#TYPES}: maven, npm, docker, daemon);
 *   <li>its release.yml section is {@code artifacts}, or unrecorded (a release from before qits-ci
 *       carried the section) — a {@code contracts} entry is never counted;
 *   <li>its SBOM is MISSING or FAILED, or PENDING for longer than {@code pending-grace} after the
 *       release (a fetch that is merely queued is not a finding);
 *   <li>the version is still in qits-artifacts ({@link ArtifactPresence}): one the GC has collected
 *       is nobody's problem any more. A probe that cannot answer FAILS THE RUN — see {@link
 *       SbomCheckFailedException};
 *   <li>it names a project — or, for a row written before the listener stored one, its repository
 *       resolves to exactly one project in qits-projects' catalog, which is then written back onto
 *       the row (see {@code ProjectResolver}). A row that resolves to none is a WARNING in the
 *       report and never a ticket: filing into a guessed project would put a finding where nobody
 *       responsible reads it.
 * </ul>
 *
 * <h2>A run</h2>
 *
 * <ol>
 *   <li>Every presence probe the run will need is made FIRST, before anything is written, so a
 *       failing probe leaves no half-filed run behind.
 *   <li>The report is computed and grouped by {@code (project, ecosystem, name)}.
 *   <li>Report-only ({@code file-tickets=false}, as shipped): the report is stored and NOTHING in
 *       qits-projects is called — not a read, not a write.
 *   <li>Otherwise each group is filed (below), and then every open ticket none of whose versions is
 *       counted any more — each INGESTED, or gone from the store — is closed.
 *   <li>The report is stored in {@code mt_sbom_check_run} either way.
 * </ol>
 *
 * <h2>Filing: one ticket per (project, ecosystem, name), a version reported once</h2>
 *
 * <p>The version is not part of the key — {@code TicketUnattendedGateTickets} in qits-projects is the
 * precedent: anything still open takes a COMMENT, anything closed or gone gets a fresh ticket.
 *
 * <ol>
 *   <li>A version already recorded against the group's ticket row is never reported again.
 *   <li>An open row whose ticket is still open in qits-projects (not DONE, not DROPPED) gets one
 *       comment per new version.
 *   <li>Otherwise — no row, a row this check closed, or a ticket DONE, DROPPED or deleted — a new
 *       ticket is filed and the row REPLACED. The new ticket carries every counted version of the
 *       group, not only the new ones: the replaced row's versions were reported on a ticket that is
 *       not this one, and are deleted with it. The oldest version is the description; each further
 *       one is a comment.
 * </ol>
 *
 * <h2>Closing</h2>
 *
 * <p>Per open row with nothing counted left: a ticket still MAINTENANCE and open is moved to
 * DROPPED and then told why (the move first, as the precedent does it — a failed move writes
 * nothing, so the next run cannot repeat a sentence already on the thread); a ticket a person
 * RETYPED is told and left open in qits-projects, but closed in this table so it is never commented
 * on twice — whoever retyped it has taken it over; a ticket already closed or gone is just closed
 * here. In all three the row's {@code closed_at} is set.
 *
 * <p><b>A ticket call that fails costs its own group, not the run</b>: the sentence goes into the
 * report's warnings and the next run tries again from what was recorded.
 *
 * <p><b>One run at a time</b>, the schedule's and the door's alike, behind one lock: two runs
 * filing the same group at once would be two tickets.
 */
@ApplicationScoped
public class SbomCheckService {

  private static final Logger LOG = Logger.getLogger(SbomCheckService.class);

  /** Why a version counts. */
  public enum Reason {
    MISSING,
    FAILED,
    PENDING
  }

  @Inject MaintenanceStore store;

  @Inject ArtifactPresence presence;

  @Inject TicketClient tickets;

  @Inject CatalogReader catalog;

  @Inject ObjectMapper json;

  @ConfigProperty(name = "qits.maintenance.sbom.check.pending-grace")
  Duration pendingGrace;

  @ConfigProperty(name = "qits.maintenance.sbom.check.file-tickets")
  boolean fileTickets;

  private final ReentrantLock running = new ReentrantLock();

  /** One counted row and why it counts. */
  record Counted(MtArtifact row, Reason reason) {}

  /** The newest stored report. */
  public SbomCheckReportDto lastReport() {
    return store
        .latestSbomCheckRun()
        .map(
            run -> {
              try {
                return json.readValue(run.report, SbomCheckReportDto.class);
              } catch (JsonProcessingException e) {
                throw new IllegalStateException("the stored sbom check report does not read", e);
              }
            })
        .orElseThrow(NoSbomCheckRunException::new);
  }

  /**
   * One run, start to finish.
   *
   * @throws SbomCheckFailedException when qits-artifacts could not be asked what it holds; nothing
   *     is filed and no report is stored
   */
  public SbomCheckReportDto run(Instant now) {
    running.lock();
    try {
      return runLocked(now);
    } finally {
      running.unlock();
    }
  }

  private SbomCheckReportDto runLocked(Instant now) {
    List<MtArtifact> candidates = store.sbomCheckCandidates(SbomClient.TYPES);
    List<MtSbomTicket> openBefore = fileTickets ? store.openSbomTickets() : List.of();

    // EVERY probe first, before anything is written: a failure throws out of here with nothing
    // filed and nothing stored.
    Map<String, Set<String>> present = new HashMap<>();
    for (MtArtifact row : candidates) {
      if (reason(row, now) != null) {
        probe(present, row.ecosystem, row.name);
      }
    }
    for (MtSbomTicket ticket : openBefore) {
      probe(present, ticket.ecosystem, ticket.name);
    }

    List<Counted> counted = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    ProjectResolver projects = new ProjectResolver();
    for (MtArtifact row : candidates) {
      Reason reason = reason(row, now);
      if (reason == null || !present.get(key(row.ecosystem, row.name)).contains(row.version)) {
        continue;
      }
      if (row.projectId == null || row.projectId.isBlank()) {
        // A row from before the listener stored the frame's project: resolved ONCE from its
        // repository through the catalog, and written back. Report-only too — it is a read.
        String resolved = projects.resolve(row.repository);
        if (resolved == null) {
          warnings.add(
              "WARN "
                  + row.ecosystem
                  + " "
                  + row.name
                  + " "
                  + row.version
                  + " ("
                  + reason
                  + ") names no project and its repository '"
                  + row.repository
                  + "' "
                  + projects.why(row.repository)
                  + ", so no ticket is filed for it");
          continue;
        }
        store.setArtifactProject(row.id, resolved);
        row.projectId = resolved;
      }
      counted.add(new Counted(row, reason));
    }

    Map<String, List<Counted>> groups = new LinkedHashMap<>();
    for (Counted c : counted) {
      groups
          .computeIfAbsent(group(c.row().projectId, c.row().ecosystem, c.row().name), k -> new ArrayList<>())
          .add(c);
    }

    if (fileTickets) {
      for (List<Counted> group : groups.values()) {
        try {
          file(group, now);
        } catch (RuntimeException e) {
          MtArtifact first = group.get(0).row();
          warnings.add(
              "could not ticket "
                  + first.ecosystem
                  + " "
                  + first.name
                  + " in project "
                  + first.projectId
                  + ": "
                  + e.getMessage());
          LOG.warnf(e, "The sbom check could not ticket %s %s", first.ecosystem, first.name);
        }
      }
      for (MtSbomTicket ticket : openBefore) {
        if (groups.containsKey(group(ticket.projectId, ticket.ecosystem, ticket.name))) {
          continue;
        }
        try {
          closeIfResolved(ticket, present, now);
        } catch (RuntimeException e) {
          warnings.add(
              "could not close the ticket "
                  + (ticket.ticketSlug == null ? ticket.ticketId : ticket.ticketSlug)
                  + " of "
                  + ticket.ecosystem
                  + " "
                  + ticket.name
                  + ": "
                  + e.getMessage());
          LOG.warnf(e, "The sbom check could not close ticket %s", ticket.ticketId);
        }
      }
    }

    SbomCheckReportDto report =
        new SbomCheckReportDto(
            now, fileTickets, entries(counted), List.copyOf(warnings), openTickets());
    try {
      store.recordSbomCheckRun(UUID.randomUUID(), now, fileTickets, json.writeValueAsString(report));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("the sbom check report does not serialize", e);
    }
    LOG.infof(
        "SBOM check: %d version(s) without a usable SBOM across %d artifact(s), %d warning(s)%s",
        counted.size(),
        groups.size(),
        warnings.size(),
        fileTickets ? "" : " — report-only, nothing filed");
    return report;
  }

  /**
   * <b>A PRE-CHANGE ROW'S PROJECT, from its repository</b> — through qits-projects' catalog listing
   * ({@code GET /projects/api/repositories}, {@link CatalogReader}, which admits {@code qits:system}),
   * read at most once per run and only when a counted row needs it.
   *
   * <p>Every row released before V14 carries no project, and after the GC those are exactly the rows
   * this check exists for — so a warning for each would leave the check unable to file anything at
   * all. The match must be EXACT and UNIQUE: the repository's catalog name, or its catalog row id
   * (which {@code mt_artifact.repository} still holds on rows written before V5's translation, and
   * which is unique by construction). A name two projects both carry is ambiguous, a name the
   * catalog does not list is unknown, and a catalog that could not be read resolves nothing — each
   * stays a warning, never a guessed project.
   */
  private final class ProjectResolver {
    private Map<String, Set<String>> projectsByName;
    private Map<String, String> projectById;
    private String failure;

    String resolve(String repository) {
      if (repository == null || repository.isBlank()) {
        return null;
      }
      load();
      if (failure != null) {
        return null;
      }
      String byId = projectById.get(repository);
      if (byId != null) {
        return byId;
      }
      Set<String> candidates = projectsByName.getOrDefault(repository, Set.of());
      return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    String why(String repository) {
      if (repository == null || repository.isBlank()) {
        return "is not recorded";
      }
      if (failure != null) {
        return "could not be resolved (" + failure + ")";
      }
      int matches = projectsByName.getOrDefault(repository, Set.of()).size();
      return matches > 1
          ? "is ambiguous in the catalog (" + matches + " projects carry that name)"
          : "is not in the catalog";
    }

    private void load() {
      if (projectsByName != null || failure != null) {
        return;
      }
      CatalogReader.Result result;
      try {
        result = catalog.read();
      } catch (RuntimeException e) {
        failure = "the catalog read threw " + e;
        return;
      }
      if (!result.ok()) {
        failure = result.error();
        return;
      }
      projectsByName = new HashMap<>();
      projectById = new HashMap<>();
      for (CatalogEntry entry : result.entries()) {
        projectsByName.computeIfAbsent(entry.name(), k -> new HashSet<>()).add(entry.project());
        if (entry.catalogId() != null) {
          projectById.put(entry.catalogId(), entry.project());
        }
      }
    }
  }

  /** Why a candidate counts, or null when it does not (a PENDING still inside its grace). */
  Reason reason(MtArtifact row, Instant now) {
    SbomStatus status = SbomStatus.of(row.sbomStatus);
    return switch (status) {
      case MISSING -> Reason.MISSING;
      case FAILED -> Reason.FAILED;
      case PENDING ->
          row.occurredAt != null && row.occurredAt.isBefore(now.minus(grace()))
              ? Reason.PENDING
              : null;
      case INGESTED -> null;
    };
  }

  private Duration grace() {
    return pendingGrace == null ? Duration.ofHours(24) : pendingGrace;
  }

  private void probe(Map<String, Set<String>> present, String type, String name) {
    present.computeIfAbsent(key(type, name), k -> presence.versions(type, name));
  }

  // --- filing -----------------------------------------------------------------------------------

  private void file(List<Counted> group, Instant now) {
    MtArtifact first = group.get(0).row();
    Optional<MtSbomTicket> current = store.sbomTicket(first.projectId, first.ecosystem, first.name);
    Set<String> reported = new HashSet<>();
    current.ifPresent(
        row -> store.sbomTicketVersions(row.id).forEach(version -> reported.add(version.version)));
    List<Counted> fresh = group.stream().filter(c -> !reported.contains(c.row().version)).toList();
    if (fresh.isEmpty()) {
      return;
    }

    if (current.isPresent() && current.get().closedAt == null) {
      MtSbomTicket row = current.get();
      Optional<TicketClient.TicketState> state = tickets.read(row.ticketId);
      if (state.isPresent() && !state.get().closed()) {
        for (Counted c : fresh) {
          tickets.comment(row.ticketId, comment(c));
          store.recordSbomTicketVersion(row.id, c.row().version, c.reason().name(), now);
        }
        LOG.infof(
            "SBOM check: %d more version(s) of %s on ticket %s",
            fresh.size(), first.name, row.ticketSlug);
        return;
      }
    }

    // A NEW TICKET, carrying every counted version of the group — see the class comment.
    Counted lead = group.get(0);
    TicketClient.Filed filed =
        tickets.file(
            first.projectId,
            "SBOM missing for " + first.name + " (" + first.ecosystem + ")",
            first.name
                + " "
                + lead.row().version
                + " is in qits-artifacts with no usable SBOM ("
                + lead.reason()
                + ").",
            description(lead, now));
    UUID rowId =
        store.openSbomTicket(
            first.projectId, first.ecosystem, first.name, filed.id(), filed.slug(), now);
    store.recordSbomTicketVersion(rowId, lead.row().version, lead.reason().name(), now);
    for (Counted c : group.subList(1, group.size())) {
      tickets.comment(filed.id(), comment(c));
      store.recordSbomTicketVersion(rowId, c.row().version, c.reason().name(), now);
    }
    LOG.infof(
        "SBOM check: filed ticket %s for %s %s (%d version(s))",
        filed.slug(), first.ecosystem, first.name, group.size());
  }

  // --- closing ----------------------------------------------------------------------------------

  private void closeIfResolved(MtSbomTicket ticket, Map<String, Set<String>> present, Instant now) {
    List<String> resolutions = new ArrayList<>();
    Set<String> stillThere = present.getOrDefault(key(ticket.ecosystem, ticket.name), Set.of());
    for (MtSbomTicketVersion version : store.sbomTicketVersions(ticket.id)) {
      Optional<MtArtifact> row = store.artifact(ticket.ecosystem, ticket.name, version.version);
      if (row.isPresent() && SbomStatus.of(row.get().sbomStatus) == SbomStatus.INGESTED) {
        resolutions.add(version.version + ": INGESTED");
      } else if (!stillThere.contains(version.version)) {
        resolutions.add(version.version + ": collected");
      } else {
        // Not counted today and not resolved either — a re-queued fetch inside its grace, say.
        // The ticket stays until the version is one or the other.
        return;
      }
    }

    Optional<TicketClient.TicketState> state = tickets.read(ticket.ticketId);
    if (state.isEmpty() || state.get().closed()) {
      store.closeSbomTicket(ticket.id, now);
      return;
    }
    String closing = closing(resolutions);
    if (!state.get().maintenance()) {
      tickets.comment(
          ticket.ticketId,
          closing.replace(
                  "Closed by qits-maintenance's SBOM check.",
                  "Noted by qits-maintenance's SBOM check.")
              + "\n\nThis ticket is no longer MAINTENANCE, so it is left open: whoever retyped it"
              + " has taken it over.");
      store.closeSbomTicket(ticket.id, now);
      return;
    }
    tickets.drop(ticket.ticketId);
    store.closeSbomTicket(ticket.id, now);
    try {
      tickets.comment(ticket.ticketId, closing);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Dropped ticket %s but could not say why on its thread", ticket.ticketId);
    }
    LOG.infof("SBOM check: dropped ticket %s of %s, resolved", ticket.ticketSlug, ticket.name);
  }

  // --- the report -------------------------------------------------------------------------------

  private List<SbomCheckReportDto.EntryDto> entries(List<Counted> counted) {
    Map<String, String> names = new HashMap<>();
    List<SbomCheckReportDto.EntryDto> entries = new ArrayList<>();
    for (Counted c : counted) {
      MtArtifact row = c.row();
      entries.add(
          new SbomCheckReportDto.EntryDto(
              row.projectId,
              repository(row, names),
              row.ecosystem,
              row.name,
              row.version,
              c.reason().name()));
    }
    return List.copyOf(entries);
  }

  private List<SbomCheckReportDto.TicketDto> openTickets() {
    List<SbomCheckReportDto.TicketDto> open = new ArrayList<>();
    for (MtSbomTicket ticket : store.openSbomTickets()) {
      open.add(
          new SbomCheckReportDto.TicketDto(
              ticket.projectId,
              ticket.ecosystem,
              ticket.name,
              ticket.ticketSlug,
              store.sbomTicketVersions(ticket.id).stream().map(v -> v.version).toList()));
    }
    return List.copyOf(open);
  }

  private String repository(MtArtifact row, Map<String, String> names) {
    if (row.repository == null) {
      return null;
    }
    return names.computeIfAbsent(row.repository, store::repositoryName);
  }

  // --- the texts --------------------------------------------------------------------------------

  String description(Counted c, Instant now) {
    MtArtifact row = c.row();
    return "qits-maintenance's daily SBOM check found a software artifact in qits-artifacts without"
        + " a usable SBOM.\n\n"
        + "- **Artifact:** `"
        + row.ecosystem
        + "` `"
        + row.name
        + "`\n"
        + "- **Version:** `"
        + row.version
        + "` (published "
        + row.occurredAt
        + ")\n"
        + "- **Repository:** `"
        + (row.repository == null ? "unknown" : store.repositoryName(row.repository))
        + "`\n"
        + "- **Release run:** "
        + (row.runId == null ? "not recorded" : "`" + row.runId + "`")
        + " (qits-ci run; absent for releases before SoftwareRelease carried it)\n"
        + "- **release.yml entry:** `.config/qits/release.yml` at tag `"
        + row.version
        + "`: `{ type: "
        + row.ecosystem
        + ", name: "
        + row.name
        + " }`\n"
        + "- **Reason:** "
        + reasonLine(c, now)
        + "\n\n"
        + "## To analyse\n"
        + "- Does the entry declare `sbom:`, and does the step that builds it write that path?\n"
        + "- Did the release run's last step pass the SBOM presence check?\n"
        + "- For FAILED: fetch the document and validate it as CycloneDX 1.6.\n"
        + "- Is this an old version kept only because something pins it? Then moving that pin"
        + " forward lets GC collect it, and this ticket closes itself.\n\n"
        + "Later affected versions are added as comments. qits-maintenance closes this ticket itself"
        + " (DROPPED, with a comment) once every listed version has an ingested SBOM or is no longer"
        + " in the store — unless it has been retyped.";
  }

  private String reasonLine(Counted c, Instant now) {
    MtArtifact row = c.row();
    return switch (c.reason()) {
      case MISSING ->
          "MISSING — qits-artifacts answers 404 for `/artifacts/sboms/"
              + row.ecosystem
              + "/"
              + row.name
              + "/-/"
              + row.version
              + "`.";
      case FAILED ->
          "FAILED — the document is there and could not be read: `" + row.sbomError + "`.";
      case PENDING ->
          "PENDING — the ingest has not read it "
              + Duration.between(row.occurredAt, now).toHours()
              + "h after the release (grace "
              + grace().toHours()
              + "h).";
    };
  }

  String comment(Counted c) {
    MtArtifact row = c.row();
    return "Also without a usable SBOM: `"
        + row.version
        + "` (published "
        + row.occurredAt
        + ", run "
        + (row.runId == null ? "not recorded" : "`" + row.runId + "`")
        + ") — "
        + c.reason()
        + (c.reason() == Reason.FAILED && row.sbomError != null ? ": " + row.sbomError : "")
        + ".";
  }

  static String closing(List<String> resolutions) {
    return "Resolved: every version listed here now has an ingested SBOM or is no longer in"
        + " qits-artifacts ("
        + String.join(", ", resolutions)
        + "). Closed by qits-maintenance's SBOM check.";
  }

  private static String key(String type, String name) {
    return type + " " + name;
  }

  private static String group(String project, String ecosystem, String name) {
    return project + " " + ecosystem + " " + name;
  }
}

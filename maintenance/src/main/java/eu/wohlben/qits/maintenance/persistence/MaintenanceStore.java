package eu.wohlben.qits.maintenance.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtArtifactComponent;
import eu.wohlben.qits.maintenance.entity.MtArtifactEdge;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtBumpWindow;
import eu.wohlben.qits.maintenance.entity.MtGitlinkPin;
import eu.wohlben.qits.maintenance.entity.MtGitlinkTree;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRelease;
import eu.wohlben.qits.maintenance.entity.MtReleasePin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.entity.MtSbomCheckRun;
import eu.wohlben.qits.maintenance.entity.MtSbomTicket;
import eu.wohlben.qits.maintenance.entity.MtSbomTicketVersion;
import eu.wohlben.qits.maintenance.entity.MtScan;
import eu.wohlben.qits.maintenance.error.BumpAlreadyActiveException;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.ReleaseOrigin;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only writer of every table in this schema, and the reader the API uses.
 *
 * <p><b>Every method activates a request context</b>, because the caller is usually the worker
 * thread and a Hibernate session is bound to that context. A route's call already has one and
 * activating a second is a no-op, so one annotation covers both callers.
 *
 * <p><b>Every METHOD is a {@code DbRetry.inNewTx} — reads included.</b> {@code inNewTx} owns the
 * transaction boundary, which is the only way a retry can tell "the body threw, so it certainly
 * never committed" from "the transaction manager reported it" — Narayana spells a lost commit and a
 * real rollback with the same exception. Each write ends with a flush, which keeps a lost connection
 * on the body's side of that line.
 *
 * <p><b>And the reads are in for a second reason, which is why the rule is UNIFORM rather than
 * audited.</b> A durable bus frame is handled INSIDE the eventstream datasource's claim
 * transaction. A BARE read on this datasource from that path enlists a second, non-XA connection
 * into that claim — and the next {@code inNewTx} then dies on "failed to enlist / exception in
 * association of connection to existing transaction", the frame is classified retryable, and the
 * consumer wedges for ever behind one frame. That was measured twice: 2026-09-02, through the graph
 * fix's new {@code repositoryName} read, and again on 2026-09-03 through {@link #groups(String)},
 * reached from {@code ScmEventListener.onMaintenanceBranchReleased}. The first fix spot-audited the
 * four methods a listener was then known to touch; the second wedge came through a fifth. <b>Spot-
 * auditing has failed twice, so it is not the rule any more</b> — a read from a bus path must never
 * enlist this datasource into somebody else's claim transaction, and the only way to know that of
 * every method is for every method to own its transaction. A caller that is not a listener pays one
 * transaction it did not need, which is the cheap side of this trade.
 *
 * <p>The entities here are flat — every column a scalar, no association and nothing LAZY — so an
 * entity read inside its own transaction is fully materialized before it detaches, and every listing
 * is a {@code list()} taken inside the same boundary. Nothing outside this class mutates a row it
 * was handed.
 *
 * <p><b>One repository's inventory is replaced in ONE transaction.</b> Pins and groups are deleted
 * and rewritten together with the row that carries the sha they were read at; a scan that committed
 * the delete and failed the insert would leave a repository looking as though it pins nothing,
 * which is also what "nothing pending" looks like.
 */
@ApplicationScoped
public class MaintenanceStore implements PanacheRepositoryBase<MtRepository, String> {

  private static final ObjectMapper JSON = new ObjectMapper();

  // --- the inventory ------------------------------------------------------------------------

  /**
   * Replaces one repository's whole inventory: the row, its pins and its groups.
   *
   * @param kindOf what can be done with a pin — passed in rather than injected, because the rule is
   *     configuration and this class is storage
   * @param catalogId the catalog row's own id, kept so another context's spelling of this
   *     repository can be read back as a name. See {@link #repositoryName(String)}.
   * @param archetype what the catalog says this repository IS, verbatim and unvalidated —
   *     <b>written exactly as given, null included</b>. See the note at the assignment for why this
   *     one field does not follow {@code catalogId}'s never-null-an-existing-value rule.
   */
  @ActivateRequestContext
  public void replaceInventory(
      String name,
      String project,
      String catalogId,
      String archetype,
      String mainBranch,
      RepositoryStatus status,
      String headSha,
      String message,
      List<ParsedPin> pins,
      List<GroupConfig.Group> groups,
      GroupSource groupSource,
      java.util.function.Function<ParsedPin, PinKind> kindOf,
      Instant now) {
    replaceInventory(
        name, project, catalogId, archetype, mainBranch, status, headSha, message, pins, groups,
        groupSource, kindOf, List.of(), now);
  }

  /**
   * The same replacement, with the npm pins the repository reaches THROUGH ITS GITLINKS written
   * beside its own — in the one transaction, so {@code GET /pins} never reads a repository whose
   * gitlink rows belong to a different scan than its gitlinks. See {@link MtGitlinkPin} for why
   * they are a table of their own rather than {@code mt_pin} rows.
   *
   * @param gitlinkPins every row to serve for this repository, the kept ones of an unreadable tree
   *     included — the caller decided which; this replaces the repository's rows with exactly these
   */
  @ActivateRequestContext
  public void replaceInventory(
      String name,
      String project,
      String catalogId,
      String archetype,
      String mainBranch,
      RepositoryStatus status,
      String headSha,
      String message,
      List<ParsedPin> pins,
      List<GroupConfig.Group> groups,
      GroupSource groupSource,
      java.util.function.Function<ParsedPin, PinKind> kindOf,
      List<GitlinkPin> gitlinkPins,
      Instant now) {
    DbRetry.runInNewTx(
        "replace the inventory of " + name,
        () -> {
          // EVERY FIELD BEFORE THE PERSIST. `MtPin.delete` below is a query, and Hibernate flushes
          // before one — so a row persisted with its not-null columns still unset fails the flush
          // rather than the insert, naming a column nobody was writing at the time.
          MtRepository row = findById(name);
          boolean fresh = row == null;
          if (fresh) {
            row = new MtRepository();
            row.name = name;
          }
          row.project = project;
          // NEVER NULLED BY A SCAN THAT DID NOT SEE ONE. A catalog listing without an id says
          // nothing about the id this row already carries, and clearing it would take the
          // translation away from every graph row that still needs it.
          if (catalogId != null && !catalogId.isBlank()) {
            row.catalogId = catalogId;
          }
          // AND THIS ONE IS WRITTEN UNCONDITIONALLY, null included — deliberately the opposite of
          // the line above. The catalog is authoritative about what a repository IS on every scan:
          // a repository re-classified over there, or one whose archetype was cleared, must read
          // that way here on the next pass. Nothing in this database survives on a stale archetype
          // the way the graph's rows survive on a stale catalog_id, so there is nothing for a
          // never-null guard to protect and a re-classification it would silently ignore.
          row.archetype = archetype;
          row.mainBranch = mainBranch;
          row.status = status.name();
          row.headSha = headSha;
          row.message = message;
          row.lastScanAt = now;
          if (fresh) {
            persist(row);
          }

          MtPin.delete("repository", name);
          for (ParsedPin pin : pins) {
            MtPin stored = new MtPin();
            stored.id = UUID.randomUUID();
            stored.repository = name;
            stored.manifestPath = pin.manifestPath();
            stored.ecosystem = pin.ecosystem().wireName();
            stored.name = pin.name();
            stored.version = pin.version();
            stored.range = pin.range();
            stored.kind = kindOf.apply(pin).name();
            stored.location = pin.location();
            stored.persist();
          }

          MtGroup.delete("repository", name);
          int ordinal = 0;
          for (GroupConfig.Group group : groups) {
            MtGroup stored = new MtGroup();
            stored.id = UUID.randomUUID();
            stored.repository = name;
            stored.name = group.name();
            stored.ordinal = ordinal++;
            stored.patterns = writeJson(group.patterns());
            // A KIND GROUP CLAIMS BY KIND AND CARRIES NO GLOBS; a configured one is the other way
            // round. The column is nullable because those are the two shapes, not three.
            stored.kind = group.kind() == null ? null : group.kind().name();
            stored.source = (groupSource == null ? GroupSource.DEFAULT : groupSource).name();
            stored.persist();
          }

          MtGitlinkPin.delete("repository", name);
          for (GitlinkPin pin : gitlinkPins == null ? List.<GitlinkPin>of() : gitlinkPins) {
            MtGitlinkPin stored = new MtGitlinkPin();
            stored.id = UUID.randomUUID();
            stored.repository = name;
            stored.gitlinkPath = pin.gitlinkPath();
            stored.submodule = pin.submodule();
            stored.sha = pin.sha();
            stored.ecosystem = pin.ecosystem();
            stored.name = pin.name();
            stored.version = pin.version();
            stored.manifestPath = pin.manifestPath();
            stored.persist();
          }
          getEntityManager().flush();
        });
  }

  /**
   * One npm pin a repository reaches through a gitlink, as {@link #replaceInventory} writes it.
   *
   * @param gitlinkPath where the gitlink sits in the carrying repository
   * @param submodule the submodule's repository name
   * @param sha the commit the submodule's tree was read at
   * @param ecosystem the wire name — {@code npm}
   * @param name the package
   * @param version the lock's resolved version
   * @param manifestPath the submodule's manifest, prefixed by {@code gitlinkPath}
   */
  public record GitlinkPin(
      String gitlinkPath,
      String submodule,
      String sha,
      String ecosystem,
      String name,
      String version,
      String manifestPath) {

    /** A stored row back as the value it was written from — what a kept row is re-written as. */
    public static GitlinkPin of(MtGitlinkPin row) {
      return new GitlinkPin(
          row.gitlinkPath, row.submodule, row.sha, row.ecosystem, row.name, row.version,
          row.manifestPath);
    }
  }

  /**
   * One npm pin a submodule's tree declared, as {@link MtGitlinkTree#pins} holds it.
   *
   * @param manifestPath relative to the SUBMODULE's root
   */
  public record TreePin(String name, String version, String manifestPath) {}

  /**
   * What the submodule's tree at {@code sha} was read to pin, if it has been read — empty when it
   * never was, and a present empty list when it was and pins nothing.
   */
  @ActivateRequestContext
  public Optional<List<TreePin>> gitlinkTree(String submodule, String sha) {
    return DbRetry.inNewTx(
        "read the cached tree of " + submodule + " at " + sha,
        () -> {
          MtGitlinkTree row =
              MtGitlinkTree.find("submodule = ?1 and sha = ?2", submodule, sha).firstResult();
          if (row == null) {
            return Optional.<List<TreePin>>empty();
          }
          List<TreePin> pins = new java.util.ArrayList<>();
          for (Map<String, Object> pin : readObjects(row.pins)) {
            pins.add(
                new TreePin(
                    String.valueOf(pin.get("name")),
                    String.valueOf(pin.get("version")),
                    String.valueOf(pin.get("manifestPath"))));
          }
          return Optional.of(List.copyOf(pins));
        });
  }

  /**
   * Remembers what one submodule tree pins. <b>Write-once</b>: a commit's tree never changes, so a
   * row already there is left as it is rather than rewritten.
   */
  @ActivateRequestContext
  public void recordGitlinkTree(String submodule, String sha, List<TreePin> pins, Instant now) {
    DbRetry.runInNewTx(
        "remember the tree of " + submodule + " at " + sha,
        () -> {
          if (MtGitlinkTree.count("submodule = ?1 and sha = ?2", submodule, sha) > 0) {
            return;
          }
          MtGitlinkTree row = new MtGitlinkTree();
          row.id = UUID.randomUUID();
          row.submodule = submodule;
          row.sha = sha;
          row.pins = writeJson(pins);
          row.readAt = now;
          row.persist();
          getEntityManager().flush();
        });
  }

  /** The npm rows one repository reaches through its gitlinks, as the last scan wrote them. */
  @ActivateRequestContext
  public List<MtGitlinkPin> gitlinkPins(String repository) {
    return DbRetry.inNewTx(
        "read the gitlink pins of one repository",
        () ->
            MtGitlinkPin.find(
                    "repository = ?1", Sort.by("gitlinkPath").and("name"), repository)
                .list());
  }

  /** Every npm row reached through a gitlink, on the platform — the GC's pin source reads it. */
  @ActivateRequestContext
  public List<MtGitlinkPin> allGitlinkPins() {
    return DbRetry.inNewTx(
        "read every gitlink pin",
        () ->
            MtGitlinkPin.findAll(Sort.by("repository").and("gitlinkPath").and("name")).list());
  }

  /**
   * Records an outcome that says nothing about the pins — an unreachable git host.
   *
   * <p><b>The pins are left standing.</b> A peer that could not be asked is not evidence that a
   * repository stopped pinning anything, and wiping the inventory on every hiccup would make the
   * UI's "pending" flicker to zero whenever the git host restarted.
   */
  @ActivateRequestContext
  public void markRepository(
      String name,
      String project,
      String catalogId,
      String archetype,
      RepositoryStatus status,
      String message,
      Instant now) {
    DbRetry.runInNewTx(
        "mark " + name + " " + status,
        () -> {
          MtRepository row = findById(name);
          boolean fresh = row == null;
          if (fresh) {
            row = new MtRepository();
            row.name = name;
          }
          if (project != null) {
            row.project = project;
          }
          if (catalogId != null && !catalogId.isBlank()) {
            row.catalogId = catalogId;
          }
          // Unconditionally, null included, and for the same reason {@link #replaceInventory}
          // gives: the caller of this method reached it holding a CatalogEntry, so the archetype
          // it passes is what the catalog answered this scan — including the catalog answering
          // nothing. A repository the git host would not talk about is still a repository
          // qits-projects has an opinion about.
          row.archetype = archetype;
          row.status = status.name();
          row.message = message;
          row.lastScanAt = now;
          if (fresh) {
            persist(row);
          }
          getEntityManager().flush();
        });
  }

  /**
   * <b>THE INVENTORY FOLLOWS THE CATALOG OUT, NOT ONLY IN.</b> Every row whose name the catalog no
   * longer lists is marked {@link RepositoryStatus#ABSENT} and loses its pins and its groups.
   *
   * <p>Until 2026-09-03 a scan only ever UPSERTED what the catalog listed, so a repository that was
   * renamed or deleted kept the status, the pins and the groups of the last scan that found it —
   * for ever, because nothing else writes those rows. Measured on the first nightly bump: 96
   * repository rows against a catalog of 48, ~800 pending changes counted against pins nothing can
   * edit, and 23 of 30 bumps FAILED with {@code no run recorded for MaintenanceBump} because the
   * clock was bumping pre-rename ghosts (qits-spa-artifacts, qits-stt, qits-projects,
   * qits-platform-spa-*). An inventory is a CACHE of the catalog's world, and a cache that only ever
   * grows is not one.
   *
   * <p><b>The row is KEPT rather than deleted</b>, and that is the point of ABSENT existing as a
   * status. The name still answers on {@code GET /repositories/{name}} with an honest sentence
   * instead of a 404 that says nothing about why, and {@code catalog_id} — the translation every
   * {@code mt_artifact} row written under another context's spelling still needs — survives with it.
   *
   * <p><b>The pins and the groups go</b>, because they are the cache: a repository the catalog
   * dropped contributes no pending change, offers the clock no group to bump (see {@code
   * BumpSchedule}, which additionally skips everything that is not OK), and has no line anybody
   * could edit. <b>{@code mt_branch} is left standing</b>, with {@code mt_scan} and {@code mt_bump}:
   * those three are the LOG of what was asked and what came back, derivable from nothing, and a
   * repository leaving the catalog does not un-push a branch that was pushed.
   *
   * <p><b>A repository that RETURNS needs nothing from here.</b> The next scan lists it again and
   * {@link #replaceInventory} writes OK with fresh pins and fresh groups over the ABSENT row.
   *
   * <p><b>An empty listing reconciles NOTHING</b>, whatever the caller believes it read. That is the
   * one failure mode with teeth — a catalog that answered `[]`, or a caller that reached here with a
   * partial listing, would mark the whole inventory absent and wipe every pin in one transaction.
   * The caller guards it too (a scan of a single repository never calls this, and a failed catalog
   * read closes the scan before it gets here); this is the belt under that brace, because the cost
   * of the two guards disagreeing is the whole store.
   *
   * @param listed every name the catalog answered — the WHOLE catalog, never a filtered subset
   * @param message the sentence the dropped rows carry
   * @return the names newly dropped, which is what is worth a log line; a row already ABSENT for
   *     this reason is rewritten idempotently and not reported again
   */
  @ActivateRequestContext
  public List<String> reconcileCatalog(
      java.util.Collection<String> listed, String message, Instant now) {
    if (listed == null || listed.isEmpty()) {
      return List.of();
    }
    List<String> names = List.copyOf(listed);
    return DbRetry.inNewTx(
        "reconcile the inventory against the catalog",
        () -> {
          List<MtRepository> gone = find("name not in ?1", names).list();
          if (gone.isEmpty()) {
            return List.<String>of();
          }
          List<String> dropped = new java.util.ArrayList<>();
          List<String> absent = new java.util.ArrayList<>();
          for (MtRepository row : gone) {
            // NEWLY dropped, so a nightly scan does not report the same twenty-three names for ever.
            if (!RepositoryStatus.ABSENT.name().equals(row.status)
                || !java.util.Objects.equals(message, row.message)) {
              dropped.add(row.name);
            }
            row.status = RepositoryStatus.ABSENT.name();
            row.message = message;
            row.lastScanAt = now;
            absent.add(row.name);
          }
          // Two bulk deletes rather than two per row: this runs over the whole inventory on every
          // full scan, and the updates above are flushed by the first query anyway.
          MtPin.delete("repository in ?1", absent);
          MtGroup.delete("repository in ?1", absent);
          // …and what its gitlinks reached, which is the same cache one hop further out.
          MtGitlinkPin.delete("repository in ?1", absent);
          getEntityManager().flush();
          return List.copyOf(dropped);
        });
  }

  /** One dependency's latest, written whether the lookup succeeded or failed. */
  @ActivateRequestContext
  public void recordLatest(Ecosystem ecosystem, String name, LatestLookup lookup, Instant now) {
    DbRetry.runInNewTx(
        "record the latest of " + name,
        () -> {
          MtLatest row =
              MtLatest.find("ecosystem = ?1 and name = ?2", ecosystem.wireName(), name)
                  .firstResult();
          boolean fresh = row == null;
          if (fresh) {
            row = new MtLatest();
            row.id = UUID.randomUUID();
            row.ecosystem = ecosystem.wireName();
            row.name = name;
          }
          row.latest = lookup.latest();
          row.sourceUrl = lookup.sourceUrl();
          row.error = lookup.error();
          row.checkedAt = now;
          if (fresh) {
            row.persist();
          }
          getEntityManager().flush();
        });
  }

  /**
   * One dependency's latest, moved FORWARD ONLY — the bus's write, beside {@link #recordLatest}'s
   * polled one.
   *
   * <p><b>Why a second method rather than a flag on the first.</b> A poll ASKS a registry what the
   * newest version is and the answer replaces whatever was there, downgrades included: a package
   * that was unpublished really is behind now. An event ANNOUNCES one release, and an announcement
   * is only ever evidence that this version exists — never that a higher one does not. Letting the
   * bus write through {@code recordLatest} would let a catch-up frame from yesterday rewind a column
   * a scan filled this morning, and the whole inventory would show that dependency as up to date
   * until the next scan.
   *
   * <p>So the guard is {@link VersionOrder}'s, in that ecosystem's own order, and it is the same
   * comparison the pending rule makes. Three cases and only the first writes:
   *
   * <ul>
   *   <li>no row at all, or a row whose lookup FAILED ({@code latest} null) — the announcement is
   *       the first thing known about this dependency, so it is adopted;
   *   <li>a strictly newer version — the column moves and {@code error} is cleared;
   *   <li>anything else — an equal version (the ordinary redelivery), or an older one (a catch-up
   *       frame behind a scan) — and <b>nothing at all is written</b>, {@code checked_at} included.
   *       Stamping the timestamp would say a lookup happened, and none did.
   * </ul>
   *
   * <p>That makes it idempotent under redelivery by construction: the second offer of one release is
   * not newer than the first, so it is a read and a return.
   *
   * @param sourceUrl where the claim came from — the bus writes {@code event:<frame id>}, which is
   *     what tells a surprising row from a registry read
   * @return whether the column moved
   */
  @ActivateRequestContext
  public boolean recordLatestIfNewer(
      Ecosystem ecosystem, String name, String version, String sourceUrl, Instant now) {
    if (version == null || version.isBlank()) {
      return false;
    }
    return DbRetry.inNewTx(
        "record the announced latest of " + name,
        () -> {
          MtLatest row =
              MtLatest.find("ecosystem = ?1 and name = ?2", ecosystem.wireName(), name)
                  .firstResult();
          if (row != null
              && row.latest != null
              && !VersionOrder.newer(ecosystem, row.latest, version)) {
            return false;
          }
          boolean fresh = row == null;
          if (fresh) {
            row = new MtLatest();
            row.id = UUID.randomUUID();
            row.ecosystem = ecosystem.wireName();
            row.name = name;
          }
          row.latest = version;
          row.sourceUrl = sourceUrl;
          row.error = null;
          row.checkedAt = now;
          if (fresh) {
            row.persist();
          }
          getEntityManager().flush();
          return true;
        });
  }

  @ActivateRequestContext
  public List<MtRepository> repositories() {
    return DbRetry.inNewTx("read every repository row", () -> listAll(Sort.by("name")));
  }

  @ActivateRequestContext
  public Optional<MtRepository> repository(String name) {
    return DbRetry.inNewTx(
        "read one repository row", () -> Optional.ofNullable(findById(name)));
  }

  /**
   * <b>ANOTHER CONTEXT'S SPELLING OF A REPOSITORY, READ BACK AS THE NAME THIS INVENTORY IS KEYED
   * BY.</b>
   *
   * <p>qits-ci's {@code SoftwareRelease} names the repository by qits-projects' ROW ID, not by the
   * catalog name — measured live 2026-09-02 — while every join on this side is name-keyed. This is
   * the whole translation, and it is deliberately a total function over three cases:
   *
   * <ul>
   *   <li>the value IS a name this catalog knows — answered unchanged, which is the ordinary case
   *       and the one a future qits-ci that publishes names lands in;
   *   <li>the value is a known {@code catalog_id} — answered with that row's name;
   *   <li>anything else — answered <b>unchanged</b>. A repository this inventory has never scanned,
   *       or one whose id arrived before the first scan after V5, still said something, and losing
   *       the string would lose the fact along with the spelling.
   * </ul>
   *
   * <p><b>One query</b>, {@code name = ?1 or catalog_id = ?1}, because the caller is the bus
   * listener and it runs inside the claim transaction of somebody else's release.
   *
   * @return the catalog name, or the spelling unchanged when the catalog knows neither
   */
  @ActivateRequestContext
  public String repositoryName(String spelling) {
    if (spelling == null || spelling.isBlank()) {
      return spelling;
    }
    // In its own transaction like every other method here — this one is where the wedge was first
    // measured (2026-09-02). See the class doctrine.
    return DbRetry.inNewTx(
        "resolve a repository spelling",
        () -> {
          MtRepository row = find("name = ?1 or catalogId = ?1", spelling).firstResult();
          return row == null ? spelling : row.name;
        });
  }

  @ActivateRequestContext
  public List<MtPin> pins(String repository) {
    return DbRetry.inNewTx(
        "read the pins of one repository",
        () ->
            MtPin.find("repository = ?1", Sort.by("manifestPath").and("name"), repository).list());
  }

  /**
   * Every pin on the platform, in one total order.
   *
   * <p>Two readers and they want it for opposite reasons. The GC's pin source serves these rows as
   * stored, so the order is the answer's — two reads over an unchanged store answer the same bytes.
   * {@code adoption/DownstreamResolver} folds them into an in-memory {@code (ecosystem, name) →
   * consumers} index and asks it once per coordinate per hop, which is why the per-coordinate
   * indexed read the release trains used is gone: dozens of round trips inside a human GET, for a
   * table of a few thousand rows.
   */
  @ActivateRequestContext
  public List<MtPin> allPins() {
    return DbRetry.inNewTx(
        "read every pin",
        () -> MtPin.findAll(Sort.by("repository").and("manifestPath").and("name")).list());
  }

  /**
   * The groups one repository declares, in the order they claim pins in.
   *
   * <p>This is the read the second wedge came through — {@code
   * ScmEventListener.onMaintenanceBranchReleased} asks it about a released maintenance branch, one
   * line after a read that already owned its transaction. See the class doctrine.
   */
  @ActivateRequestContext
  public List<MtGroup> groups(String repository) {
    return DbRetry.inNewTx(
        "read the groups of one repository",
        () -> MtGroup.find("repository = ?1", Sort.by("ordinal"), repository).list());
  }

  @ActivateRequestContext
  public List<MtLatest> allLatest() {
    return DbRetry.inNewTx("read every latest row", () -> MtLatest.listAll());
  }

  /**
   * Every latest row of one ecosystem.
   *
   * <p>One reader: {@code work/ReleaseLedgerBackfill}, whose whole input is the GITLINK rows — the
   * only place on this platform where "this repository released this version, at this commit" is
   * written down for releases that predate the release ledger. Filtering {@link #allLatest()} in
   * java would answer the same question by reading every dependency the platform pins.
   */
  @ActivateRequestContext
  public List<MtLatest> latestOf(Ecosystem ecosystem) {
    return DbRetry.inNewTx(
        "read every latest row of one ecosystem",
        () -> MtLatest.find("ecosystem = ?1", Sort.by("name"), ecosystem.wireName()).list());
  }

  @ActivateRequestContext
  public Optional<MtLatest> latest(Ecosystem ecosystem, String name) {
    return DbRetry.inNewTx(
        "read the latest of one dependency",
        () ->
            Optional.ofNullable(
                MtLatest.find("ecosystem = ?1 and name = ?2", ecosystem.wireName(), name)
                    .firstResult()));
  }

  // --- scans --------------------------------------------------------------------------------

  /** Opens a scan row, REQUESTED, before it is queued. */
  @ActivateRequestContext
  public UUID openScan(ScanScope scope, String repository, String trigger, Instant now) {
    return DbRetry.inNewTx(
        "open a " + scope + " scan",
        () -> {
          MtScan row = new MtScan();
          row.id = UUID.randomUUID();
          row.scope = scope.name();
          row.repository = repository;
          row.trigger = trigger;
          row.status = ScanStatus.REQUESTED.name();
          row.startedAt = now;
          row.persist();
          getEntityManager().flush();
          return row.id;
        });
  }

  /** Moves a scan along. A terminal status stamps {@code finished_at} and nothing else does. */
  @ActivateRequestContext
  public void scanStatus(UUID id, ScanStatus status, String message, Instant now) {
    DbRetry.runInNewTx(
        "set scan " + id + " " + status,
        () -> {
          MtScan row = MtScan.findById(id);
          if (row == null) {
            return;
          }
          row.status = status.name();
          if (message != null) {
            row.message = message;
          }
          if (status.terminal()) {
            row.finishedAt = now;
          }
          getEntityManager().flush();
        });
  }

  /**
   * Closes every scan a dead process left open, and answers how many there were.
   *
   * <p>A scan's work is entirely in this process — reads it made and rows it wrote — so a successor
   * cannot resume one and must not pretend it did. FAILED with a sentence is the honest record; the
   * next schedule scans again in minutes.
   */
  @ActivateRequestContext
  public long failInterruptedScans(String message, Instant now) {
    return DbRetry.inNewTx(
        "close the scans a restart interrupted",
        () -> {
          List<MtScan> open =
              MtScan.find(
                      "status in ?1",
                      List.of(ScanStatus.REQUESTED.name(), ScanStatus.RUNNING.name()))
                  .list();
          for (MtScan row : open) {
            row.status = ScanStatus.FAILED.name();
            row.message = message;
            row.finishedAt = now;
          }
          getEntityManager().flush();
          return (long) open.size();
        });
  }

  /**
   * Whether a scan of exactly this repository is already queued or running — the bus's debounce.
   *
   * <p>A push to a repository's main branch asks for that one repository to be re-read, and a burst
   * of pushes is the ordinary shape of a merge. Without this, five pushes in a minute are five scan
   * rows queued behind one worker thread, each re-reading a tree the one in front of it already
   * read.
   *
   * <p><b>It is repository-scoped exactly, and a whole-catalog scan is deliberately NOT counted.</b>
   * A full scan does cover this repository, so counting it would debounce correctly — but it runs
   * for minutes, and suppressing an event's rescan for the length of one would mean a push landing
   * during the nightly scan is read at whatever revision that scan happened to reach. One extra
   * git-host read behind a single-threaded queue is the cheaper mistake.
   */
  @ActivateRequestContext
  public boolean scanPending(String repository) {
    return DbRetry.inNewTx(
        "count pending scans",
        () ->
            MtScan.count(
                    "repository = ?1 and status in ?2",
                    repository,
                    List.of(ScanStatus.REQUESTED.name(), ScanStatus.RUNNING.name()))
                > 0);
  }

  @ActivateRequestContext
  public Optional<MtScan> scan(UUID id) {
    return DbRetry.inNewTx(
        "read one scan row", () -> Optional.ofNullable(MtScan.findById(id)));
  }

  /** The newest scans. */
  @ActivateRequestContext
  public List<MtScan> scans(int limit) {
    return DbRetry.inNewTx(
        "read the newest scans",
        () -> MtScan.findAll(Sort.by("startedAt").descending()).page(0, limit).list());
  }

  // --- branches -----------------------------------------------------------------------------

  @ActivateRequestContext
  public List<MtBranch> branches(String repository) {
    return DbRetry.inNewTx(
        "read the branches of one repository",
        () -> MtBranch.find("repository = ?1", Sort.by("groupName"), repository).list());
  }

  @ActivateRequestContext
  public Optional<MtBranch> branch(String repository, String group) {
    return DbRetry.inNewTx(
        "read one branch row",
        () ->
            Optional.ofNullable(
                MtBranch.find("repository = ?1 and groupName = ?2", repository, group)
                    .firstResult()));
  }

  /** Writes what the git host says about one group's branch. */
  @ActivateRequestContext
  public void recordBranch(
      String repository,
      String group,
      String branchName,
      BranchState state,
      String headSha,
      Instant now) {
    DbRetry.runInNewTx(
        "record the branch of " + repository + "/" + group,
        () -> {
          MtBranch row =
              MtBranch.find("repository = ?1 and groupName = ?2", repository, group).firstResult();
          boolean fresh = row == null;
          if (fresh) {
            row = new MtBranch();
            row.id = UUID.randomUUID();
            row.repository = repository;
            row.groupName = group;
          }
          row.branch = branchName;
          row.state = state.name();
          row.headSha = headSha;
          row.updatedAt = now;
          if (fresh) {
            row.persist();
          }
          getEntityManager().flush();
        });
  }

  // --- bumps --------------------------------------------------------------------------------

  /**
   * Opens a bump.
   *
   * <p><b>The active-bump check is INSIDE the transaction</b> rather than a read before it. A
   * person pressing the button while a scheduled scan asks for the same group is the ordinary case,
   * and a check outside the write is a race whose prize is two runs pushing one branch.
   */
  @ActivateRequestContext
  public UUID openBump(
      String repository,
      String group,
      String branch,
      String environment,
      BumpTrigger trigger,
      List<?> changes,
      Instant now) {
    return DbRetry.inNewTx(
        "open a bump of " + repository + "/" + group,
        () -> {
          MtBump active = activeBumpRow(repository, group);
          if (active != null) {
            throw new BumpAlreadyActiveException(repository, group, active.id);
          }
          MtBump row = new MtBump();
          row.id = UUID.randomUUID();
          row.repository = repository;
          row.groupName = group;
          row.mode = BumpMode.GROUP.name();
          row.branch = branch;
          row.environment = environment;
          row.trigger = trigger.name();
          row.status = BumpStatus.REQUESTED.name();
          row.changes = writeJson(changes);
          row.startedAt = now;
          row.persist();
          getEntityManager().flush();
          return row.id;
        });
  }

  /** Records the branch head a run starts from, so the ending can tell whether it moved. */
  @ActivateRequestContext
  public void bumpStartHead(UUID id, String head) {
    DbRetry.runInNewTx(
        "record the start head of bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          row.resultSha = head;
          getEntityManager().flush();
        });
  }

  /** Records what qits-projects said to the join, without ending anything. */
  @ActivateRequestContext
  public void bumpJoined(UUID id, String releaseState, String releaseDetail, Instant now) {
    DbRetry.runInNewTx(
        "record the join of bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          row.releaseState = releaseState;
          row.releaseDetail = releaseDetail;
          row.releaseStateAt = now;
          getEntityManager().flush();
        });
  }

  /** Records that qits-ci accepted the trigger and named its runs. */
  @ActivateRequestContext
  public void bumpDispatched(UUID id, String eventId, List<String> runIds) {
    DbRetry.runInNewTx(
        "dispatch bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          row.ciEventId = eventId;
          row.ciRunId = runIds.isEmpty() ? null : String.join(",", runIds);
          row.status = BumpStatus.RUNNING.name();
          getEntityManager().flush();
        });
  }

  /**
   * Adds qits-ci's automatic retry of one of a bump's runs to the runs it follows (qits-760), once:
   * the retry's verdict stands for the run it re-fires. Appended rather than substituted, so the
   * row keeps naming every run that was part of its answer.
   */
  @ActivateRequestContext
  public void bumpRunAdopted(UUID id, String runId) {
    DbRetry.runInNewTx(
        "adopt run " + runId + " into bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          List<String> ids = new ArrayList<>();
          if (row.ciRunId != null) {
            for (String held : row.ciRunId.split(",")) {
              if (!held.isBlank()) {
                ids.add(held.trim());
              }
            }
          }
          if (ids.contains(runId)) {
            return;
          }
          ids.add(runId);
          row.ciRunId = String.join(",", ids);
          getEntityManager().flush();
        });
  }

  /** Records the CI run status a poll read, without ending the bump. */
  @ActivateRequestContext
  public void bumpRunStatus(UUID id, String ciRunStatus) {
    DbRetry.runInNewTx(
        "record the ci status of bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          row.ciRunStatus = ciRunStatus;
          getEntityManager().flush();
        });
  }

  /** Closes a bump. */
  @ActivateRequestContext
  public void bumpFinished(
      UUID id, BumpStatus status, String ciRunStatus, String message, Instant now) {
    bumpFinished(id, status, ciRunStatus, message, null, now);
  }

  /**
   * Closes a bump, naming the commit it wrote.
   *
   * <p><b>The sha is written only where it is known, and never cleared.</b> Passing null leaves the
   * column alone rather than blanking it — every ending that pushed nothing goes through the shorter
   * overload above and has nothing to say about a commit, and an ending that DID read one must not
   * have it erased by a later write that did not.
   */
  @ActivateRequestContext
  public void bumpFinished(
      UUID id,
      BumpStatus status,
      String ciRunStatus,
      String message,
      String resultSha,
      Instant now) {
    DbRetry.runInNewTx(
        "finish bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          row.status = status.name();
          if (ciRunStatus != null) {
            row.ciRunStatus = ciRunStatus;
          }
          if (resultSha != null) {
            row.resultSha = resultSha;
          }
          row.message = message;
          row.finishedAt = status.terminal() ? now : null;
          getEntityManager().flush();
        });
  }

  /**
   * Records what the release ask answered, without touching the bump's status.
   *
   * <p><b>The status is deliberately untouched and that is the whole failure policy.</b> A bump that
   * reached SUCCEEDED had a green run and a branch that moved — facts about this service's own work.
   * Whether qits-projects answered is not one of them, and flipping the row to FAILED because that
   * service was restarting would report somebody else's downtime as this service's, over a branch
   * that really is pushed.
   *
   * @param releaseRequestId the release request's id, or the {@code converged} / {@code refused}
   *     sentinel — or NULL to leave the column alone, which is what a retryable answer writes: the
   *     message is updated so a person can see what happened, and the sweep asks again on the next
   *     tick because the column is still empty
   */
  @ActivateRequestContext
  public void bumpReleaseAsked(UUID id, String releaseRequestId, String message) {
    DbRetry.runInNewTx(
        "record the release ask of bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          if (releaseRequestId != null) {
            row.releaseRequestId = releaseRequestId;
          }
          if (message != null) {
            row.message = message;
          }
          getEntityManager().flush();
        });
  }

  /**
   * Records what qits-projects last said about the release request this bump opened.
   *
   * <p><b>Bookkeeping for a reader, never a gate.</b> The dispatcher acts on the answer it has just
   * received and writes it here so that a bump standing since the morning explains itself in the
   * listing; see {@link MtBump#releaseState}. A row whose state has not moved is still re-stamped,
   * because "when was this last true" is half of what the column is worth.
   */
  @ActivateRequestContext
  public void bumpReleaseState(UUID id, String state, String detail, Instant at) {
    DbRetry.runInNewTx(
        "record the release state of bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          row.releaseState = state;
          row.releaseDetail = detail;
          row.releaseStateAt = at;
          getEntityManager().flush();
        });
  }

  /**
   * Forgets the release request a bump recorded, because qits-projects says it was WITHDRAWN.
   *
   * <p><b>A withdrawn request counts as no request at all</b> (owner decision 2026-10-04, qits-886).
   * {@link #bumpReleaseAsked} cannot say so — a null there means "leave the column alone" — so this
   * is the one writer that puts the column back to null. That returns the row to {@link
   * #bumpsOwedARelease}, and the sweep asks for a fresh request while the branch is still pushed and
   * ahead of main, or writes {@code converged} when it is not.
   *
   * <p>The observation columns are cleared with it: a null request id beside a WITHDRAWN state would
   * read on the bump page as a release that stopped, when what it is is an ask that is owed again.
   * The message says what happened instead.
   *
   * <p><b>Only the request that was read is cleared.</b> If the column no longer holds it — the sweep
   * already asked again and stored the fresh one — this writes nothing.
   *
   * @param requestId the request qits-projects answered WITHDRAWN for
   * @param message the bump's sentence, saying so
   */
  @ActivateRequestContext
  public void bumpReleaseWithdrawn(UUID id, String requestId, String message) {
    DbRetry.runInNewTx(
        "forget the withdrawn release request of bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null || !requestId.equals(row.releaseRequestId)) {
            return;
          }
          row.releaseRequestId = null;
          row.releaseState = null;
          row.releaseDetail = null;
          row.releaseStateAt = null;
          if (message != null) {
            row.message = message;
          }
          getEntityManager().flush();
        });
  }

  /**
   * Every bump that pushed a branch and has not settled its release ask — what the sweep re-attempts.
   *
   * <p>Bounded by construction rather than by a limit: every outcome of the ask writes the column, so
   * a row leaves this listing after one tick. The rows that stay are the ones whose ask is genuinely
   * owed, and the branch-state check the caller makes is what ends even those.
   *
   * <p><b>GROUP mode only, and that is what keeps the boundedness true.</b> A targeted bump never
   * asks for a release — the caller already has the request its pins are for — so its {@code
   * release_request_id} is null for ever by design. Without the term every targeted row would join
   * this listing the moment it succeeded and be re-attempted once per poll tick for the life of the
   * row: not an ask that is owed, but one that will never be made.
   *
   * <p><b>NOTHING_TO_DO is here too, and it is what makes a stranded branch self-heal.</b> That
   * ending means "this run pushed nothing", which is not "this branch has nothing unreleased" — a
   * head read that raced the step's own push is enough to separate the two, and the branch then sat
   * unreleased while the dispatcher held its repository for a release nobody had asked for
   * (qits-mirror-platform-service, 2026-09-16, a day on an old qits-integrations). {@code
   * BumpService.finishGroup} now makes the ask at the ending when the branch is ahead of main; this
   * term is what reaches the rows that ended before it did, and what re-attempts an ask that did not
   * land. The caller's branch-state check is still what ends them: a NOTHING_TO_DO whose branch is
   * genuinely main writes {@code CONVERGED} on the first tick and leaves, so the listing stays
   * bounded exactly as the paragraph above describes.
   */
  @ActivateRequestContext
  public List<MtBump> bumpsOwedARelease() {
    return DbRetry.inNewTx(
        "read the bumps owed a release ask",
        () ->
            MtBump.find(
                    "status in ?1 and releaseRequestId is null and mode = ?2",
                    Sort.by("startedAt"),
                    List.of(BumpStatus.SUCCEEDED.name(), BumpStatus.NOTHING_TO_DO.name()),
                    BumpMode.GROUP.name())
                .list());
  }

  @ActivateRequestContext
  public Optional<MtBump> bump(UUID id) {
    return DbRetry.inNewTx(
        "read one bump row", () -> Optional.ofNullable(MtBump.findById(id)));
  }

  /** The newest bumps, of one repository or of all of them. */
  @ActivateRequestContext
  public List<MtBump> bumps(String repository, int limit) {
    return DbRetry.inNewTx(
        "read the newest bumps",
        () -> {
          Sort newestFirst = Sort.by("startedAt").descending();
          if (repository == null || repository.isBlank()) {
            List<MtBump> all = MtBump.findAll(newestFirst).page(0, limit).list();
            return all;
          }
          List<MtBump> ofOne =
              MtBump.find("repository = ?1", newestFirst, repository).page(0, limit).list();
          return ofOne;
        });
  }

  /**
   * The newest bumps still on their way. A bump is pending while it is asked for or running, and
   * after a green run while its branch is not released: the release is still owed (a pushed commit
   * and no answer yet), or the release request is still open. {@code converged} and {@code refused}
   * end it, as do a request that shipped, a failure and nothing to do. A withdrawn request does not:
   * the dispatcher clears it, which puts the bump back to an ask that is owed.
   */
  @ActivateRequestContext
  public List<MtBump> pendingBumps(int limit) {
    return DbRetry.inNewTx(
        "read the pending bumps",
        () -> {
          List<MtBump> pending =
              MtBump.find(
                      // The release arm is a GROUP bump's: an automation's request id is the
                      // request it belongs to, never one it asked for, so a green one is over.
                      "status in ?1 or (status = ?2 and mode = ?5 and ((releaseRequestId is null"
                          + " and resultSha is not null) or (releaseRequestId not in ?3 and"
                          + " (releaseState is null or releaseState in ?4))))",
                      Sort.by("startedAt").descending(),
                      List.of(BumpStatus.REQUESTED.name(), BumpStatus.RUNNING.name()),
                      BumpStatus.SUCCEEDED.name(),
                      List.of("converged", "refused"),
                      List.of("PENDING", "READY", "REJECTED", "FAILED", "CONFLICTED"),
                      BumpMode.GROUP.name())
                  .page(0, limit)
                  .list();
          return pending;
        });
  }

  /** Every bump that has not ended — what the poller drives. */
  @ActivateRequestContext
  public List<MtBump> activeBumps() {
    return DbRetry.inNewTx(
        "read the bumps still running",
        () ->
            MtBump.find(
                    "status in ?1",
                    Sort.by("startedAt"),
                    List.of(BumpStatus.REQUESTED.name(), BumpStatus.RUNNING.name()))
                .list());
  }

  /** The group bump holding one group's branch, if there is one. */
  @ActivateRequestContext
  public Optional<MtBump> activeBump(String repository, String group) {
    return DbRetry.inNewTx(
        "read the active bump of one group",
        () -> Optional.ofNullable(activeBumpRow(repository, group)));
  }

  /**
   * The newest bump of one group, whatever became of it.
   *
   * <p><b>Newest, not newest-ended</b> — the dispatcher asks this to find out whether the last thing
   * it did to a repository is still standing, and a row that is REQUESTED or RUNNING is an answer to
   * that question too (it is not a bump that ended without failing, so it holds nothing back). One
   * row per repository per tick, which is why this exists rather than {@link #bumps(String, int)}:
   * that one reads a page of history for a listing, and the gate wants a single row.
   */
  @ActivateRequestContext
  public Optional<MtBump> newestBump(String repository, String group) {
    return DbRetry.inNewTx(
        "read the newest bump of one group",
        () ->
            Optional.ofNullable(
                (MtBump)
                    MtBump.find(
                            // GROUP MODE ONLY. The question is whether this group's nightly branch is
                            // waiting on a release; a targeted bump onto somebody's workspace branch
                            // is not an answer to it, and letting one be the newest row would hold —
                            // or free — a group on the strength of work done to a different ref for a
                            // different caller.
                            "repository = ?1 and groupName = ?2 and mode = ?3",
                            Sort.by("startedAt").descending(),
                            repository,
                            group,
                            BumpMode.GROUP.name())
                        .firstResult()));
  }

  /**
   * WHEN THE CLOCK LAST REACHED EACH REPOSITORY — the newest {@code started_at} of its SCHEDULED
   * bumps, one entry per repository that has ever had one.
   *
   * <p><b>It exists to break a tie, and the tie used to be broken by the alphabet.</b> The
   * dispatcher builds its candidates by walking {@link #repositories()}, which sorts by name, and
   * {@code BumpOrder} then hands out the first free candidate in the order it was given — so among
   * repositories that are equally ready, the arbiter was the first letter of the name. One bump goes
   * at a time and each is held until its own release lands, so a fan-out of the whole estate drains
   * at roughly one repository every five to fifteen minutes and the end of the alphabet is always
   * last. Measured 2026-09-13: {@code qits-projects-daemon} and {@code qits-workspace-daemon}
   * consume the identical two jars from one {@code qits-coding-agents} release; the first was bumped
   * at 19:53 and the second at 21:28, nine repositories later, for no reason but its name. That is
   * permanent starvation rather than jitter — the same repositories lose every single time — which
   * is what makes it worth a query. Ordering the candidates by this map ascending puts the
   * least-recently-bumped first, and a repository can no longer be permanently last.
   *
   * <p><b>SCHEDULED rows only, and the term is load-bearing.</b> A targeted bump is a caller's press
   * on a branch that caller owns, and a MANUAL group bump is somebody pressing the button; neither
   * is the clock reaching this repository, and counting either would let a person asking for one
   * favour push that repository to the back of the queue this map drains — exactly the starvation
   * the tiebreak is here to end.
   *
   * <p>A repository with no scheduled bump at all is simply ABSENT rather than carrying a sentinel:
   * "never" is not a timestamp, and the caller is the one that decides what never ranks as (it ranks
   * as the oldest, which is the honest reading — nothing has ever been handed to it).
   *
   * <p>One grouped query per tick over {@code mt_bump}, not a row per candidate: the table holds
   * every bump this service has ever dispatched, so a scan folded into a map in Java would grow
   * without bound while the answer stays one row per repository.
   */
  @ActivateRequestContext
  public Map<String, Instant> lastScheduledBumpAt() {
    return DbRetry.inNewTx(
        "read when the clock last reached each repository",
        () -> {
          List<Object[]> rows =
              getEntityManager()
                  .createQuery(
                      "select bump.repository, max(bump.startedAt) from MtBump bump"
                          + " where bump.trigger = :trigger group by bump.repository",
                      Object[].class)
                  .setParameter("trigger", BumpTrigger.SCHEDULED.name())
                  .getResultList();
          Map<String, Instant> newest = new LinkedHashMap<>();
          for (Object[] row : rows) {
            newest.put((String) row[0], (Instant) row[1]);
          }
          return newest;
        });
  }

  // --- release-request automations -----------------------------------------------------------

  /**
   * One automation row to open — everything {@link #openAutomation} writes, named rather than
   * positional because eleven strings in a row are a transposition waiting to happen.
   *
   * @param repository the repository, as the catalog spells it
   * @param kind the kind's wire name, {@code mt_bump.automation_kind}; also the row's group label
   * @param requestId the release request, or null for a row the targeted door opens
   * @param foldSha the fold this outcome is for, or null for the same row
   * @param previousFoldSha the fold before it, as the trigger named it, or null
   * @param automationOnly whether only the applicable kinds' own paths changed since then, or null
   * @param branch the ref the run writes
   * @param workItem the commit subject's scope, or null
   * @param environment which environment's ci runs it
   * @param trigger FOLD, MANUAL — or SCHEDULED, which nothing here writes
   * @param changes a MaintenanceBump run's changes, frozen here as a bump's are; empty otherwise
   * @param extras the kind's own payload fields, or empty
   * @param status REQUESTED to queue a run; NOTHING_TO_DO or FAILED to record an outcome no run is
   *     needed for (a FRESH plan, a carry-over, a breaker that is still tripped)
   * @param message the sentence, for a row opened already ended
   */
  public record AutomationOpening(
      String repository,
      String kind,
      String requestId,
      String foldSha,
      String previousFoldSha,
      Boolean automationOnly,
      String branch,
      String workItem,
      String environment,
      BumpTrigger trigger,
      List<?> changes,
      Map<String, Object> extras,
      BumpStatus status,
      String message) {}

  /**
   * Opens one release-request automation row, <b>locked per (repository, kind, request)</b>.
   *
   * <p><b>The lock is a transaction-scoped advisory lock</b>, taken before anything is read, because
   * the two callers that race here are ordinary rather than rare: qits-projects posts every fold and
   * then its thirty-second sweep posts the same fold again whenever the first answer was not final,
   * and a re-run press can land between the two. Without it both would read "no row for this fold",
   * both would insert, and one fold would carry two runs.
   *
   * <p>Inside the lock, in order:
   *
   * <ul>
   *   <li><b>{@code exclusive}</b> (the re-run door, the targeted door): an active row of this
   *       (request, kind) — or, with no request, of this (repository, kind, branch) — is a 409, named;
   *   <li><b>otherwise</b> (a fold): a FOLD row for the same (request, kind, fold, branch) is the
   *       answer already, and its id comes back unchanged — the trigger is idempotent per fold;
   *   <li><b>every WAITING row of this (request, kind) on another fold is SUPERSEDED</b>: only the
   *       newest waiting fold is kept, because a run for a fold the request has left answers nothing.
   *       A RUNNING one is left to end; its ending compares its fold and supersedes itself.
   * </ul>
   *
   * @return the row's id — the new one, or the one this fold already had
   */
  @ActivateRequestContext
  public UUID openAutomation(AutomationOpening opening, boolean exclusive, Instant now) {
    String scope =
        opening.requestId() != null ? opening.requestId() : "branch:" + opening.branch();
    return DbRetry.inNewTx(
        "open the " + opening.kind() + " automation of " + opening.repository() + " for " + scope,
        () -> {
          getEntityManager()
              .createNativeQuery(
                  "select count(*) from (select pg_advisory_xact_lock(hashtext(?1))) as locked")
              .setParameter(
                  1, "automation|" + opening.repository() + "|" + opening.kind() + "|" + scope)
              .getSingleResult();
          if (exclusive) {
            MtBump active =
                opening.requestId() != null
                    ? MtBump.find(
                            "mode = ?1 and releaseRequestId = ?2 and automationKind = ?3"
                                + " and status in ?4",
                            BumpMode.AUTOMATION.name(),
                            opening.requestId(),
                            opening.kind(),
                            ACTIVE)
                        .firstResult()
                    : MtBump.find(
                            "mode = ?1 and repository = ?2 and automationKind = ?3 and branch = ?4"
                                + " and status in ?5",
                            BumpMode.AUTOMATION.name(),
                            opening.repository(),
                            opening.kind(),
                            opening.branch(),
                            ACTIVE)
                        .firstResult();
            if (active != null) {
              throw BumpAlreadyActiveException.onBranch(
                  opening.repository(), active.branch, active.id);
            }
          } else if (opening.trigger() == BumpTrigger.FOLD
              && opening.requestId() != null
              && opening.foldSha() != null) {
            MtBump existing =
                MtBump.find(
                        "mode = ?1 and releaseRequestId = ?2 and automationKind = ?3 and foldSha = ?4"
                            + " and branch = ?5 and trigger = ?6",
                        BumpMode.AUTOMATION.name(),
                        opening.requestId(),
                        opening.kind(),
                        opening.foldSha(),
                        opening.branch(),
                        opening.trigger().name())
                    .firstResult();
            if (existing != null) {
              return existing.id;
            }
          }
          if (opening.requestId() != null && opening.foldSha() != null) {
            supersedeWaitingRows(opening.requestId(), opening.kind(), opening.foldSha(), now);
          }
          MtBump row = new MtBump();
          row.id = UUID.randomUUID();
          row.repository = opening.repository();
          row.groupName = opening.kind();
          row.mode = BumpMode.AUTOMATION.name();
          row.automationKind = opening.kind();
          row.branch = opening.branch();
          row.environment = opening.environment();
          row.trigger = opening.trigger().name();
          row.status = opening.status().name();
          row.changes = writeJson(opening.changes());
          row.automationExtras =
              opening.extras() == null || opening.extras().isEmpty()
                  ? null
                  : writeJson(opening.extras());
          row.releaseRequestId = opening.requestId();
          row.foldSha = opening.foldSha();
          row.previousFoldSha = opening.previousFoldSha();
          row.automationOnly = opening.automationOnly();
          row.workItem = opening.workItem();
          row.message = opening.message();
          row.startedAt = now;
          row.finishedAt = opening.status().terminal() ? now : null;
          row.persist();
          getEntityManager().flush();
          return row.id;
        });
  }

  /**
   * Every automation row of one request at one fold, oldest first — what the trigger and the read
   * door answer from.
   */
  @ActivateRequestContext
  public List<MtBump> automations(String requestId, String foldSha) {
    if (requestId == null || foldSha == null) {
      return List.of();
    }
    return DbRetry.inNewTx(
        "read the automations of one fold",
        () ->
            MtBump.<MtBump>find(
                    "mode = ?1 and releaseRequestId = ?2 and foldSha = ?3",
                    Sort.by("startedAt"),
                    BumpMode.AUTOMATION.name(),
                    requestId,
                    foldSha)
                .list());
  }

  /** Every row of one (request, kind), newest first — what the circuit breaker walks. */
  @ActivateRequestContext
  public List<MtBump> automationHistory(String requestId, String kind) {
    if (requestId == null || kind == null) {
      return List.of();
    }
    return DbRetry.inNewTx(
        "read the automation history of one request",
        () ->
            MtBump.<MtBump>find(
                    "mode = ?1 and releaseRequestId = ?2 and automationKind = ?3",
                    Sort.by("startedAt").descending(),
                    BumpMode.AUTOMATION.name(),
                    requestId,
                    kind)
                .list());
  }

  /** The newest row of one (request, kind), whatever became of it. */
  @ActivateRequestContext
  public Optional<MtBump> latestAutomation(String requestId, String kind) {
    return automationHistory(requestId, kind).stream().findFirst();
  }

  /**
   * The fold the newest automation row of one request is for — what the read door answers when it
   * is not told which fold. Empty when the request has none.
   */
  @ActivateRequestContext
  public Optional<MtBump> newestAutomation(String requestId) {
    if (requestId == null) {
      return Optional.empty();
    }
    return DbRetry.inNewTx(
        "read the newest automation of one request",
        () ->
            Optional.ofNullable(
                (MtBump)
                    MtBump.find(
                            "mode = ?1 and releaseRequestId = ?2 and foldSha is not null",
                            Sort.by("startedAt").descending(),
                            BumpMode.AUTOMATION.name(),
                            requestId)
                        .firstResult()));
  }

  /**
   * How many automation runs are RUNNING estate-wide — the cap's question. qits-ci's slots are few,
   * and automations queue as ordinary event runs beside the release requests' own QA.
   */
  @ActivateRequestContext
  public long runningAutomationCount() {
    return DbRetry.inNewTx(
        "count the running automations",
        () ->
            MtBump.count(
                "mode = ?1 and status = ?2",
                BumpMode.AUTOMATION.name(),
                BumpStatus.RUNNING.name()));
  }

  /**
   * The RUNNING automation writing one (repository, kind, branch), if there is one — which is what
   * "one active run per (request, kind)" means for a kind's own branch, and per source branch for a
   * kind that writes the request's.
   */
  @ActivateRequestContext
  public Optional<MtBump> runningAutomation(String repository, String kind, String branch) {
    return DbRetry.inNewTx(
        "read the running automation of one branch",
        () ->
            Optional.ofNullable(
                (MtBump)
                    MtBump.find(
                            "mode = ?1 and repository = ?2 and automationKind = ?3 and branch = ?4"
                                + " and status = ?5",
                            BumpMode.AUTOMATION.name(),
                            repository,
                            kind,
                            branch,
                            BumpStatus.RUNNING.name())
                        .firstResult()));
  }

  /**
   * Every automation row that wrote one branch of one repository, newest first — what the
   * automation-branch sweep reads to see whether the branch is still being worked on and how long
   * ago this service last touched it.
   */
  @ActivateRequestContext
  public List<MtBump> automationsOnBranch(String repository, String branch) {
    return DbRetry.inNewTx(
        "read the automations of one branch",
        () ->
            MtBump.<MtBump>find(
                    "mode = ?1 and repository = ?2 and branch = ?3",
                    Sort.by("startedAt").descending(),
                    BumpMode.AUTOMATION.name(),
                    repository,
                    branch)
                .list());
  }

  /** Every automation row still waiting for a run, oldest first — what an ending dispatches next. */
  @ActivateRequestContext
  public List<MtBump> waitingAutomations() {
    return DbRetry.inNewTx(
        "read the waiting automations",
        () ->
            MtBump.<MtBump>find(
                    "mode = ?1 and status = ?2",
                    Sort.by("startedAt"),
                    BumpMode.AUTOMATION.name(),
                    BumpStatus.REQUESTED.name())
                .list());
  }

  /**
   * Supersedes every WAITING row of one (request, kind) whose fold is not {@code exceptFold}: only
   * the newest waiting fold is kept. A RUNNING row is not touched — see {@link #openAutomation}.
   *
   * @return how many rows were superseded
   */
  @ActivateRequestContext
  public int supersedeWaiting(String requestId, String kind, String exceptFold) {
    return DbRetry.inNewTx(
        "supersede the waiting automations of one request",
        () -> {
          int count = supersedeWaitingRows(requestId, kind, exceptFold, Instant.now());
          getEntityManager().flush();
          return count;
        });
  }

  private static int supersedeWaitingRows(
      String requestId, String kind, String exceptFold, Instant now) {
    List<MtBump> waiting =
        MtBump.<MtBump>find(
                "mode = ?1 and releaseRequestId = ?2 and automationKind = ?3 and status = ?4"
                    + " and (foldSha is null or foldSha <> ?5)",
                BumpMode.AUTOMATION.name(),
                requestId,
                kind,
                BumpStatus.REQUESTED.name(),
                exceptFold)
            .list();
    for (MtBump row : waiting) {
      row.status = BumpStatus.SUPERSEDED.name();
      row.finishedAt = now;
      row.message =
          "superseded before it ran: the release request moved on to fold " + abbreviate(exceptFold);
    }
    return waiting.size();
  }

  private static String abbreviate(String sha) {
    return sha == null || sha.length() <= 12 ? sha : sha.substring(0, 12);
  }

  private static final List<String> ACTIVE =
      List.of(BumpStatus.REQUESTED.name(), BumpStatus.RUNNING.name());

  // --- the dispatch window --------------------------------------------------------------------

  /**
   * When the open dispatch window ends, or empty when there is no window.
   *
   * <p><b>A read per tick rather than a field read.</b> Outside a window this is the whole cost of
   * the dispatch schedule — one primary-key lookup of one row every fifteen seconds, four thousand
   * a day, which is less than the poller beside it does in an hour. What it buys is the window
   * surviving the redeploy that this service's own bump causes; see {@link MtBumpWindow}.
   */
  @ActivateRequestContext
  public Optional<Instant> bumpWindow() {
    return bumpWindowRow().map(row -> row.closesAt);
  }

  /** The same row whole, for the API — {@code openedAt} is for people rather than for the gate. */
  @ActivateRequestContext
  public Optional<MtBumpWindow> bumpWindowRow() {
    return DbRetry.inNewTx(
        "read the bump dispatch window",
        () -> Optional.ofNullable((MtBumpWindow) MtBumpWindow.findById(MtBumpWindow.INTERNAL)));
  }

  /** Opens the window, or replaces the one that is open. An upsert on the singleton key. */
  @ActivateRequestContext
  public void openBumpWindow(Instant now, Instant closes) {
    DbRetry.runInNewTx(
        "open the bump dispatch window",
        () -> {
          MtBumpWindow row = MtBumpWindow.findById(MtBumpWindow.INTERNAL);
          boolean fresh = row == null;
          if (fresh) {
            row = new MtBumpWindow();
            row.id = MtBumpWindow.INTERNAL;
          }
          row.openedAt = now;
          row.closesAt = closes;
          if (fresh) {
            row.persist();
          }
          getEntityManager().flush();
        });
  }

  /**
   * Closes it. Deleting the row is what "no window" means, and calling this with none open is not
   * an error — every one of the dispatcher's closing conditions may be reached twice.
   */
  @ActivateRequestContext
  public void closeBumpWindow() {
    DbRetry.runInNewTx(
        "close the bump dispatch window",
        () -> {
          MtBumpWindow.deleteById(MtBumpWindow.INTERNAL);
          getEntityManager().flush();
        });
  }

  /**
   * The GROUP bump holding one group's branch, if there is one.
   *
   * <p>The mode term is not a tidiness: a targeted row carries a sentinel in {@code group_name}, and
   * without the term a repository that declared a group spelled like the sentinel would find its
   * nightly bump held up by somebody else's workspace branch.
   */
  private static MtBump activeBumpRow(String repository, String group) {
    return MtBump.find(
            "repository = ?1 and groupName = ?2 and mode = ?3 and status in ?4",
            repository,
            group,
            BumpMode.GROUP.name(),
            List.of(BumpStatus.REQUESTED.name(), BumpStatus.RUNNING.name()))
        .firstResult();
  }

  // --- the sbom graph -------------------------------------------------------------------------

  /**
   * The row an announced release leaves behind, PENDING, for the ingest to pick up.
   *
   * <p><b>The row IS the outbox and this method is deliberately not an upsert of everything.</b> A
   * coordinate already known is LEFT ALONE — its status, its error and its graph are the reading of
   * an immutable released version, and a redelivered frame is not new evidence about it. Without
   * that, every catch-up sweep would re-queue every release it re-offered and the ingest would ask
   * qits-artifacts the same question about the same bytes for ever.
   *
   * @return the row's id, whether it was created here or was already there
   */
  @ActivateRequestContext
  public UUID upsertArtifact(
      Ecosystem ecosystem,
      String name,
      String version,
      String repository,
      Instant occurredAt) {
    return upsertArtifact(
        ecosystem.wireName(), name, version, repository, occurredAt, ReleaseOrigin.NONE);
  }

  /**
   * The same, with where the release came from — the project, the release.yml section and the run
   * the SBOM check reads (V14).
   *
   * <p><b>Those three are the one exception to "a known coordinate is left alone", and only ever
   * from null.</b> They are facts about the release, not readings of its document, so a redelivered
   * frame that names one the row does not hold yet fills it in; a value already stored is never
   * overwritten.
   */
  @ActivateRequestContext
  public UUID upsertArtifact(
      Ecosystem ecosystem,
      String name,
      String version,
      String repository,
      Instant occurredAt,
      ReleaseOrigin origin) {
    return upsertArtifact(ecosystem.wireName(), name, version, repository, occurredAt, origin);
  }

  /**
   * <b>The row a released DAEMON BINARY leaves behind — PENDING, an outbox like any other.</b>
   *
   * <p>A sibling of the {@link Ecosystem} overload of {@code upsertArtifact}, sharing its write,
   * and separate only because that one takes an enum: the ecosystem column takes {@link
   * Ecosystem#DAEMON_WIRE_NAME} as the literal string — it is not an {@link Ecosystem} and must not
   * become one, and {@code varchar(32)} with no check constraint has always been able to hold it. Everything else is the same rule: a
   * coordinate already known is left alone, a new one is written PENDING, and the caller queues the
   * fetch ({@code SbomIngestService.announcedDaemon}).
   *
   * <p>The row does two jobs. It is what the pin source derives a keep for the binary from (see
   * {@code control/CarriedDaemons}), and it is the outbox for the binary's bill of materials, which
   * qits-artifacts stores under the {@code daemon} segment of the SBOM route and {@code SbomClient}
   * addresses by this stored word.
   *
   * <p><b>It used to be written terminal FAILED</b>, because the SBOM route was then addressed
   * through an {@link Ecosystem} and a daemon could not be named. That is gone, and V13 re-queues
   * the rows it wrote.
   *
   * @return the row's id, whether it was created here or was already there
   */
  @ActivateRequestContext
  public UUID upsertDaemonArtifact(
      String name, String version, String repository, Instant occurredAt) {
    return upsertDaemonArtifact(name, version, repository, occurredAt, ReleaseOrigin.NONE);
  }

  /** The same, with where the release came from. See the {@link ReleaseOrigin} overload above. */
  @ActivateRequestContext
  public UUID upsertDaemonArtifact(
      String name, String version, String repository, Instant occurredAt, ReleaseOrigin origin) {
    return upsertArtifact(
        Ecosystem.DAEMON_WIRE_NAME, name, version, repository, occurredAt, origin);
  }

  private UUID upsertArtifact(
      String type,
      String name,
      String version,
      String repository,
      Instant occurredAt,
      ReleaseOrigin origin) {
    ReleaseOrigin from = origin == null ? ReleaseOrigin.NONE : origin;
    return DbRetry.inNewTx(
        "record the released artifact " + name + " " + version,
        () -> {
          MtArtifact row = artifactRow(type, name, version);
          if (row != null) {
            // Left alone — except the release's own origin, filled in where it is still null.
            boolean filled = false;
            if (row.projectId == null && from.projectId() != null) {
              row.projectId = from.projectId();
              filled = true;
            }
            if (row.section == null && from.section() != null) {
              row.section = from.section();
              filled = true;
            }
            if (row.runId == null && from.runId() != null) {
              row.runId = from.runId();
              filled = true;
            }
            if (filled) {
              getEntityManager().flush();
            }
            return row.id;
          }
          row = new MtArtifact();
          row.id = UUID.randomUUID();
          row.ecosystem = type;
          row.name = name;
          row.version = version;
          row.repository = repository;
          row.occurredAt = occurredAt;
          row.sbomStatus = SbomStatus.PENDING.name();
          row.projectId = from.projectId();
          row.section = from.section();
          row.runId = from.runId();
          row.persist();
          getEntityManager().flush();
          return row.id;
        });
  }

  /**
   * The manual backfill's write: create the row, or put an existing one back to PENDING.
   *
   * <p><b>The by-hand way to move a MISSING or FAILED row</b>; the other is the daily SBOM check,
   * which re-reads both. A person who knows a document has since been stored and will not wait for
   * the check asks for it by hand, and this is what that ask writes. The graph is left standing until the re-ingest replaces it: a row
   * with no components for a minute would read as an artifact that contains nothing.
   */
  @ActivateRequestContext
  public UUID requeueArtifact(
      Ecosystem ecosystem, String name, String version, String repository, Instant now) {
    return requeueArtifact(ecosystem.wireName(), name, version, repository, now);
  }

  /**
   * The same, keyed by the artifact's stored wire type — {@code maven}, {@code npm}, {@code docker}
   * or {@code daemon} — which is how a daemon row, not being an {@link Ecosystem}, is re-queued.
   */
  @ActivateRequestContext
  public UUID requeueArtifact(
      String type, String name, String version, String repository, Instant now) {
    return DbRetry.inNewTx(
        "re-queue the sbom of " + name + " " + version,
        () -> {
          MtArtifact row = artifactRow(type, name, version);
          if (row == null) {
            row = new MtArtifact();
            row.id = UUID.randomUUID();
            row.ecosystem = type;
            row.name = name;
            row.version = version;
            row.repository = repository;
            // NOW, because nobody announced this one: a manual backfill of a release from months
            // ago has no frame to take a moment from, and inventing one would put it in the middle
            // of the ordering the dependents view is built on.
            row.occurredAt = now;
            row.sbomStatus = SbomStatus.PENDING.name();
            row.persist();
          } else {
            row.sbomStatus = SbomStatus.PENDING.name();
            row.sbomError = null;
            if (repository != null && !repository.isBlank()) {
              row.repository = repository;
            }
          }
          getEntityManager().flush();
          return row.id;
        });
  }

  /**
   * The whole of one artifact's graph, replaced in ONE transaction, and the row marked INGESTED.
   *
   * <p><b>Wholesale, for {@code replaceInventory}'s reason.</b> A document is one reading of one
   * immutable release; merging a second reading into the first would leave components from a parse
   * this build has since corrected. And a transaction that committed the delete and failed the
   * insert would leave an INGESTED artifact containing nothing, which is indistinguishable from a
   * release with no dependencies.
   *
   * <p>The edges are given by INDEX into {@code components}, with {@code -1} for the root, because
   * the caller cannot know the ids until they are minted here.
   *
   * @param components what the document listed, in document order
   * @param edges who pulled in whom, by position in that list
   */
  @ActivateRequestContext
  public void replaceGraph(
      UUID artifactId,
      List<ParsedSbom.Component> components,
      List<ParsedSbom.Edge> edges,
      Instant now) {
    DbRetry.runInNewTx(
        "replace the sbom graph of " + artifactId,
        () -> {
          MtArtifact row = MtArtifact.findById(artifactId);
          if (row == null) {
            return;
          }
          // Edges first: they refer to the components, and the delete order is the insert order
          // reversed for the same reason a foreign key exists at all.
          MtArtifactEdge.delete("artifactId", artifactId);
          MtArtifactComponent.delete("artifactId", artifactId);

          List<UUID> ids = new java.util.ArrayList<>(components.size());
          for (ParsedSbom.Component component : components) {
            MtArtifactComponent stored = new MtArtifactComponent();
            // EVERY FIELD BEFORE THE PERSIST — the same rock replaceInventory names: a query below
            // makes Hibernate flush, and a row whose not-null columns are still unset fails the
            // flush rather than the insert.
            stored.id = UUID.randomUUID();
            stored.artifactId = artifactId;
            stored.bomRef = component.bomRef();
            stored.purl = component.purl();
            stored.ecosystem =
                component.ecosystem() == null ? null : component.ecosystem().wireName();
            stored.name = component.name();
            stored.version = component.version();
            stored.direct = component.direct();
            stored.persist();
            ids.add(stored.id);
          }
          for (ParsedSbom.Edge edge : edges) {
            if (edge.child() < 0 || edge.child() >= ids.size() || edge.parent() >= ids.size()) {
              continue;
            }
            MtArtifactEdge stored = new MtArtifactEdge();
            stored.id = UUID.randomUUID();
            stored.artifactId = artifactId;
            // -1 is the root, which is the artifact row itself and has no component id.
            stored.parentComponentId = edge.parent() < 0 ? null : ids.get(edge.parent());
            stored.childComponentId = ids.get(edge.child());
            stored.persist();
          }

          row.sbomStatus = SbomStatus.INGESTED.name();
          row.sbomError = null;
          row.ingestedAt = now;
          getEntityManager().flush();
        });
  }

  /**
   * qits-artifacts holds no document for this coordinate. Not terminal: the daily SBOM check
   * re-reads every MISSING row still in the store ({@code SbomIngestService#recheck}), so a document
   * backfilled since is ingested, and a manual {@link #requeueArtifact} asks again too.
   */
  @ActivateRequestContext
  public void markArtifactMissing(UUID artifactId) {
    markArtifact(artifactId, SbomStatus.MISSING, null);
  }

  /** The document could not be read, and the sentence is what a person decides on. */
  @ActivateRequestContext
  public void markArtifactFailed(UUID artifactId, String error) {
    markArtifact(artifactId, SbomStatus.FAILED, error);
  }

  private void markArtifact(UUID artifactId, SbomStatus status, String error) {
    DbRetry.runInNewTx(
        "mark the sbom of " + artifactId + " " + status,
        () -> {
          MtArtifact row = MtArtifact.findById(artifactId);
          if (row == null) {
            return;
          }
          row.sbomStatus = status.name();
          row.sbomError = error;
          getEntityManager().flush();
        });
  }

  /** Every artifact still waiting for its document — what the sweep re-queues. */
  @ActivateRequestContext
  public List<MtArtifact> pendingArtifacts() {
    return DbRetry.inNewTx(
        "read the artifacts still owed an sbom",
        () ->
            MtArtifact.find(
                    "sbomStatus = ?1", Sort.by("occurredAt"), SbomStatus.PENDING.name())
                .list());
  }

  @ActivateRequestContext
  public Optional<MtArtifact> artifact(UUID id) {
    return DbRetry.inNewTx(
        "read one artifact row", () -> Optional.ofNullable(MtArtifact.findById(id)));
  }

  @ActivateRequestContext
  public Optional<MtArtifact> artifact(Ecosystem ecosystem, String name, String version) {
    return DbRetry.inNewTx(
        "read one artifact by coordinate",
        () -> Optional.ofNullable(artifactRow(ecosystem, name, version)));
  }

  private static MtArtifact artifactRow(Ecosystem ecosystem, String name, String version) {
    return artifactRow(ecosystem.wireName(), name, version);
  }

  private static MtArtifact artifactRow(String type, String name, String version) {
    return MtArtifact.find("ecosystem = ?1 and name = ?2 and version = ?3", type, name, version)
        .firstResult();
  }

  /**
   * One embedding of one dependency: the artifact that ships it, and the component row that says
   * so.
   *
   * @param artifact the released artifact whose document listed it
   * @param component what the document said about it
   */
  public record Dependent(MtArtifact artifact, MtArtifactComponent component) {}

  /**
   * <b>WHO SHIPS A COPY OF THIS.</b> Every ingested artifact whose bill of materials names the
   * dependency, direct or transitive.
   *
   * <p><b>Read in two steps rather than as one join, deliberately.</b> {@code
   * mt_artifact_component} carries a plain uuid rather than a mapped association — the same flat
   * shape every other table here uses — so the join would have to be native SQL. Two indexed reads
   * (the {@code (ecosystem, name)} index, then the artifacts by id) answer the same question, and
   * the set is bounded by how many versions of how many libraries embed one dependency.
   *
   * @param newestPerArtifactOnly the DEFAULT view: one row per dependent artifact NAME, the newest
   *     released version of it. A library released fifty times would otherwise answer this question
   *     fifty times over and bury the one fact anybody wanted — which of our things still ship it.
   */
  @ActivateRequestContext
  public List<Dependent> dependents(
      Ecosystem ecosystem, String name, boolean newestPerArtifactOnly) {
    return DbRetry.inNewTx(
        "read the dependents of " + name,
        () -> {
          // BOTH reads inside the one transaction, which they wanted anyway: the second is keyed by
          // what the first answered.
          List<MtArtifactComponent> components =
              MtArtifactComponent.find(
                      "ecosystem = ?1 and name = ?2", ecosystem.wireName(), name)
                  .list();
          if (components.isEmpty()) {
            return List.<Dependent>of();
          }
          List<UUID> artifactIds =
              components.stream().map(component -> component.artifactId).distinct().toList();
          Map<UUID, MtArtifact> artifacts = new LinkedHashMap<>();
          for (MtArtifact row : MtArtifact.<MtArtifact>find("id in ?1", artifactIds).list()) {
            artifacts.put(row.id, row);
          }

          List<Dependent> found = new java.util.ArrayList<>();
          for (MtArtifactComponent component : components) {
            MtArtifact artifact = artifacts.get(component.artifactId);
            if (artifact != null) {
              found.add(new Dependent(artifact, component));
            }
          }
          // The dependent name, then newest first — the order the API serves and the order the
          // newest-per-name filter below reads.
          found.sort(dependentOrder());
          if (!newestPerArtifactOnly) {
            return List.copyOf(found);
          }
          List<Dependent> newest = new java.util.ArrayList<>();
          String previous = null;
          for (Dependent dependent : found) {
            String key = dependent.artifact().ecosystem + " " + dependent.artifact().name;
            if (!key.equals(previous)) {
              newest.add(dependent);
              previous = key;
            }
          }
          return List.copyOf(newest);
        });
  }

  /** Dependent name ascending, then the newest release of it first. */
  private static java.util.Comparator<Dependent> dependentOrder() {
    return java.util.Comparator.comparing(
            (Dependent dependent) -> dependent.artifact().ecosystem + " " + dependent.artifact().name)
        .thenComparing(
            dependent -> dependent.artifact().occurredAt,
            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
        .thenComparing(dependent -> dependent.artifact().version);
  }

  /**
   * Every distinct {@code (ecosystem, name)} this platform has released, newest row first per
   * pair.
   *
   * <p>The listing the internal-libs page is built from. Distinct pairs of {@code mt_artifact}
   * rather than a union with the component table: a library nobody has released has nothing to say
   * about what it contains, and a component that is not itself an artifact of ours is somebody
   * else's package, which the dependency page already answers for.
   */
  @ActivateRequestContext
  public List<MtArtifact> newestArtifactPerName() {
    return DbRetry.inNewTx(
        "read the newest artifact per name",
        () -> {
          List<MtArtifact> all =
              MtArtifact.findAll(
                      Sort.by("ecosystem").and("name").and("occurredAt", Sort.Direction.Descending))
                  .list();
          List<MtArtifact> newest = new java.util.ArrayList<>();
          String previous = null;
          for (MtArtifact row : all) {
            String key = row.ecosystem + " " + row.name;
            if (!key.equals(previous)) {
              newest.add(row);
              previous = key;
            }
          }
          return List.copyOf(newest);
        });
  }

  /**
   * Every artifact one repository has released, newest first.
   *
   * <p>A pure delegate — the transaction is the one the overload below opens, and opening a second
   * around this call would be two boundaries for one read.
   */
  @ActivateRequestContext
  public List<MtArtifact> artifactsOfRepository(String repository) {
    return artifactsOfRepository(List.of(repository));
  }

  /**
   * The same listing for a repository under EVERY spelling its rows may carry.
   *
   * <p>{@code mt_artifact.repository} holds what the release announced, and until this deploy that
   * was qits-projects' row id rather than the name — so one repository's history is split across
   * two strings and a name-only read answers only the half written since. The caller supplies both
   * (see {@code ArtifactGraph}'s translation), and this stays a single indexed read.
   */
  @ActivateRequestContext
  public List<MtArtifact> artifactsOfRepository(List<String> spellings) {
    if (spellings == null || spellings.isEmpty()) {
      return List.of();
    }
    return DbRetry.inNewTx(
        "read the artifacts of one repository",
        () ->
            MtArtifact.find(
                    "repository in ?1",
                    Sort.by("ecosystem").and("name").and("occurredAt", Sort.Direction.Descending),
                    spellings)
                .list());
  }

  /**
   * What ONE REPOSITORY published at a named set of versions — every artifact of those releases.
   *
   * <p>The narrow read behind {@code ArtifactGraph.imagesReleasedWith}, and it is narrow on purpose:
   * the caller is the GC's pin source, which asks about the handful of versions somebody's manifest
   * currently pins rather than about a repository's whole history. {@link
   * #artifactsOfRepository(List)} would answer the same question by reading every release that
   * repository ever cut, once per repository, on every collection.
   *
   * <p>Both terms are {@code in} lists for the reason that overload spells out: the repository is
   * carried under every spelling its rows may hold, and the versions are the several a set of pins
   * named. Neither is ever empty here — an empty {@code in} answers nothing and costs a round trip
   * to find that out.
   */
  @ActivateRequestContext
  public List<MtArtifact> artifactsOfReleases(
      List<String> spellings, Collection<String> versions) {
    if (spellings == null || spellings.isEmpty() || versions == null || versions.isEmpty()) {
      return List.of();
    }
    return DbRetry.inNewTx(
        "read the artifacts of named releases of one repository",
        () ->
            MtArtifact.find(
                    "repository in ?1 and version in ?2",
                    Sort.by("ecosystem").and("name"),
                    spellings,
                    List.copyOf(versions))
                .list());
  }

  /** Everything one artifact's document listed. */
  @ActivateRequestContext
  public List<MtArtifactComponent> components(UUID artifactId) {
    return DbRetry.inNewTx(
        "read the components of one artifact",
        () -> MtArtifactComponent.find("artifactId = ?1", Sort.by("name"), artifactId).list());
  }

  /** One artifact's adjacency, for walking "what pulled this in". */
  @ActivateRequestContext
  public List<MtArtifactEdge> edges(UUID artifactId) {
    return DbRetry.inNewTx(
        "read the edges of one artifact",
        () -> MtArtifactEdge.find("artifactId = ?1", artifactId).list());
  }

  /** One artifact row by its stored wire type — how a {@code daemon} row is looked up. */
  @ActivateRequestContext
  public Optional<MtArtifact> artifact(String type, String name, String version) {
    return DbRetry.inNewTx(
        "read one artifact by type and coordinate",
        () -> Optional.ofNullable(artifactRow(type, name, version)));
  }

  // --- the daily sbom check (V14) ----------------------------------------------------------------

  /**
   * Every row the SBOM check might count: one of the given types, from the {@code artifacts}
   * section (or from before sections existed), and not INGESTED. The pending grace and the presence
   * probe are the caller's — the first is a clock, the second a peer.
   *
   * <p><b>No cut-off, on purpose.</b> Every released version still in the store counts, however
   * old. What retires one is an INGESTED backfill — the check re-reads every MISSING and FAILED
   * row's document before it counts it — or the GC collecting it, which it does for every type
   * once nothing on any main branch still pins the version.
   */
  @ActivateRequestContext
  public List<MtArtifact> sbomCheckCandidates(Collection<String> types) {
    if (types == null || types.isEmpty()) {
      return List.of();
    }
    return DbRetry.inNewTx(
        "read the artifacts the sbom check may count",
        () ->
            MtArtifact.find(
                    "ecosystem in ?1 and (section is null or section = ?2) and sbomStatus in ?3",
                    Sort.by("ecosystem").and("name").and("occurredAt"),
                    List.copyOf(types),
                    "artifacts",
                    List.of(
                        SbomStatus.MISSING.name(),
                        SbomStatus.FAILED.name(),
                        SbomStatus.PENDING.name()))
                .list());
  }

  /**
   * Writes a project resolved from the catalog onto a row that carries none — once, and never over
   * a project the release itself named.
   */
  @ActivateRequestContext
  public void setArtifactProject(UUID artifactId, String projectId) {
    DbRetry.runInNewTx(
        "resolve the project of artifact " + artifactId,
        () -> {
          MtArtifact row = MtArtifact.findById(artifactId);
          if (row != null && row.projectId == null) {
            row.projectId = projectId;
            getEntityManager().flush();
          }
        });
  }

  /** Stores one check run and its report. */
  @ActivateRequestContext
  public void recordSbomCheckRun(UUID id, Instant ranAt, boolean filed, String report) {
    DbRetry.runInNewTx(
        "record an sbom check run",
        () -> {
          MtSbomCheckRun row = new MtSbomCheckRun();
          row.id = id;
          row.ranAt = ranAt;
          row.filed = filed;
          row.report = report;
          row.persist();
          getEntityManager().flush();
        });
  }

  /** The newest check run, if any has run. */
  @ActivateRequestContext
  public Optional<MtSbomCheckRun> latestSbomCheckRun() {
    return DbRetry.inNewTx(
        "read the newest sbom check run",
        () ->
            MtSbomCheckRun.<MtSbomCheckRun>findAll(Sort.by("ranAt", Sort.Direction.Descending))
                .firstResultOptional());
  }

  /** The current ticket row of one {@code (project, ecosystem, name)}, open or closed. */
  @ActivateRequestContext
  public Optional<MtSbomTicket> sbomTicket(String projectId, String ecosystem, String name) {
    return DbRetry.inNewTx(
        "read the sbom ticket of " + name,
        () ->
            MtSbomTicket.<MtSbomTicket>find(
                    "projectId = ?1 and ecosystem = ?2 and name = ?3", projectId, ecosystem, name)
                .firstResultOptional());
  }

  /** Every ticket row the check has not finished with. */
  @ActivateRequestContext
  public List<MtSbomTicket> openSbomTickets() {
    return DbRetry.inNewTx(
        "read the open sbom tickets",
        () ->
            MtSbomTicket.<MtSbomTicket>find(
                    "closedAt is null", Sort.by("projectId").and("ecosystem").and("name"))
                .list());
  }

  /** The versions reported on one ticket row, oldest report first. */
  @ActivateRequestContext
  public List<MtSbomTicketVersion> sbomTicketVersions(UUID ticketRow) {
    return DbRetry.inNewTx(
        "read the versions of one sbom ticket",
        () ->
            MtSbomTicketVersion.<MtSbomTicketVersion>find(
                    "ticketRow = ?1", Sort.by("reportedAt").and("version"), ticketRow)
                .list());
  }

  /**
   * The ticket a group is now reported on: a new row, or the existing one REPLACED — new ticket id
   * and slug, reopened, and its versions deleted, since they were reported on a ticket that is not
   * this one. One transaction, so the unique key never names a ticket half-swapped.
   *
   * @return the row's id
   */
  @ActivateRequestContext
  public UUID openSbomTicket(
      String projectId,
      String ecosystem,
      String name,
      UUID ticketId,
      String ticketSlug,
      Instant now) {
    return DbRetry.inNewTx(
        "open the sbom ticket of " + name,
        () -> {
          MtSbomTicket row =
              MtSbomTicket.<MtSbomTicket>find(
                      "projectId = ?1 and ecosystem = ?2 and name = ?3",
                      projectId,
                      ecosystem,
                      name)
                  .firstResult();
          if (row == null) {
            row = new MtSbomTicket();
            row.id = UUID.randomUUID();
            row.projectId = projectId;
            row.ecosystem = ecosystem;
            row.name = name;
            row.ticketId = ticketId;
            row.ticketSlug = ticketSlug;
            row.openedAt = now;
            row.persist();
          } else {
            MtSbomTicketVersion.delete("ticketRow", row.id);
            row.ticketId = ticketId;
            row.ticketSlug = ticketSlug;
            row.openedAt = now;
            row.closedAt = null;
          }
          getEntityManager().flush();
          return row.id;
        });
  }

  /** Records that one version was reported on a ticket row. A second record of it is a no-op. */
  @ActivateRequestContext
  public void recordSbomTicketVersion(UUID ticketRow, String version, String reason, Instant now) {
    DbRetry.runInNewTx(
        "record a version on an sbom ticket",
        () -> {
          if (MtSbomTicketVersion.findById(new MtSbomTicketVersion.Key(ticketRow, version))
              != null) {
            return;
          }
          MtSbomTicketVersion row = new MtSbomTicketVersion();
          row.ticketRow = ticketRow;
          row.version = version;
          row.reason = reason;
          row.reportedAt = now;
          row.persist();
          getEntityManager().flush();
        });
  }

  /** The check is done with this ticket row. */
  @ActivateRequestContext
  public void closeSbomTicket(UUID ticketRow, Instant now) {
    DbRetry.runInNewTx(
        "close an sbom ticket row",
        () -> {
          MtSbomTicket row = MtSbomTicket.findById(ticketRow);
          if (row != null) {
            row.closedAt = now;
            getEntityManager().flush();
          }
        });
  }

  // --- the release ledger -----------------------------------------------------------------------

  /**
   * One dependency a released tree declared, on its way into {@code mt_release_pin}.
   *
   * @param version a version for the three registry ecosystems, and a COMMIT SHA for a gitlink
   */
  public record ReleasePin(Ecosystem ecosystem, String name, String version) {}

  /**
   * One release and one thing its tree declared — the release-pin half of an adoption's evidence.
   *
   * @param release the consumer's own release, which is what an adoption is REPORTED as
   * @param pin what that release's tree pinned the coordinate at, which is what PROVES it
   */
  public record ReleaseCarrier(MtRelease release, MtReleasePin pin) {}

  /**
   * <b>ONE RELEASE AND EVERYTHING ITS TREE DECLARED, replaced in ONE transaction.</b>
   *
   * <p><b>Wholesale, for {@link #replaceGraph}'s reason and then one more.</b> A tag is immutable,
   * so a second reading of one is a correction of the first and never a second fact; merging would
   * leave pins from a parse this build has since changed. And a transaction that committed the
   * delete and failed the insert would leave a release that declared nothing, which reads exactly
   * like a repository that pins nothing.
   *
   * <p><b>Idempotent by construction, which is what both writers need.</b> The bus redelivers a
   * frame whenever a claim rolls back and the backfill re-runs on every boot; the row is found by
   * {@code (repository, version)} and rewritten in place, so N attempts converge on one answer.
   * {@code sha} and {@code occurred_at} are replaced too — a re-record is a better reading, not an
   * older one.
   *
   * @return the row's id, whether it was created here or was already there
   */
  @ActivateRequestContext
  public UUID recordRelease(
      String repository,
      String version,
      String sha,
      Instant occurredAt,
      List<ReleasePin> pins) {
    return DbRetry.inNewTx(
        "record the release " + repository + " " + version,
        () -> {
          MtRelease row =
              MtRelease.find("repository = ?1 and version = ?2", repository, version).firstResult();
          boolean fresh = row == null;
          if (fresh) {
            row = new MtRelease();
            row.id = UUID.randomUUID();
            row.repository = repository;
            row.version = version;
          }
          // EVERY FIELD BEFORE THE PERSIST — the rock replaceInventory names: the delete below
          // makes Hibernate flush, and a row whose not-null columns are still unset fails the flush
          // rather than the insert, naming a column nobody was writing at the time.
          row.sha = sha;
          row.occurredAt = occurredAt;
          if (fresh) {
            row.persist();
          }
          MtReleasePin.delete("releaseId", row.id);
          for (ReleasePin pin : pins) {
            if (pin.ecosystem() == null || pin.name() == null || pin.name().isBlank()) {
              continue;
            }
            MtReleasePin stored = new MtReleasePin();
            stored.id = UUID.randomUUID();
            stored.releaseId = row.id;
            stored.ecosystem = pin.ecosystem().wireName();
            stored.name = pin.name();
            stored.version = pin.version();
            stored.persist();
          }
          getEntityManager().flush();
          return row.id;
        });
  }

  /** Whether the ledger already holds this exact release. The backfill's "is there work here". */
  @ActivateRequestContext
  public boolean releaseRecorded(String repository, String version) {
    if (repository == null || version == null) {
      return false;
    }
    return DbRetry.inNewTx(
        "check whether one release is in the ledger",
        () -> MtRelease.count("repository = ?1 and version = ?2", repository, version) > 0);
  }

  /**
   * Every release of one repository, oldest first.
   *
   * <p>The read that resolves a GITLINK pin: an embedder pins a submodule at a COMMIT, so "which
   * release of the frontend is this service carrying" is this listing matched on {@code sha}
   * through {@code latest/GitlinkSha.same}. One indexed read per gitlink hop.
   */
  @ActivateRequestContext
  public List<MtRelease> releasesOf(String repository) {
    if (repository == null || repository.isBlank()) {
      return List.of();
    }
    return DbRetry.inNewTx(
        "read the releases of one repository",
        () -> MtRelease.find("repository = ?1", Sort.by("occurredAt"), repository).list());
  }

  /**
   * <b>WHICH RELEASES DECLARED THIS COORDINATE</b> — the release-pin evidence, beside {@link
   * #dependents} SBOM one.
   *
   * <p><b>Read in two steps rather than as one join, and the parallel with {@code dependents} is
   * exact.</b> {@code mt_release_pin.release_id} is a plain uuid rather than a mapped association —
   * the flat shape this schema takes everywhere outside V3's graph — so a join would have to be
   * native SQL. The {@code (ecosystem, name)} index answers the first read and the releases by id
   * answer the second, both inside the one transaction because the second is keyed by what the
   * first said.
   *
   * <p><b>Every release, never a newest-per-repository fold.</b> {@code dependents} offers that
   * choice because its default view is a page's; here the question is always "when did they FIRST
   * declare it", and a fold to the newest buries precisely that.
   */
  @ActivateRequestContext
  public List<ReleaseCarrier> releasePinCarriers(Ecosystem ecosystem, String name) {
    if (ecosystem == null || name == null || name.isBlank()) {
      return List.of();
    }
    return DbRetry.inNewTx(
        "read the releases declaring " + name,
        () -> {
          List<MtReleasePin> pins =
              MtReleasePin.<MtReleasePin>find(
                      "ecosystem = ?1 and name = ?2", ecosystem.wireName(), name)
                  .list();
          if (pins.isEmpty()) {
            return List.<ReleaseCarrier>of();
          }
          List<UUID> releaseIds = pins.stream().map(pin -> pin.releaseId).distinct().toList();
          Map<UUID, MtRelease> releases = new LinkedHashMap<>();
          for (MtRelease row : MtRelease.<MtRelease>find("id in ?1", releaseIds).list()) {
            releases.put(row.id, row);
          }
          List<ReleaseCarrier> found = new java.util.ArrayList<>();
          for (MtReleasePin pin : pins) {
            MtRelease release = releases.get(pin.releaseId);
            if (release != null) {
              found.add(new ReleaseCarrier(release, pin));
            }
          }
          return List.copyOf(found);
        });
  }

  // --- json ---------------------------------------------------------------------------------
  //
  // THE THREE BELOW ARE THE ONLY METHODS HERE THAT OWN NO TRANSACTION, and they are static column
  // codecs rather than store methods: they touch no datasource, so there is nothing for them to
  // enlist. Everything that does reach the database is wrapped, without exception.

  /** A list into the text column that holds it. Two columns are json and neither is queried by. */
  static String writeJson(Object value) {
    try {
      return JSON.writeValueAsString(value == null ? List.of() : value);
    } catch (Exception e) {
      throw new IllegalStateException("could not write a json column", e);
    }
  }

  /** A json array column back as a list of strings, empty when it does not read. */
  public static List<String> readStrings(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return List.of(JSON.readValue(json, String[].class));
    } catch (Exception e) {
      return List.of();
    }
  }

  /** A json array column back as a list of maps — the stored bump changes. */
  public static List<Map<String, Object>> readObjects(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> read = JSON.readValue(json, List.class);
      return read == null ? List.of() : read;
    } catch (Exception e) {
      return List.of();
    }
  }
}

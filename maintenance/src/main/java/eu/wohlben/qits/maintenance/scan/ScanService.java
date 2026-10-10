package eu.wohlben.qits.maintenance.scan;

import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.catalog.CatalogReader;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.latest.LatestResolver;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * A scan: read the catalog, re-read every repository's manifests, and refresh the latest versions
 * the scope covers. That is the whole of it — it writes an inventory and asks for nothing.
 *
 * <p><b>The manifests are ALWAYS re-read, whatever the scope.</b> That is one git-host call per
 * repository plus a handful of blobs, and it is what keeps the inventory honest: a scope that
 * skipped it would report pending changes against pins somebody removed yesterday. The scope
 * governs the REGISTRY half — internal releases land many times a day and are one hop away, an
 * external index is a daily question.
 *
 * <p><b>A repository is scanned even when the last scan failed</b>, and an unreachable one keeps
 * the pins it had. A peer that could not be asked is not evidence that a repository stopped pinning
 * anything.
 *
 * <p><b>A FULL scan also reconciles the other way: the inventory follows the catalog OUT.</b> Until
 * 2026-09-03 this loop only ever upserted what the catalog listed, so a repository that was renamed
 * or removed kept its last status (OK), its pins and its groups for ever — nothing else writes those
 * rows. Measured on the first nightly bump schedule: 96 repository rows against a catalog of 48, and
 * 23 of 30 bumps FAILED with {@code no run recorded for MaintenanceBump} because the clock was
 * bumping pre-rename ghosts. So a scan that read the WHOLE catalog now marks every unlisted row
 * ABSENT and drops its pins and groups — see {@link #reconcile}, which is where the three guards
 * are.
 *
 * <p><b>A SCAN NEVER BUMPS ANYTHING, whoever asked for it.</b> It used to: a SCHEDULED scan whose
 * {@code bump.auto} was on asked for one bump per group it found pending, at the end of its own run.
 * That is gone, and the reason is that it welded two decisions together. A scan is a READ — of the
 * catalog, of every manifest, of half the registries — and its schedule is set by how fast those
 * facts go stale; a bump is a WRITE into somebody else's repository, and its schedule is set by when
 * a branch is welcome. Coupling them meant the external scan's 01:00 read decided when the internal
 * half got a branch, and moving either cron moved the other's meaning. What is owed a bump is read
 * off the inventory this service wrote by {@code bump.BumpDispatcher}, which opens a release request
 * whose pre-run writes it (qits-1133).
 */
@ApplicationScoped
public class ScanService {

  private static final Logger LOG = Logger.getLogger(ScanService.class);

  /**
   * What a row says once the catalog stopped listing it. It is the repository's own sentence rather
   * than the scan's, in the shape every other status message here takes — the UI shows the reason,
   * never the word.
   */
  public static final String DROPPED_FROM_THE_CATALOG = "dropped from the catalog";

  @Inject CatalogReader catalog;

  @Inject eu.wohlben.qits.maintenance.automation.UpstreamReplan upstream;

  @Inject ManifestScanner manifests;

  @Inject LatestResolver resolver;

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject WorkQueue queue;

  /** The npm pins each repository reaches through its gitlinks, resolved here and stored beside. */
  @Inject GitlinkNpmPins gitlinkNpmPins;

  /**
   * Opens a scan row, queues the work and answers at once.
   *
   * <p><b>The row exists before the work does.</b> {@code POST /scans} answers 202 with this id and
   * the client polls {@code GET /scans/{id}} — a scan of the whole catalog is one git-host read per
   * repository and a registry lookup per dependency, so a person who pressed the button has to be
   * able to see that something is happening rather than guess from a listing that has not changed
   * yet.
   */
  public UUID request(ScanScope scope, String repository, ScanTrigger trigger) {
    return request(scope, repository, trigger, null);
  }

  /**
   * The same scan, reading one repository's manifests at {@code revision} instead of at its default
   * branch — what a release-triggered scan asks for, because a release is a tag and the branch has
   * not caught up yet. See {@link ManifestScanner#read(CatalogEntry, String)} for why that is the
   * honest read rather than merely the earlier one.
   *
   * <p><b>The revision rides the closure and is deliberately NOT a column on the scan row.</b> It is
   * a property of the work rather than of the record a client polls: the queue is in this process, so
   * a scan that does not run is one whose row never reaches a terminal state anyway, and a migration
   * to persist a value nothing could replay from would buy nothing. It is refused for a whole-catalog
   * scan, where one revision cannot be true of forty-eight repositories.
   */
  public UUID request(ScanScope scope, String repository, ScanTrigger trigger, String revision) {
    if (revision != null && !revision.isBlank() && repository == null) {
      throw new IllegalArgumentException(
          "a revision names one repository's history; a whole-catalog scan cannot take one");
    }
    UUID id = store.openScan(scope, repository, trigger.name(), Instant.now());
    String what =
        "the " + trigger.name().toLowerCase(java.util.Locale.ROOT) + " " + scope + " scan " + id
            + (repository == null ? "" : " of " + repository)
            + (revision == null || revision.isBlank() ? "" : " at " + revision);
    queue.submit(what, () -> run(id, scope, repository, revision, what));
    return id;
  }

  /**
   * One scan, start to finish, on the worker thread.
   *
   * <p><b>It never lets an exception escape without closing the row.</b> The first live scan over
   * 49 repositories died inside a latest lookup, and because nothing caught it the row stayed
   * RUNNING for ever — a scan that is not running and does not say so is worse than a failed one,
   * because nothing and nobody can tell the difference from a slow one.
   */
  void run(UUID id, ScanScope scope, String repository, String revision, String what) {
    try {
      scan(id, scope, repository, revision, what);
    } catch (RuntimeException e) {
      LOG.errorf(e, "%s could not be completed", what);
      store.scanStatus(id, ScanStatus.FAILED, message(e), Instant.now());
    }
  }

  /** What a scan does, with the row's ending left to {@link #run}. */
  private void scan(UUID id, ScanScope scope, String repository, String revision, String what) {
    Instant now = Instant.now();
    store.scanStatus(id, ScanStatus.RUNNING, null, now);
    CatalogReader.Result read = catalog.read();
    if (!read.ok()) {
      // NOTHING IS MARKED. A catalog this service could not read says nothing about any
      // repository, and writing UNREACHABLE across the whole inventory would turn one peer's
      // outage into seventy rows of noise.
      LOG.warnf("%s did nothing: %s", what, read.error());
      store.scanStatus(id, ScanStatus.FAILED, read.error(), Instant.now());
      return;
    }
    List<CatalogEntry> entries =
        read.entries().stream()
            .filter(entry -> repository == null || entry.name().equals(repository))
            .toList();
    if (entries.isEmpty()) {
      // AND THIS IS ALSO THE EMPTY-CATALOG GUARD. A read that succeeded and listed nothing — every
      // row unaddressable, or qits-projects answering `{"repositories":[]}` after somebody emptied
      // the wrong database — closes the scan FAILED here, one line before the reconciliation below
      // could mark the entire inventory ABSENT against it. A listing that lost every name is not
      // evidence that every repository is gone.
      LOG.warnf("%s matched no repository in the catalog", what);
      store.scanStatus(
          id, ScanStatus.FAILED, "no repository in the catalog matched", Instant.now());
      return;
    }

    // The WHOLE listing, not the filtered loop: a scan of one service still has to find the
    // project its frontend submodule is addressed under.
    Map<String, CatalogEntry> catalogByName = new LinkedHashMap<>();
    for (CatalogEntry entry : read.entries()) {
      catalogByName.putIfAbsent(entry.name(), entry);
    }
    for (CatalogEntry entry : entries) {
      scanOne(entry, catalogByName, revision, now);
    }
    // BEFORE the registry half, so no ghost's pins are looked up: a dropped repository's rows are
    // gone by the time refreshLatest reads the inventory back out of the store.
    reconcile(read, repository, what, now);
    refreshLatest(scope, repository);

    LOG.infof("%s finished over %d repositories", what, entries.size());
    store.scanStatus(
        id,
        ScanStatus.SUCCEEDED,
        entries.size() + " repositories read at " + scope + " scope",
        Instant.now());
  }

  /**
   * One repository's manifests, written into the inventory.
   *
   * <p>A repository that blows up is that repository's row, not the scan's: forty-eight others
   * still have manifests worth reading.
   */
  private void scanOne(
      CatalogEntry entry, Map<String, CatalogEntry> catalog, String revision, Instant now) {
    try {
      readInto(entry, catalog, revision, now);
    } catch (RuntimeException e) {
      LOG.warnf(e, "%s could not be scanned", entry.name());
      store.markRepository(
          entry.name(),
          entry.project(),
          entry.catalogId(),
          entry.archetype(),
          RepositoryStatus.UNREACHABLE,
          message(e),
          now);
    }
  }

  private void readInto(
      CatalogEntry entry, Map<String, CatalogEntry> catalog, String revision, Instant now) {
    ManifestScanner.Read read = manifests.read(entry, revision);
    if (read.status() == RepositoryStatus.UNREACHABLE) {
      store.markRepository(
          entry.name(),
          entry.project(),
          entry.catalogId(),
          entry.archetype(),
          read.status(),
          read.message(),
          now);
      return;
    }
    store.replaceInventory(
        entry.name(),
        entry.project(),
        entry.catalogId(),
        entry.archetype(),
        entry.mainBranch(),
        read.status(),
        read.headSha(),
        read.message(),
        read.pins(),
        read.groups(),
        read.groupSource(),
        config::kindOf,
        // What each gitlink's submodule pins at the recorded commit — read once per (submodule,
        // sha) and stored, so the GC's pin source can serve it without calling the git host.
        gitlinkNpmPins.resolve(entry.name(), read.pins(), catalog, now),
        now);
  }

  /**
   * <b>THE HALF THAT WAS MISSING: every row the catalog no longer lists, marked ABSENT.</b>
   *
   * <p>The scan above upserts what the catalog HAS. This is the other direction, and without it the
   * inventory is a set that only ever grows: a repository that was renamed keeps its last status —
   * OK, because the scan that recorded it succeeded — along with its pins, its groups and therefore
   * its pending count, and nothing anywhere ever says otherwise. What that cost was measured on
   * 2026-09-03: 30 nightly bumps, 23 of them FAILED with {@code no run recorded for MaintenanceBump}
   * against names qits-projects has not held for weeks.
   *
   * <h2>The three guards, and why each of them is here</h2>
   *
   * <ul>
   *   <li><b>the catalog read SUCCEEDED</b> — a failed read never reaches this method at all,
   *       because {@code scan} closes the row FAILED and returns the moment {@code read.ok()} is
   *       false. Reconciling against the empty list a failed read carries would mark every
   *       repository on the platform absent and wipe every pin, on one peer's outage;
   *   <li><b>the scan covers the WHOLE catalog</b> — {@code repository == null}. A scan OF ONE
   *       repository is a listing of one, and it says nothing whatsoever about the other
   *       forty-seven. That is the ordinary shape of {@code ScanTrigger.EVENT}: a push to a main
   *       branch queues a scan of exactly that repository, several times an hour, and a single-repo
   *       scan that reconciled would empty the inventory on every push;
   *   <li><b>the listing is not empty</b> — held one line earlier by the {@code entries.isEmpty()}
   *       refusal, and again inside {@code MaintenanceStore.reconcileCatalog}. Two places, because
   *       the failure mode is the whole store in one transaction.
   * </ul>
   *
   * <p><b>The names are the WHOLE listing</b>, taken off the catalog read rather than off the
   * filtered loop above — which is the same list here by construction, and would silently stop being
   * one the day a scope learns to narrow the repositories rather than the lookups.
   */
  private void reconcile(CatalogReader.Result read, String repository, String what, Instant now) {
    if (repository != null) {
      // A scan of one repository read a listing of one. Nothing here is evidence about anything
      // else, and the whole inventory is "anything else".
      return;
    }
    List<String> listed = read.entries().stream().map(CatalogEntry::name).toList();
    List<String> dropped = store.reconcileCatalog(listed, DROPPED_FROM_THE_CATALOG, now);
    if (!dropped.isEmpty()) {
      LOG.infof(
          "%s dropped %d repositories the catalog no longer lists: %s", what, dropped.size(), dropped);
    }
  }

  /**
   * The registry half.
   *
   * <p>One lookup per DEPENDENCY, not per pin: forty repositories pinning quarkus ask the registry
   * once. The set is read back out of the store rather than kept from the loop above, so a scan of
   * one repository still refreshes exactly that repository's dependencies.
   */
  private void refreshLatest(ScanScope scope, String repository) {
    Map<String, Lookup> wanted = new LinkedHashMap<>();
    List<MtPin> pins = repository == null ? store.allPins() : store.pins(repository);
    for (MtPin pin : pins) {
      Optional<Ecosystem> ecosystem = Ecosystem.of(pin.ecosystem);
      if (ecosystem.isEmpty()) {
        continue;
      }
      PinKind kind = PinKind.valueOf(pin.kind);
      if (!scope.covers(kind) || !resolver.resolvable(ecosystem.get(), kind)) {
        continue;
      }
      wanted.putIfAbsent(
          PendingChanges.key(pin.ecosystem, pin.name), new Lookup(ecosystem.get(), kind, pin.name));
    }
    for (Lookup lookup : wanted.values()) {
      // The resolver already turns everything into an answer; this is the belt to its braces, so
      // that even a bug in the resolver itself costs one dependency rather than the scan.
      LatestLookup latest;
      try {
        latest = resolver.resolve(lookup.ecosystem(), lookup.kind(), lookup.name());
      } catch (RuntimeException e) {
        LOG.warnf("The latest of %s could not be looked up: %s", lookup.name(), e.toString());
        latest = LatestLookup.failed(null, "the lookup failed: " + e);
      }
      try {
        if (store.recordLatest(lookup.ecosystem(), lookup.name(), latest, Instant.now())) {
          // The poll is one of the three places mt_latest advances (qits-1133); a no-op while the
          // dependency-bump automation is switched off.
          upstream.latestMoved(lookup.ecosystem(), lookup.name());
        }
      } catch (RuntimeException e) {
        LOG.warnf("The latest of %s could not be written: %s", lookup.name(), e.toString());
      }
    }
  }

  /** An exception as a row's message: the sentence, never a stack trace, never null. */
  private static String message(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.toString() : e.getClass().getSimpleName() + ": " + message;
  }

  /** One registry question: what is the newest version of this dependency. */
  private record Lookup(Ecosystem ecosystem, PinKind kind, String name) {}
}

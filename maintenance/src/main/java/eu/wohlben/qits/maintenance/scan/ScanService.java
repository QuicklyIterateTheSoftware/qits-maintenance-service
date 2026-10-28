package eu.wohlben.qits.maintenance.scan;

import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.catalog.CatalogReader;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.latest.LatestResolver;
import eu.wohlben.qits.maintenance.latest.MainHeadLatest;
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

  /**
   * What a row says once the catalog lists it as no longer declared by its project (qits-1695) — a
   * retired repository whose wrapper entry went while its catalog row stayed.
   */
  public static final String NOT_DECLARED_BY_ITS_PROJECT =
      "not declared by its project, so not maintained";

  @Inject CatalogReader catalog;

  @Inject eu.wohlben.qits.maintenance.automation.UpstreamReplan upstream;

  @Inject ManifestScanner manifests;

  @Inject LatestResolver resolver;

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject WorkQueue queue;

  /** The npm pins each repository reaches through its gitlinks, resolved here and stored beside. */
  @Inject GitlinkNpmPins gitlinkNpmPins;

  /** The ancestry door: is a release's commit on main yet. */
  @Inject GitHostReader gitHost;

  /**
   * The gitlink latest's one rule (qits-1257): the release a repository's {@code main} head
   * carries. A scan at main has resolved that head already, so keeping the row true here costs no
   * read at all.
   */
  @Inject MainHeadLatest mainHead;

  /** The main-only requests this service opened on an UNDECLARED row, withdrawn (qits-1695). */
  @Inject eu.wohlben.qits.maintenance.automation.UndeclaredRequestWithdrawal undeclaredRequests;

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
    int scanned = 0;
    for (CatalogEntry entry : entries) {
      if (!maintained(entry, repository, what)) {
        continue;
      }
      scanOne(entry, catalogByName, revision, now);
      scanned++;
    }
    // BEFORE the registry half, so no ghost's pins are looked up: a dropped repository's rows are
    // gone by the time refreshLatest reads the inventory back out of the store.
    reconcile(read, repository, what, now);
    refreshLatest(scope, repository);

    LOG.infof("%s finished over %d repositories", what, scanned);
    store.scanStatus(
        id,
        ScanStatus.SUCCEEDED,
        scanned + " repositories read at " + scope + " scope"
            + (scanned == entries.size()
                ? ""
                : ", " + (entries.size() - scanned) + " not maintained"),
        Instant.now());
  }

  /**
   * <b>Whether this scan reads the repository at all</b> (qits-1695) — false for a row its project
   * no longer declares.
   *
   * <ul>
   *   <li><b>The catalog says {@code declared:false}</b>: never read, by any scan. A full scan marks
   *       it UNDECLARED in {@link #reconcile}; a single-repository scan only skips it, because
   *       catalogue membership is the full scan's to apply.
   *   <li><b>The store already holds it UNDECLARED and this is a scan of ONE repository</b> — a
   *       push's, a release tag's, an automation's or a person's: skipped too, whatever the catalog
   *       says now. Otherwise the next push to a retired repository would write OK and fresh pins
   *       over the row, and the dispatcher would bump it again. The full scan that finds it declared
   *       is the only way back, and it reads it like any other row.
   * </ul>
   */
  private boolean maintained(CatalogEntry entry, String repository, String what) {
    if (!entry.declared()) {
      LOG.debugf("%s skips %s: %s", what, entry.name(), NOT_DECLARED_BY_ITS_PROJECT);
      return false;
    }
    if (repository != null
        && store
            .repository(entry.name())
            .map(row -> RepositoryStatus.UNDECLARED.name().equals(row.status))
            .orElse(false)) {
      LOG.infof(
          "%s skips %s: it is UNDECLARED until a full scan finds it declared", what, entry.name());
      return false;
    }
    return true;
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
    boolean atMain = revision == null || revision.isBlank();
    if (!atMain && read.status() == RepositoryStatus.ABSENT) {
      // A TAG THE GIT HOST DOES NOT HOLD SAYS NOTHING ABOUT MAIN (qits-1172). Writing it would
      // replace every pin of the repository with none — measured live 2026-10-10 on
      // qits-projects-service, whose pins went and whose pinned versions the GC then deleted.
      LOG.warnf(
          "%s is absent at %s; its pins are kept as they were: %s",
          entry.name(), revision, read.message());
      return;
    }
    // What each gitlink's submodule pins at the recorded commit — read once per (submodule, sha)
    // and stored, so the GC's pin source can serve it without calling the git host.
    List<MaintenanceStore.GitlinkPin> gitlinkPins =
        gitlinkNpmPins.resolve(entry.name(), read.pins(), catalog, now);
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
        gitlinkPins,
        now);
    if (atMain && read.headSha() != null && !read.headSha().isBlank()) {
      keepMain(entry, read, gitlinkPins);
      mainHeadLatest(entry, read.headSha());
    }
  }

  /**
   * <b>The repository's gitlink latest, set to the release its {@code main} head carries</b>
   * (qits-1257) — from the head this scan just resolved, so it is free. It is the scheduled half of
   * that rule: the push half in {@code bus/ScmEventListener} writes it minutes after a finalize
   * merge, and this catches a frame that never arrived and heals a row an older rule wrote, every
   * night, for every repository the catalog lists. A tag scan never reaches here — a tag is not
   * {@code main}, which is the whole point.
   *
   * <p>Its failure is a WARN and not the repository's: the manifests above were read and written,
   * and an UNREACHABLE row over a latest that could not be stored would say something untrue.
   */
  private void mainHeadLatest(CatalogEntry entry, String headSha) {
    try {
      mainHead.record(entry.name(), headSha, Instant.now());
    } catch (RuntimeException e) {
      LOG.warnf(
          "The gitlink latest of %s could not be set from its main head: %s",
          entry.name(), e.toString());
    }
  }

  /**
   * <b>MAIN'S PINS, KEPT APART FROM THE LAST SCAN'S, and the releases main has caught up with.</b>
   *
   * <p>A release scan replaces {@code mt_pin} with the TAG's pins, and a tag can run ahead of main
   * and of the deployment. So the GC's keep-set is a union (qits-1172): what main declares, plus
   * what every release not yet on main declared. This writes the first half and closes the second:
   * a release whose commit main now contains is dropped from it, because main declares the same.
   *
   * <p>Only a git host answering "yes" marks a release. An unanswered question keeps it, which keeps
   * more rather than less.
   */
  private void keepMain(
      CatalogEntry entry, ManifestScanner.Read read, List<MaintenanceStore.GitlinkPin> gitlinks) {
    List<MaintenanceStore.MainPin> pins = new java.util.ArrayList<>();
    for (eu.wohlben.qits.maintenance.manifest.ParsedPin pin : read.pins()) {
      if (pin.ecosystem() == Ecosystem.GITLINK || config.kindOf(pin) != PinKind.INTERNAL) {
        continue;
      }
      pins.add(
          new MaintenanceStore.MainPin(
              pin.ecosystem().wireName(), pin.name(), pin.version(), pin.manifestPath(), null));
    }
    for (MaintenanceStore.GitlinkPin pin : gitlinks == null ? List.<MaintenanceStore.GitlinkPin>of() : gitlinks) {
      pins.add(
          new MaintenanceStore.MainPin(
              pin.ecosystem(),
              pin.name(),
              pin.version(),
              pin.manifestPath(),
              eu.wohlben.qits.maintenance.control.Inventory.gitlinkVia(
                  pin.gitlinkPath(), pin.sha())));
    }
    store.replaceMainPins(entry.name(), read.headSha(), pins);

    List<UUID> onMain = new java.util.ArrayList<>();
    for (eu.wohlben.qits.maintenance.entity.MtRelease release :
        store.releasesNotOnMain(entry.name())) {
      if (GitlinkSha.same(release.sha, read.headSha())) {
        onMain.add(release.id);
        continue;
      }
      GitHostReader.Containment contained =
          gitHost.contains(entry.catalogId(), release.sha, read.headSha());
      if (contained.readable() && contained.contains()) {
        onMain.add(release.id);
      } else if (!contained.readable()) {
        LOG.debugf(
            "%s %s is kept: %s", entry.name(), release.version, contained.error());
      }
    }
    store.markReleasesOnMain(onMain);
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
   * <p><b>A listed row its project no longer declares is marked UNDECLARED here too</b>
   * (qits-1695), with the ABSENT treatment — pins and groups gone — and the main-only requests this
   * service opened on it are withdrawn. Same guards: only a full scan of a catalog read that
   * succeeded and listed something.
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
    // LISTED BUT NOT DECLARED (qits-1695): kept off the ABSENT sweep above by being listed, and
    // given the same treatment under their own status here.
    List<MaintenanceStore.Undeclared> undeclared =
        read.entries().stream()
            .filter(entry -> !entry.declared())
            .map(
                entry ->
                    new MaintenanceStore.Undeclared(
                        entry.name(), entry.project(), entry.catalogId(), entry.archetype()))
            .toList();
    List<String> newly = store.undeclare(undeclared, NOT_DECLARED_BY_ITS_PROJECT, now);
    if (!newly.isEmpty()) {
      LOG.infof(
          "%s marked %d repositories their project no longer declares: %s",
          what, newly.size(), newly);
    }
    // Every UNDECLARED row, not only the newly marked: a request opened before the mark, or a
    // withdrawal qits-projects did not take, is caught by the next full scan. It never fails this
    // one — the sweep logs and swallows its own failures, and this is the belt to that brace.
    try {
      undeclaredRequests.sweep();
    } catch (RuntimeException e) {
      LOG.warnf("%s could not withdraw the requests of undeclared repositories: %s", what,
          e.toString());
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

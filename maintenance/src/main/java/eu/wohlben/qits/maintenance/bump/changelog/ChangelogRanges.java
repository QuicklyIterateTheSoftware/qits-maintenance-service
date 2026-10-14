package eu.wohlben.qits.maintenance.bump.changelog;

import eu.wohlben.qits.maintenance.bump.Calver;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.entity.MtRelease;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.logging.Logger;

/**
 * <b>WHICH CHANGELOGS A BUMP CARRIES</b>, and the proof that every one of them exists (epic qits-893,
 * task qits-1143).
 *
 * <p>Every release publishes {@code @changelog/<repository>} at its version, and a bump commit
 * carries the changelogs of every release it pulls in: for each bumped INTERNAL dependency, every
 * release of its source repository after the old pin up to and including the new one. This class
 * decides that list per change; the bump step's CLI fetches the texts and composes the message.
 *
 * <p><b>The rules, in the order they are applied to one change.</b>
 *
 * <ol>
 *   <li><b>Only an internal change has changelogs.</b> {@link MaintenanceConfig#kindOf(Ecosystem,
 *       String)} decides; Maven Central, npmjs and upstream images publish none on this platform and
 *       are listed as they always were. A GITLINK is internal by construction.
 *   <li><b>The source repository</b> of a gitlink is its {@code name} — the repository the
 *       submodule is. Every other coordinate is looked up in {@link ArtifactGraph#producers()},
 *       keyed exactly as the dispatcher keys it. <b>An internal coordinate nobody is known to
 *       publish is a PROBLEM</b>: it has no changelog to find, and the owner's rule is that a missing
 *       changelog is an error rather than something to work around (refinement decision 7 — the
 *       first thing to relax if it proves noisy).
 *   <li><b>The old version</b> is the change's {@code from} — except for a gitlink, whose {@code
 *       from} is a commit sha. That is mapped through the repository's release ledger ({@link
 *       MaintenanceStore#releasesOf}) with {@link GitlinkSha#same}; a sha no release was cut from
 *       leaves the old version UNKNOWN, and the range is then the new version alone. A {@code from}
 *       that is not a calver is unknown in the same way: no calver order can bound it.
 *   <li><b>The published versions</b> are the docs store's listing ({@link ChangelogClient}). A
 *       repository with none at all predates changelogs and has no range — and no problem.
 *   <li><b>The floor is the OLDEST published changelog</b>, by calver. Releases cut before the
 *       epic have none, and they are left out rather than reported missing: the rule needs no
 *       configuration and ages out on its own.
 *   <li><b>The candidates</b> are the union of the ledger's versions, the published ones and the
 *       new pin itself (the bump writes it, so it is a release whatever the ledger says), kept
 *       when {@code from < v <= to} (only {@code v == to} when the old version is unknown) and {@code
 *       v >= floor}, in {@link Calver#ORDER}. The union matters both ways: the ledger finds a release
 *       whose publish did not complete, and the listing covers a release the ledger never heard of.
 *   <li><b>A candidate the store did not publish is asked about once more</b> (qits-1156): a tag
 *       is cut before its publish runs, so a release can be tagged and still publish NOTHING — its
 *       request went OBSOLETE or WITHDRAWN before its publish run finished, the run cancelled with
 *       it. qits-projects' history of the repository ({@link ReleaseRequestClient#history}) says so
 *       per version ({@link ReleaseRequestClient.Cut#publishedNothing}), and such a version is left
 *       out of the range: it has no changelog, and nothing can ever publish one (only a CI run of
 *       that release may). The history is read only when a changelog is missing. <b>Its commits
 *       are not lost</b>: the next release that publishes lists the commits of every earlier
 *       release since the last published changelog ({@code qits artifacts publish changelog},
 *       qits-1193), so a later version in the range carries them, and the bump message, which
 *       joins the changelogs of the range, carries them with it. Before qits-1193 they were in no
 *       changelog at all (measured, qits-maintenance-service 2026.1010.124627 lists none of
 *       2026.1010.120523's).
 *       <p><b>A publish that FAILED in a closed request is skipped too, but only below the new
 *       pin</b> (qits-1191, {@link ReleaseRequestClient.Cut#publishFailedForGood}). Its request is
 *       OBSOLETE or WITHDRAWN, so nothing will ever retry the run, and its changelog can never
 *       appear (measured: qits-workspace-daemon 2026.1011.81850 and 2026.1011.115738, publish runs
 *       restarted by a qits-ci redeploy, held every consumer's bump). The bump pins a later,
 *       published release, so it ships those commits, and that release's changelog lists them,
 *       as for a cancelled release.
 *       <p><b>As the new pin itself it passes only when a LATER release published a changelog</b>
 *       (qits-1209). The pin comes from the coordinate's own registry listing, so that coordinate
 *       is stored; publish-only-if-changed can leave it the newest version while later releases
 *       publish (measured: qits-projects-golden-masters 2026.1011.230000 held six bumps). The range
 *       then names the earliest later published changelog, which lists the pin's commits
 *       (qits-1193), so it can end above the pin. Without a later changelog it stays a PROBLEM.
 *   <li><b>Every other candidate the store did not publish is either still being published, or a
 *       PROBLEM.</b> It is <b>PENDING</b> (qits-1235) when none of the requests that cut it is
 *       FINALIZED and at least one is still RELEASED with its publish run not begun, PENDING,
 *       RUNNING or UNKNOWN ({@link ReleaseRequestClient.Cut#publishStillOpen}): a tag fires {@code
 *       SCMRelease} at once and the estate pins are re-planned on it within seconds ({@code
 *       UpstreamReplan}), minutes before that run stores {@code @changelog/<repository>}
 *       (measured: qits-workspaces-service 2026.1014.92409, tagged 09:24:08, its estate pins FAILED
 *       at 09:24:27, its changelog stored at 09:31:39). A pending version STAYS in the range and is
 *       named in {@link Result#pending}; the caller waits for it instead of failing the bump.
 *       <p>Otherwise it is a <b>PROBLEM</b> naming the repository and the version: every release
 *       publishes one, so that release's publish did not complete. That covers a publish run that
 *       went green without the changelog; any FINALIZED request without one; a release still
 *       RELEASED whose run FAILED or was CANCELLED, which nothing retries until somebody asks; and
 *       a version the history does not name at all.
 *   <li>No candidate, no range — the change carries no {@code changelog} field.
 * </ol>
 *
 * <p><b>A store that cannot be read is not a problem, it is a RETRY.</b> {@link Result#transientFailure}
 * tells the caller to leave the bump REQUESTED, exactly as a 503 from qits-ci's trigger does; a
 * changelog that is really missing is a sentence on a FAILED row, and those two must never be
 * confused.
 *
 * <p><b>A changelog still being published is not a problem either, it is a WAIT</b> (qits-1235).
 * {@link Result#pending} names each such release; the caller leaves the bump REQUESTED and the
 * sweep asks again, which recomputes the range. Unlike a retry it is bounded: a release that is
 * still pending after {@code qits.maintenance.bump.changelog-wait} is the problem sentence after
 * all ({@link Pending#problem}), because a publish run that long is one that broke.
 *
 * <p><b>One read per repository per call.</b> Several coordinates of one multi-module reactor share
 * a repository, so the listing and the ledger are cached for the duration of one {@link #resolve} —
 * and only that long, because the next dispatch must see a changelog published since.
 */
@ApplicationScoped
public class ChangelogRanges {

  private static final Logger LOG = Logger.getLogger(ChangelogRanges.class);

  @Inject MaintenanceConfig config;

  @Inject ArtifactGraph artifacts;

  @Inject MaintenanceStore store;

  @Inject ChangelogClient changelogs;

  @Inject ReleaseRequestClient releaseRequests;

  /**
   * What the changes of one bump carry.
   *
   * @param ranges the range of every change that has one, keyed by the change itself; a change
   *     absent here carries no {@code changelog} field
   * @param problems every reason the bump must not be sent, deduplicated, in the order met
   * @param transientFailure the docs store could not be read for at least one repository: send
   *     nothing now and ask again later
   * @param pending every release whose changelog is missing while its publish run has not finished
   *     (rule 8, qits-1235), deduplicated, in the order met. Each is still in its range: send
   *     nothing now, ask again later, and report {@link Pending#problem} once the wait is spent
   */
  public record Result(
      Map<Change, ChangelogRange> ranges,
      List<String> problems,
      boolean transientFailure,
      List<Pending> pending) {

    public Result {
      ranges = ranges == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ranges));
      problems = problems == null ? List.of() : List.copyOf(problems);
      pending = pending == null ? List.of() : List.copyOf(pending);
    }

    public Result(
        Map<Change, ChangelogRange> ranges, List<String> problems, boolean transientFailure) {
      this(ranges, problems, transientFailure, List.of());
    }
  }

  /**
   * One release whose changelog is still being published (qits-1235).
   *
   * @param repository the source repository
   * @param version the release
   */
  public record Pending(String repository, String version) {

    /** The sentence this release is once the wait is spent — the one every missing changelog gets. */
    public String problem() {
      return missing(repository, version);
    }

    /** {@code <repository> <version>}, as a sentence names it. */
    @Override
    public String toString() {
      return repository + " " + version;
    }
  }

  /** The sentence of a release with no changelog. */
  static String missing(String repository, String version) {
    return "no changelog for "
        + repository
        + " "
        + version
        + " (@changelog/"
        + repository
        + "): every release publishes one, so this release's publish did not complete";
  }

  /** One row of a repository's release ledger, as much of it as a range needs. */
  record Release(String version, String sha) {}

  /**
   * The versions of one repository that were tagged and will never publish a changelog (rule 7).
   *
   * @param versions the versions that published nothing; empty when none is known
   * @param failed the versions whose publish FAILED in a closed request (qits-1191): skipped only
   *     below the new pin, never as the pin itself
   * @param pending the versions whose publish has not finished yet (rule 8, qits-1235): kept in
   *     the range, and waited for rather than reported
   * @param error why qits-projects could not be read, or null — a RETRY, like an unreadable store
   */
  record Unpublished(Set<String> versions, Set<String> failed, Set<String> pending, String error) {

    Unpublished {
      versions = versions == null ? Set.of() : Set.copyOf(versions);
      failed = failed == null ? Set.of() : Set.copyOf(failed);
      pending = pending == null ? Set.of() : Set.copyOf(pending);
    }

    Unpublished(Set<String> versions, String error) {
      this(versions, Set.of(), Set.of(), error);
    }

    static final Unpublished NONE = new Unpublished(Set.of(), Set.of(), Set.of(), null);

    /**
     * What a readable history says: {@link #neverPublished}, {@link #failedForGood} and {@link
     * #stillPublishing}.
     */
    static Unpublished of(List<ReleaseRequestClient.Cut> cuts) {
      return new Unpublished(
          neverPublished(cuts), failedForGood(cuts), stillPublishing(cuts), null);
    }
  }

  /** Everything the rules read, as a seam a plain unit test can replace. */
  interface Sources {

    /** Rule 1: whether the platform publishes this coordinate. */
    boolean internal(Ecosystem ecosystem, String name);

    /** Rule 2: {@link ArtifactGraph#producers()}. */
    Map<String, String> producers();

    /** Rule 3 and 6: the repository's release ledger. */
    List<Release> releasesOf(String repository);

    /** Rule 4: the docs store's listing. */
    ChangelogClient.Result published(String repository);

    /** Rules 7 and 8: what qits-projects says of the versions with no changelog. */
    Unpublished unpublished(String repository);
  }

  /** The ranges of one bump's changes, read from this deployment's store, graph and docs store. */
  public Result resolve(List<Change> changes) {
    return resolve(
        changes,
        new Sources() {
          @Override
          public boolean internal(Ecosystem ecosystem, String name) {
            return config.kindOf(ecosystem, name) == PinKind.INTERNAL;
          }

          @Override
          public Map<String, String> producers() {
            return artifacts.producers();
          }

          @Override
          public List<Release> releasesOf(String repository) {
            List<Release> releases = new ArrayList<>();
            for (MtRelease row : store.releasesOf(repository)) {
              releases.add(new Release(row.version, row.sha));
            }
            return releases;
          }

          @Override
          public ChangelogClient.Result published(String repository) {
            return changelogs.versions(repository);
          }

          @Override
          public Unpublished unpublished(String repository) {
            String catalogId = store.repository(repository).map(row -> row.catalogId).orElse(null);
            if (catalogId == null || catalogId.isBlank()) {
              // qits-projects is addressed by the catalog id; without one nothing says a release
              // published nothing, so its missing changelog stays a problem.
              return Unpublished.NONE;
            }
            ReleaseRequestClient.History history = releaseRequests.history(catalogId);
            if (!history.readable()) {
              return new Unpublished(Set.of(), history.error());
            }
            return Unpublished.of(history.cuts());
          }
        });
  }

  /** The rules themselves, over any {@link Sources}. */
  static Result resolve(List<Change> changes, Sources sources) {
    Map<Change, ChangelogRange> ranges = new LinkedHashMap<>();
    Set<String> problems = new LinkedHashSet<>();
    Set<Pending> pending = new LinkedHashSet<>();
    boolean transientFailure = false;

    // Read once per call, lazily: a bump of external dependencies only never touches the graph.
    Map<String, String> producers = null;
    Map<String, ChangelogClient.Result> published = new HashMap<>();
    Map<String, List<Release>> ledgers = new HashMap<>();
    Map<String, Unpublished> unpublished = new HashMap<>();

    for (Change change : changes == null ? List.<Change>of() : changes) {
      // 1. Internal only.
      Optional<Ecosystem> ecosystem = Ecosystem.of(change.ecosystem());
      if (ecosystem.isEmpty()
          || change.name() == null
          || !sources.internal(ecosystem.get(), change.name())) {
        continue;
      }
      boolean gitlink = ecosystem.get() == Ecosystem.GITLINK;

      // 2. The source repository.
      String repository;
      if (gitlink) {
        repository = change.name().trim();
      } else {
        if (producers == null) {
          producers = sources.producers();
        }
        repository = producers.get(ArtifactGraph.producerKey(change.ecosystem(), change.name()));
      }
      if (repository == null || repository.isBlank()) {
        problems.add(
            "no source repository is known for "
                + change.ecosystem()
                + " "
                + change.name()
                + ", so its changelogs cannot be found");
        continue;
      }

      // 4. What the store published — first, because a repository with none needs no ledger.
      ChangelogClient.Result listing = published.computeIfAbsent(repository, sources::published);
      if (listing.transientFailure()) {
        transientFailure = true;
        LOG.warnf(
            "The changelogs of %s could not be read (%s): %s",
            repository, listing.url(), listing.reason());
        continue;
      }
      TreeSet<String> publishedVersions = new TreeSet<>(Calver.ORDER);
      for (String version : listing.versions()) {
        if (Calver.isCalver(version)) {
          publishedVersions.add(version);
        }
      }
      if (publishedVersions.isEmpty()) {
        // The repository predates changelogs: listed as today, with nothing to find and nothing
        // to report.
        continue;
      }

      String to = change.to();
      if (!Calver.isCalver(to)) {
        // Not a version this platform cut, so no release of it published anything.
        continue;
      }

      // 3. The old version.
      List<Release> ledger = ledgers.computeIfAbsent(repository, sources::releasesOf);
      String old = gitlink ? releasedFrom(ledger, change.from()) : change.from();
      if (old != null && !Calver.isCalver(old)) {
        old = null;
      }

      // 5. The floor.
      String floor = publishedVersions.first();

      // 6. The candidates.
      TreeSet<String> candidates = new TreeSet<>(Calver.ORDER);
      List<String> union = new ArrayList<>(publishedVersions);
      // The new pin is a release by definition — it is what the bump writes — so it is a candidate
      // even when neither the ledger nor the listing has heard of it; otherwise the one release a
      // bump is sure to pull in could be missing its changelog unnoticed.
      union.add(to);
      for (Release release : ledger) {
        union.add(release.version());
      }
      for (String version : union) {
        if (!Calver.isCalver(version)
            || Calver.compare(version, to) > 0
            || Calver.compare(version, floor) < 0) {
          continue;
        }
        boolean inRange =
            old == null ? Calver.compare(version, to) == 0 : Calver.compare(version, old) > 0;
        if (inRange) {
          candidates.add(version);
        }
      }

      // 7. Every candidate published — or published nothing, for good.
      List<String> missing =
          candidates.stream().filter(version -> !publishedVersions.contains(version)).toList();
      Unpublished nothing = Unpublished.NONE;
      if (!missing.isEmpty()) {
        nothing = unpublished.computeIfAbsent(repository, sources::unpublished);
        if (nothing.error() != null) {
          transientFailure = true;
          LOG.warnf(
              "The release requests of %s could not be read: %s", repository, nothing.error());
          continue;
        }
        for (String version : missing) {
          if (nothing.versions().contains(version)) {
            candidates.remove(version);
            LOG.infof(
                "%s %s was tagged and published nothing, so a bump carries no changelog of it",
                repository, version);
          } else if (nothing.failed().contains(version) && Calver.compare(version, to) < 0) {
            candidates.remove(version);
            LOG.infof(
                "%s %s was tagged and its publish failed in a closed request, so nothing can"
                    + " publish its changelog; a bump to the later %s carries none of it",
                repository, version, to);
          } else if (nothing.failed().contains(version) && publishedVersions.higher(to) != null) {
            // qits-1209: the pin itself failed for good, but a later release published, and its
            // changelog lists the pin's commits (qits-1193). Name that changelog instead.
            String later = publishedVersions.higher(to);
            candidates.remove(version);
            candidates.add(later);
            LOG.infof(
                "%s %s is the new pin and its publish failed in a closed request; its commits are"
                    + " in the later changelog %s, so the range names that one",
                repository, version, later);
          }
        }
      }
      // 8. Still being published — a wait, and the version stays in the range (qits-1235); or a
      // problem.
      for (String version : candidates) {
        if (publishedVersions.contains(version)) {
          continue;
        }
        if (nothing.pending().contains(version)) {
          pending.add(new Pending(repository, version));
          LOG.infof(
              "%s %s has no changelog yet and its publish run has not finished; the bump waits"
                  + " for it",
              repository, version);
        } else {
          problems.add(missing(repository, version));
        }
      }

      // 9. No candidate, no range.
      if (!candidates.isEmpty()) {
        ranges.put(change, new ChangelogRange(repository, List.copyOf(candidates)));
      }
    }
    return new Result(ranges, List.copyOf(problems), transientFailure, List.copyOf(pending));
  }

  /**
   * The versions whose every cut request published nothing. A version cut by more than one request
   * is skipped only when none of them published.
   */
  static Set<String> neverPublished(List<ReleaseRequestClient.Cut> cuts) {
    Set<String> nothing = new HashSet<>();
    Set<String> ran = new HashSet<>();
    for (ReleaseRequestClient.Cut cut : cuts) {
      (cut.publishedNothing() ? nothing : ran).add(cut.version());
    }
    nothing.removeAll(ran);
    return nothing;
  }

  /**
   * The versions whose publish FAILED for good (qits-1191): every request that cut it closed without
   * completing, at least one with a FAILED publish run, and none with a run that passed or is open.
   * A version also cut by a FINALIZED or RELEASED request, or by one whose run passed, is not here.
   */
  static Set<String> failedForGood(List<ReleaseRequestClient.Cut> cuts) {
    Set<String> failed = new HashSet<>();
    Set<String> other = new HashSet<>();
    for (ReleaseRequestClient.Cut cut : cuts) {
      if (cut.publishFailedForGood()) {
        failed.add(cut.version());
      } else if (!cut.publishedNothing()) {
        other.add(cut.version());
      }
    }
    failed.removeAll(other);
    return failed;
  }

  /**
   * The versions whose publish has not finished yet (qits-1235): at least one request that cut it is
   * still RELEASED with its publish run not begun or not ended ({@link
   * ReleaseRequestClient.Cut#publishStillOpen}), and none is FINALIZED — a completed release without
   * its changelog is a broken publish whatever another request is doing.
   */
  static Set<String> stillPublishing(List<ReleaseRequestClient.Cut> cuts) {
    Set<String> open = new HashSet<>();
    Set<String> finalized = new HashSet<>();
    for (ReleaseRequestClient.Cut cut : cuts) {
      if (cut.publishStillOpen()) {
        open.add(cut.version());
      } else if ("FINALIZED".equals(cut.state())) {
        finalized.add(cut.version());
      }
    }
    open.removeAll(finalized);
    return open;
  }

  /**
   * The release a gitlink's old commit was cut from, or null. Where one commit was released more
   * than once, the newest of those versions is what the pin already carried.
   */
  private static String releasedFrom(List<Release> ledger, String sha) {
    String newest = null;
    for (Release release : ledger) {
      if (release.version() != null
          && GitlinkSha.same(release.sha(), sha)
          && (newest == null || Calver.compare(release.version(), newest) > 0)) {
        newest = release.version();
      }
    }
    return newest;
  }
}

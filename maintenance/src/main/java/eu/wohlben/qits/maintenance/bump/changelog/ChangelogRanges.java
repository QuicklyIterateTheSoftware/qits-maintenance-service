package eu.wohlben.qits.maintenance.bump.changelog;

import eu.wohlben.qits.maintenance.bump.Calver;
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
 *   <li><b>A candidate the store did not publish is a PROBLEM</b> naming the repository and the
 *       version: every release publishes one, so that release's publish did not complete.
 *   <li>No candidate, no range — the change carries no {@code changelog} field.
 * </ol>
 *
 * <p><b>A store that cannot be read is not a problem, it is a RETRY.</b> {@link Result#transientFailure}
 * tells the caller to leave the bump REQUESTED, exactly as a 503 from qits-ci's trigger does; a
 * changelog that is really missing is a sentence on a FAILED row, and those two must never be
 * confused.
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

  /**
   * What the changes of one bump carry.
   *
   * @param ranges the range of every change that has one, keyed by the change itself; a change
   *     absent here carries no {@code changelog} field
   * @param problems every reason the bump must not be sent, deduplicated, in the order met
   * @param transientFailure the docs store could not be read for at least one repository: send
   *     nothing now and ask again later
   */
  public record Result(
      Map<Change, ChangelogRange> ranges, List<String> problems, boolean transientFailure) {

    public Result {
      ranges = ranges == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ranges));
      problems = problems == null ? List.of() : List.copyOf(problems);
    }
  }

  /** One row of a repository's release ledger, as much of it as a range needs. */
  record Release(String version, String sha) {}

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
        });
  }

  /** The rules themselves, over any {@link Sources}. */
  static Result resolve(List<Change> changes, Sources sources) {
    Map<Change, ChangelogRange> ranges = new LinkedHashMap<>();
    Set<String> problems = new LinkedHashSet<>();
    boolean transientFailure = false;

    // Read once per call, lazily: a bump of external dependencies only never touches the graph.
    Map<String, String> producers = null;
    Map<String, ChangelogClient.Result> published = new HashMap<>();
    Map<String, List<Release>> ledgers = new HashMap<>();

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

      // 7. Every candidate published.
      for (String version : candidates) {
        if (!publishedVersions.contains(version)) {
          problems.add(
              "no changelog for "
                  + repository
                  + " "
                  + version
                  + " (@changelog/"
                  + repository
                  + "): every release publishes one, so this release's publish did not complete");
        }
      }

      // 8. No candidate, no range.
      if (!candidates.isEmpty()) {
        ranges.put(change, new ChangelogRange(repository, List.copyOf(candidates)));
      }
    }
    return new Result(ranges, List.copyOf(problems), transientFailure);
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

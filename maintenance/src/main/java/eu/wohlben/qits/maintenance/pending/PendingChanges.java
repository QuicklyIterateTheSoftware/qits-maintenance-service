package eu.wohlben.qits.maintenance.pending;

import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.latest.PublishedLookup;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.manifest.Globs;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Which pins are behind, and which group each belongs to.
 *
 * <p><b>Pending is COMPUTED, never stored.</b> It is {@code mt_pin} joined to {@code mt_latest} and
 * read through {@code mt_group}; a table would be a third copy of a fact two others already carry,
 * going stale between the scan that moves a pin and the scan that moves a latest.
 *
 * <p><b>A pin has to be actionable before either rule applies.</b> REACTOR and UNRESOLVED pins are
 * recorded and shown and are never pending — there is no line to edit in the first and no version
 * to compare in the second.
 *
 * <p><b>And what is pending is then grouped, by kind or by glob.</b> The fallback grouping is the
 * INTERNAL/EXTERNAL split — a pin's own kind decides its branch — while a repository that declared
 * groups of its own has those tried first, by their globs. See {@link #groupOf}.
 *
 * <p><b>Two rules then decide whether a newer version is OFFERED, and the second is the one people
 * ask about.</b>
 *
 * <ol>
 *   <li>The latest has to be strictly newer in that ecosystem's own order — maven's for a pom, semver
 *       for npm, the same maven order over calver tags for an image.
 *   <li>A PRERELEASE latest is offered only when the pin is a prerelease too. Nobody wants a
 *       release candidate pushed onto a branch by a schedule; somebody already running one does want
 *       the next.
 * </ol>
 *
 * <p><b>A latest that could not be read offers nothing</b>, and that is deliberately different from
 * "up to date". The repository page shows the error beside the pin so the two never look alike.
 *
 * <p><b>GITLINK answers neither rule and is decided by {@link #gitlink} instead.</b> Its pin is a
 * commit sha, and there is no order over shas — the two rules above would be arithmetic on a hash.
 *
 * <p><b>A maven property two artifacts read is decided once, for both</b> (qits-1206), by {@link
 * #resolve}: its one line moves every reader, and a content-addressed reactor need not have
 * published all of them at the version one of them is offered.
 */
public final class PendingChanges {

  /** The location prefix of a pin versioned by a declared maven property. */
  private static final String PROPERTY = "property:";

  private PendingChanges() {}

  /**
   * One pending upgrade, with the group that will carry it.
   *
   * @param group the group whose branch this change goes on
   * @param change the edit itself
   */
  public record Pending(String group, Change change) {}

  /**
   * One pin's planned edit, before any group is chosen.
   *
   * @param pin the pin the change is keyed to — for a shared property, the first of its readers by
   *     name — and so the pin whose group carries it
   * @param change the edit itself
   */
  public record Planned(MtPin pin, Change change) {}

  /**
   * What the pending rule makes of a set of pins: the edits, and what was behind and not edited.
   *
   * @param planned the edits, in the order the pins were given
   * @param held the dependencies a {@code hold:} kept back — every reader of a shared property one
   *     of whose readers is held
   * @param blocked one sentence per shared property that is behind and has no version to move to
   */
  public record Resolution(List<Planned> planned, List<String> held, List<String> blocked) {

    /** Only the edits. */
    public List<Change> changes() {
      return planned.stream().map(Planned::change).toList();
    }
  }

  /**
   * The pending rule over a set of pins, with every maven property more than one of them reads
   * decided ONCE, for all its readers.
   *
   * <p><b>Why a shared property is not decided per pin</b> (qits-1206). One {@code ${property}}
   * read by several artifacts of a reactor — {@code qits-arch-rules} and {@code qits-db-core} both
   * at {@code ${qits.integrations-quarkus.version}} — is one line, and the step that applies a
   * change rewrites that line for every reader at once. Such a reactor publishes
   * CONTENT-ADDRESSED: a release uploads only the modules whose content changed, so {@code
   * qits-arch-rules} can exist at a version {@code qits-db-core} was never uploaded at. Judged per
   * pin, arch-rules' latest moved the property and db-core's build then failed to resolve. So the
   * target is the highest version EVERY reader has published that is newer than the property's
   * value — readers not behind themselves constrain it all the same — and a property with no such
   * version is not moved at all: it is reported in {@code blocked}, as is one whose readers'
   * published versions could not be read.
   *
   * <p><b>A property one artifact reads is exactly the per-pin rule</b>, and asks no registry: the
   * version list is fetched only for a shared property, and only once one of its readers is behind
   * by {@link #newerVersion}.
   *
   * @param pins the pins to judge; REACTOR and UNRESOLVED ones are never pending and share nothing
   * @param latest every latest row, keyed by ecosystem and name
   * @param holds whether a {@code hold:} names a dependency; holding one reader holds the property
   * @param published the registry's version lists, or null to judge every pin on its own — the
   *     per-pin verdict the repository page shows, which asks nothing
   */
  public static Resolution resolve(
      List<MtPin> pins,
      Map<String, MtLatest> latest,
      Predicate<String> holds,
      PublishedVersions published) {
    Map<String, List<MtPin>> shared = published == null ? Map.of() : sharedProperties(pins);
    List<Planned> planned = new ArrayList<>();
    List<String> held = new ArrayList<>();
    List<String> blocked = new ArrayList<>();
    Set<String> decided = new HashSet<>();
    for (MtPin pin : pins) {
      String property = sharedKey(pin);
      List<MtPin> readers = property == null ? null : shared.get(property);
      if (readers != null) {
        if (decided.add(property)) {
          shared(readers, latest, holds, published, planned, held, blocked);
        }
        continue;
      }
      Optional<String> newer = newerVersion(pin, latest);
      if (newer.isEmpty()) {
        continue;
      }
      if (holds.test(pin.name)) {
        held.add(pin.name);
        continue;
      }
      planned.add(
          new Planned(
              pin,
              new Change(
                  pin.ecosystem, pin.manifestPath, pin.name, pin.version, newer.get(), pin.location)));
    }
    return new Resolution(List.copyOf(planned), List.copyOf(held), List.copyOf(blocked));
  }

  /**
   * One shared property's verdict: nothing when no reader is behind, held when any reader is held,
   * otherwise one change to the highest version every reader has published — or a blocked
   * sentence when there is none, or a version list could not be read.
   */
  private static void shared(
      List<MtPin> readers,
      Map<String, MtLatest> latest,
      Predicate<String> holds,
      PublishedVersions published,
      List<Planned> planned,
      List<String> held,
      List<String> blocked) {
    if (readers.stream().noneMatch(reader -> newerVersion(reader, latest).isPresent())) {
      return;
    }
    List<String> names = readers.stream().map(reader -> reader.name).toList();
    if (readers.stream().anyMatch(reader -> holds.test(reader.name))) {
      held.addAll(names);
      return;
    }
    MtPin keyed = readers.getFirst();
    String property = keyed.location.substring(PROPERTY.length());
    Ecosystem ecosystem = Ecosystem.MAVEN;
    Set<String> common = null;
    for (MtPin reader : readers) {
      PublishedLookup lookup = published.lookup(ecosystem, kindOf(reader), reader.name);
      if (lookup == null || !lookup.ok()) {
        blocked.add(
            "property " + property + ": the published versions of " + reader.name
                + " could not be read: "
                + (lookup == null ? "no answer" : lookup.error()));
        return;
      }
      Set<String> versions = new LinkedHashSet<>();
      for (String version : lookup.versions()) {
        versions.add(version.trim());
      }
      if (common == null) {
        common = versions;
      } else {
        common.retainAll(versions);
      }
    }
    String current = keyed.version;
    List<String> candidates = new ArrayList<>();
    for (String version : common) {
      if (!VersionOrder.newer(ecosystem, current, version)) {
        continue;
      }
      // The same prerelease rule newerVersion applies: a released property is never moved to a
      // release candidate, however many readers have one.
      if (VersionOrder.prerelease(ecosystem, version)
          && !VersionOrder.prerelease(ecosystem, current)) {
        continue;
      }
      candidates.add(version);
    }
    Optional<String> target = VersionOrder.highest(ecosystem, candidates);
    if (target.isEmpty()) {
      blocked.add(
          "property " + property + ": no version newer than " + current
              + " is published for every artifact reading it (" + String.join(", ", names) + ")");
      return;
    }
    planned.add(
        new Planned(
            keyed,
            new Change(
                keyed.ecosystem, keyed.manifestPath, keyed.name, current, target.get(),
                keyed.location)));
  }

  /**
   * The (manifest, location) a pin's version line is shared on, or null for a pin that cannot
   * share one: only an actionable maven pin versioned by a declared property.
   */
  private static String sharedKey(MtPin pin) {
    if (!Ecosystem.MAVEN.wireName().equals(pin.ecosystem)
        || !kindOf(pin).actionable()
        || pin.location == null
        || !pin.location.startsWith(PROPERTY)) {
      return null;
    }
    return pin.manifestPath + "\0" + pin.location;
  }

  /**
   * Every property two or more distinct artifacts read, keyed by {@link #sharedKey}, readers sorted
   * by name. A property one artifact reads — however many modules repeat it — is not here.
   */
  private static Map<String, List<MtPin>> sharedProperties(List<MtPin> pins) {
    Map<String, Map<String, MtPin>> byProperty = new LinkedHashMap<>();
    for (MtPin pin : pins) {
      String key = sharedKey(pin);
      if (key != null) {
        byProperty.computeIfAbsent(key, k -> new TreeMap<>()).putIfAbsent(pin.name, pin);
      }
    }
    Map<String, List<MtPin>> shared = new LinkedHashMap<>();
    byProperty.forEach(
        (key, readers) -> {
          if (readers.size() > 1) {
            shared.put(key, List.copyOf(readers.values()));
          }
        });
    return shared;
  }

  /**
   * Every pending change of one repository, grouped, in a stable order — each pin judged on its
   * own. This is the per-pin verdict the repository page counts; it asks no registry, so a shared
   * property reads as pending wherever one of its readers is behind. A bump uses {@link
   * #of(List, Map, List, PublishedVersions)}.
   *
   * @param pins the repository's pins
   * @param latest every latest row, keyed by ecosystem and name
   * @param groups the repository's groups, in declaration order
   */
  public static List<Pending> of(List<MtPin> pins, Map<String, MtLatest> latest, List<MtGroup> groups) {
    return of(pins, latest, groups, null);
  }

  /**
   * Every pending change of one repository, grouped, in a stable order, with every shared maven
   * property decided once for all its readers — see {@link #resolve}.
   *
   * @param pins the repository's pins
   * @param latest every latest row, keyed by ecosystem and name
   * @param groups the repository's groups, in declaration order
   * @param published the registry's version lists, or null for the per-pin verdict
   */
  public static List<Pending> of(
      List<MtPin> pins,
      Map<String, MtLatest> latest,
      List<MtGroup> groups,
      PublishedVersions published) {
    List<Pending> pending = new ArrayList<>();
    for (Planned planned : resolve(pins, latest, name -> false, published).planned()) {
      // Only an ACTIONABLE pin gets this far: `newerVersion` answers empty for REACTOR and
      // UNRESOLVED, so every pin reaching the grouping below is INTERNAL or EXTERNAL and the two
      // kind groups between them claim everything a configured group left. A shared property's
      // change goes with the pin it is keyed to.
      Optional<String> group = groupOf(planned.pin(), groups);
      if (group.isEmpty()) {
        // Nothing claims it: a repository that declared its own `dependencies` and `external`
        // groups with globs that leave a gap. There is no branch to put this on, so it is not
        // pending — it is unclaimed, and the repository page still shows the newer version.
        continue;
      }
      pending.add(new Pending(group.get(), planned.change()));
    }
    return List.copyOf(pending);
  }

  /**
   * Only the changes of one group, which is what a bump payload carries — with every shared maven
   * property decided once, against what its readers have actually published (qits-1206).
   */
  public static List<Change> forGroup(
      List<MtPin> pins,
      Map<String, MtLatest> latest,
      List<MtGroup> groups,
      String group,
      PublishedVersions published) {
    List<Change> changes = new ArrayList<>();
    for (Pending entry : of(pins, latest, groups, published)) {
      if (entry.group().equals(group)) {
        changes.add(entry.change());
      }
    }
    return List.copyOf(changes);
  }

  /** How many changes each group has, every declared group present even at zero. */
  public static Map<String, Integer> countByGroup(
      List<MtPin> pins, Map<String, MtLatest> latest, List<MtGroup> groups) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (MtGroup group : groups) {
      counts.put(group.name, 0);
    }
    for (Pending entry : of(pins, latest, groups)) {
      counts.merge(entry.group(), 1, Integer::sum);
    }
    return counts;
  }

  /**
   * The version this pin should move to, or empty.
   *
   * <p>Public because the repository detail page reports the same verdict per pin, and a second
   * implementation of "is this one behind" would be the one the UI disagreed with.
   */
  public static Optional<String> newerVersion(MtPin pin, Map<String, MtLatest> latest) {
    // A REACTOR or UNRESOLVED pin has no line to bump and no version worth comparing. It is never
    // pending, whatever a registry says — a module depending on its sibling at ${project.version}
    // is the ordinary case, and offering an upgrade for it would be offering to overwrite the
    // release's own stamp.
    if (!kindOf(pin).actionable()) {
      return Optional.empty();
    }
    MtLatest row = latest.get(key(pin.ecosystem, pin.name));
    if (row == null || row.latest == null || row.latest.isBlank()) {
      return Optional.empty();
    }
    Optional<Ecosystem> ecosystem = Ecosystem.of(pin.ecosystem);
    if (ecosystem.isEmpty()) {
      return Optional.empty();
    }
    if (ecosystem.get() == Ecosystem.GITLINK) {
      return gitlink(pin, row);
    }
    if (!VersionOrder.newer(ecosystem.get(), pin.version, row.latest)) {
      return Optional.empty();
    }
    if (VersionOrder.prerelease(ecosystem.get(), row.latest)
        && !VersionOrder.prerelease(ecosystem.get(), pin.version)) {
      return Optional.empty();
    }
    return Optional.of(row.latest);
  }

  /**
   * A gitlink's verdict: <b>a difference between two shas, never a comparison of two versions</b>.
   *
   * <p>Neither of this class's two rules can be applied to one, and forcing them would be worse
   * than useless. A gitlink PIN is a commit sha — 40 hex characters that no order ranks, and that
   * maven's order would happily declare "newer" or "older" by reading the leading digits of a hash.
   * And rule 2 has nothing to work on: a sha is neither a release nor a prerelease.
   *
   * <p>So the question is the only one that can be asked honestly — <b>is the submodule pinned at
   * the commit that repository's {@code main} head is, the newest FINALIZED release</b> (qits-1257:
   * a release that was only cut is never a target) — and both halves have to be
   * KNOWN for the answer to be no. The latest row carries the release's sha in
   * {@code source_url} ({@code latest/GitlinkSha}); a row without one is a release this service
   * could not tie to a commit, and pending must not be guessed from a version alone: the step
   * fetches a tag and would move the gitlink to a commit nothing here compared.
   *
   * <p><b>The change's {@code to} is the calver VERSION and its {@code from} is the sha.</b> They
   * are deliberately not the same kind of thing, because they address different sides: the step
   * fetches {@code refs/tags/<to>} from the sibling repository and writes whatever commit that
   * resolves to, and {@code from} is what the tree holds now — the commit-message half, exactly as
   * it is for every other ecosystem.
   */
  private static Optional<String> gitlink(MtPin pin, MtLatest row) {
    Optional<String> released = GitlinkSha.read(row.sourceUrl);
    if (released.isEmpty() || pin.version == null || pin.version.isBlank()) {
      return Optional.empty();
    }
    if (GitlinkSha.same(pin.version, released.get())) {
      return Optional.empty();
    }
    return Optional.of(row.latest);
  }

  /**
   * The group a pin belongs to.
   *
   * <p><b>Two ways a group claims, and a group uses exactly one of them.</b> A group with a KIND
   * takes every pin of that kind — the two built-in halves, {@code dependencies} for INTERNAL and
   * {@code external} for EXTERNAL. A group with none matches its GLOBS against the dependency name,
   * which is what a repository's own {@code .config/qits/maintenance.yml} declares.
   *
   * <p><b>First match wins</b>, in declaration order, which is what {@code mt_group.ordinal}
   * carries. A configured group is written before the kind pair, so {@code angular} claims
   * {@code @angular/core} and the EXTERNAL half never sees it.
   *
   * <p><b>A REACTOR or UNRESOLVED pin is claimed by no kind group</b>, because neither of those is a
   * kind a group can carry: there is no line to bump and no branch to put it on. It reaches this
   * method only from the detail page — {@link #of} filters it out one step earlier — and a glob
   * group that names it explicitly still claims it, which is what the page then shows.
   */
  public static Optional<String> groupOf(MtPin pin, List<MtGroup> groups) {
    PinKind kind = kindOf(pin);
    for (MtGroup group : groups) {
      Optional<PinKind> claims = kindOf(group);
      boolean matched =
          claims.isPresent()
              ? claims.get() == kind
              : Globs.matchesAny(MaintenanceStore.readStrings(group.patterns), pin.name);
      if (matched) {
        return Optional.of(group.name);
      }
    }
    return Optional.empty();
  }

  /**
   * A group's kind, or empty when it claims by glob.
   *
   * <p>A word this build does not know reads as empty rather than as a crash — the same reading a
   * pin's unknown kind gets, and for the same reason: a row written by a newer build must not take
   * a page down. Such a group then claims by its patterns, which for a kind group are {@code []} —
   * so it claims nothing rather than everything.
   */
  public static Optional<PinKind> kindOf(MtGroup group) {
    if (group.kind == null || group.kind.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(PinKind.valueOf(group.kind));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** A stored pin's kind, defaulting to UNRESOLVED for a word this build does not know. */
  public static PinKind kindOf(MtPin pin) {
    try {
      return PinKind.valueOf(pin.kind);
    } catch (IllegalArgumentException | NullPointerException e) {
      return PinKind.UNRESOLVED;
    }
  }

  /** The join key of {@code mt_pin} and {@code mt_latest}. */
  public static String key(String ecosystem, String name) {
    return ecosystem + " " + name;
  }

  /** Every latest row, keyed for the join. */
  public static Map<String, MtLatest> index(List<MtLatest> rows) {
    Map<String, MtLatest> index = new LinkedHashMap<>();
    for (MtLatest row : rows) {
      index.put(key(row.ecosystem, row.name), row);
    }
    return index;
  }
}

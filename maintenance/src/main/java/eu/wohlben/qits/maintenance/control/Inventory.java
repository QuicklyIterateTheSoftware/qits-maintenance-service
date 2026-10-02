package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.dto.BumpDto;
import eu.wohlben.qits.maintenance.dto.BumpWindowDto;
import eu.wohlben.qits.maintenance.dto.DependencyDto;
import eu.wohlben.qits.maintenance.dto.GroupDto;
import eu.wohlben.qits.maintenance.dto.PinDto;
import eu.wohlben.qits.maintenance.dto.PinSourceDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDetailDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDto;
import eu.wohlben.qits.maintenance.dto.ScanDto;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtBumpWindow;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.entity.MtScan;
import eu.wohlben.qits.maintenance.error.EmptyInventoryException;
import eu.wohlben.qits.maintenance.error.NoSuchBumpException;
import eu.wohlben.qits.maintenance.error.NoSuchRepositoryException;
import eu.wohlben.qits.maintenance.error.NoSuchScanException;
import eu.wohlben.qits.maintenance.manifest.Globs;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side: the inventory and the bump log, in the shapes the API serves.
 *
 * <p>It sits in the domain jar because the shapes are the context's, not the web layer's —
 * {@code service}'s controllers do routing, roles and status codes and nothing else.
 *
 * <p><b>Pending is computed here on every read</b>, from the pins, the latest rows and the groups.
 * Three tables and no cache: a stored count would go stale the moment either half moved, and the
 * two halves move on different schedules by design.
 */
@ApplicationScoped
public class Inventory {

  @Inject MaintenanceStore store;

  @Inject BumpService bumps;

  @Inject BumpDispatcher dispatcher;

  /** The other half of the dependency picture — what the releases CONTAIN. See {@link ArtifactGraph}. */
  @Inject ArtifactGraph graph;

  /** The images a maven or npm pin names through the release that stamped them both. */
  @Inject CarriedImages carried;

  @Inject CarriedDaemons carriedDaemons;

  /** Every repository, with its groups and what each has pending. */
  public List<RepositoryDto> repositories() {
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    List<RepositoryDto> listing = new ArrayList<>();
    for (MtRepository row : store.repositories()) {
      List<MtPin> pins = store.pins(row.name);
      List<MtGroup> groups = store.groups(row.name);
      List<GroupDto> groupDtos = groups(row.name, pins, latest, groups);
      listing.add(
          new RepositoryDto(
              row.name,
              row.project,
              // Verbatim, unparsed. See MtRepository.archetype: the vocabulary is qits-projects'
              // and a word this service has not heard of is still the honest answer to "what is
              // this".
              row.archetype,
              row.lastScanAt,
              row.headSha,
              row.status,
              row.message,
              groupDtos.stream().mapToInt(GroupDto::pending).sum(),
              groupDtos));
    }
    return List.copyOf(listing);
  }

  /**
   * One repository with every pin it holds, and what its releases contain that no pin names.
   *
   * <p><b>Two lists, two facts, one join key.</b> {@code pins} is read from manifests and every row
   * has a line a bump can edit; {@code transitives} is read from the bills of materials of the
   * artifacts this repository RELEASED, and no line anywhere names one. They meet on
   * {@code (ecosystem, name)} — which is what removes a pin from the transitive list — and they are
   * never merged into one array.
   */
  public RepositoryDetailDto repository(String name) {
    MtRepository row = store.repository(name).orElseThrow(() -> new NoSuchRepositoryException(name));
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    List<MtPin> pins = store.pins(name);
    List<MtGroup> groups = store.groups(name);
    List<GroupDto> groupDtos = groups(name, pins, latest, groups);
    List<PinDto> pinDtos = new ArrayList<>();
    for (MtPin pin : pins) {
      pinDtos.add(pin(pin, latest, groups));
    }
    return new RepositoryDetailDto(
        row.name,
        row.project,
        row.archetype,
        row.lastScanAt,
        row.headSha,
        row.status,
        row.message,
        groupDtos.stream().mapToInt(GroupDto::pending).sum(),
        groupDtos,
        List.copyOf(pinDtos),
        graph.transitives(name, pins));
  }

  /**
   * Every dependency whose name matches the glob, with everyone who pins it.
   *
   * <p>A blank glob is every dependency: the page it serves is a searchable list, and an empty box
   * should show the list rather than nothing.
   *
   * <p><b>The kind filter is SERVER-SIDE and that is not an optimisation.</b> The two halves are two
   * pages — the platform's own releases and everybody else's — and they are the same split every
   * default group, every branch and both scan schedules already make. A client filtering an
   * unfiltered list would be a fourth place the same rule is spelled, and the first to disagree.
   *
   * @param kind INTERNAL or EXTERNAL, or null for both. A pin whose kind is neither — REACTOR,
   *     UNRESOLVED — is excluded by any filter and included by none, because it is not a half of
   *     that split at all.
   */
  public List<DependencyDto> dependencies(String glob, PinKind kind) {
    String pattern = glob == null || glob.isBlank() ? "*" : glob.trim();
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    Map<String, List<MtPin>> byDependency = new LinkedHashMap<>();
    for (MtPin pin : store.allPins()) {
      if (!Globs.matches(pattern, pin.name)) {
        continue;
      }
      if (kind != null && PendingChanges.kindOf(pin) != kind) {
        continue;
      }
      byDependency
          .computeIfAbsent(PendingChanges.key(pin.ecosystem, pin.name), key -> new ArrayList<>())
          .add(pin);
    }
    List<DependencyDto> listing = new ArrayList<>();
    for (Map.Entry<String, List<MtPin>> entry : byDependency.entrySet()) {
      MtPin first = entry.getValue().get(0);
      MtLatest row = latest.get(entry.getKey());
      List<DependencyDto.DependencyPinDto> pins = new ArrayList<>();
      for (MtPin pin : entry.getValue()) {
        pins.add(
            new DependencyDto.DependencyPinDto(
                pin.repository,
                pin.version,
                pin.manifestPath,
                PendingChanges.newerVersion(pin, latest).isPresent()));
      }
      listing.add(
          new DependencyDto(
              first.ecosystem,
              first.name,
              row == null ? null : row.latest,
              row == null ? null : row.checkedAt,
              row == null ? null : row.error,
              List.copyOf(pins)));
    }
    return List.copyOf(listing);
  }

  /**
   * <b>THE KEEP-SET THE ARTIFACT GC READS.</b> Every internal registry artifact any catalogued
   * repository's main branch still references, with the freshness of the inventory that says so.
   *
   * <p>qits-artifacts collects the registry against a handful of PIN SOURCES read once per run, and
   * this is the third: what the running services deploy, what the images name, and — here — what the
   * manifests pin. A version this answer carries is one a build would resolve tomorrow, so a
   * collection that dropped it would break a repository nobody has touched. The store already holds
   * exactly that fact, refreshed wholesale per repository on every main push and again nightly;
   * nothing is computed here beyond the filter and the order.
   *
   * <p><b>An empty inventory REFUSES rather than answering an empty keep-set</b>, and that is the
   * whole reason this method has a throw in it. The consumer is fail-closed — an unanswered or
   * unreadable source deletes nothing that run — while a successful answer is authoritative. So a
   * store that has never been filled must not say "nothing is referenced", because the sentence the
   * consumer would read is "every internal library on the platform is unreferenced". See
   * {@link EmptyInventoryException}, which also says why an UNREACHABLE repository is a different
   * case: it keeps the pins its last good scan read, so the keep-set is stale rather than absent,
   * and {@code lastScanAt} and {@code status} travel with the answer for the consumer to judge.
   *
   * <p><b>Two filters, and neither of them is a new rule.</b> The KIND is the one the scan already
   * stored on the row — {@link PendingChanges#kindOf(MtPin)}, so INTERNAL means here exactly what it
   * means on the repository page and in a bump's grouping. The ECOSYSTEM excludes GITLINK, which is
   * internal by construction and is the one ecosystem whose version is a commit sha rather than a
   * registry coordinate: a garbage collector handed one would look for an artifact of that name at
   * that version and find nothing. An ecosystem word this build does not know is excluded for the
   * same reason — there is no registry it names.
   *
   * <p><b>The rows are served as stored: no dedupe, no folding.</b> Five repositories pinning one
   * library are five rows, each naming its repository and its manifest path, because the consumer
   * folds and the fold it wants is its own. And the ORDER is total — ecosystem, name, version,
   * repository, manifest — so two reads over an unchanged store answer identically and a diff
   * between two runs is a change in the platform rather than in a query plan.
   *
   * <p><b>The one thing this computes is {@link CarriedImages} and {@link CarriedDaemons}, and it is
   * the same fact in the store it will actually be fetched from.</b> Container image versions and
   * the qits CLI's version are pom pins now — a maven coordinate whose own version IS the image tag,
   * or the daemon binary's store coordinate — so a repository pinning one references something it
   * never spells out, and a keep-set of literal manifest lines would leave that image or that binary
   * held by retention alone for as long as the bump sits on main unreleased. The derived rows are
   * {@code docker} and {@code daemon} rows carrying a {@code via}; everything else about them, the
   * order included, is what a stored row gets.
   */
  public PinSourceDto pins() {
    List<MtRepository> rows = store.repositories();
    if (rows.isEmpty()) {
      throw new EmptyInventoryException();
    }
    List<PinSourceDto.RepositoryStateDto> repositories = new ArrayList<>();
    for (MtRepository row : rows) {
      repositories.add(
          new PinSourceDto.RepositoryStateDto(row.name, row.status, row.lastScanAt, row.headSha));
    }
    // Read once, filtered here rather than in a query: the whole set is small, and the kind is the
    // stored word this class reads through PendingChanges everywhere else.
    List<PinSourceDto.ArtifactPinDto> pins = new ArrayList<>();
    for (MtPin pin : store.allPins()) {
      if (PendingChanges.kindOf(pin) != PinKind.INTERNAL) {
        continue;
      }
      Optional<Ecosystem> ecosystem = Ecosystem.of(pin.ecosystem);
      if (ecosystem.isEmpty() || ecosystem.get() == Ecosystem.GITLINK) {
        continue;
      }
      pins.add(
          // The column is already the wire name — `replaceInventory` writes `Ecosystem.wireName()`
          // and the lookup above proved it is one this build knows — so it is served as stored.
          new PinSourceDto.ArtifactPinDto(
              pin.ecosystem, pin.name, pin.version, pin.repository, pin.manifestPath, null));
    }
    // …and the images and daemon binaries those maven and npm pins name without spelling out.
    // Derived from the STORED rows — snapshotted here, so the second derivation cannot read the
    // first one's output as if a manifest had written it — rather than from the store again, so the
    // halves of one answer can never be read at two different moments. See CarriedImages and
    // CarriedDaemons for why the keep would otherwise have a hole in it in each store.
    List<PinSourceDto.ArtifactPinDto> stored = List.copyOf(pins);
    pins.addAll(carried.resolve(stored));
    pins.addAll(carriedDaemons.resolve(stored));
    pins.sort(PIN_ORDER);
    return new PinSourceDto(Instant.now(), List.copyOf(repositories), List.copyOf(pins));
  }

  /** The total order the pin source is served in. Every field is non-null on a stored row. */
  private static final Comparator<PinSourceDto.ArtifactPinDto> PIN_ORDER =
      Comparator.comparing(PinSourceDto.ArtifactPinDto::ecosystem)
          .thenComparing(PinSourceDto.ArtifactPinDto::name)
          .thenComparing(PinSourceDto.ArtifactPinDto::version)
          .thenComparing(PinSourceDto.ArtifactPinDto::repository)
          .thenComparing(PinSourceDto.ArtifactPinDto::manifestPath);

  /** The newest bumps, of one repository or of all of them. */
  public List<BumpDto> bumps(String repository, int limit) {
    return store.bumps(repository, limit).stream().map(Inventory::bump).toList();
  }

  /** The newest bumps still on their way, of every repository. */
  public eu.wohlben.qits.maintenance.dto.PendingBumpsDto pendingBumps(int limit) {
    return new eu.wohlben.qits.maintenance.dto.PendingBumpsDto(
        store.pendingBumps(limit).stream().map(Inventory::bump).toList());
  }

  /** One bump. */
  public BumpDto bump(UUID id) {
    return bump(store.bump(id).orElseThrow(() -> new NoSuchBumpException(id)));
  }

  /** One scan, which is what a client polls after a 202. */
  /**
   * The dispatch window as it stands — <b>and it answers whether or not there is one</b>.
   *
   * <p>{@code open} is computed here rather than stored: a row whose {@code closesAt} has passed but
   * which no tick has reached yet is a real state, and reporting it as open would be a lie a reader
   * could act on. With no row at all the two timestamps are null and {@code open} is false, and the
   * rest of the answer — the outcome, the counts and the whole owed queue — is the same reasoning
   * the tick makes. A 404 there was the door saying "no window" to somebody asking "why has nothing
   * been dispatched", which is the one question it exists to answer.
   *
   * <p><b>The read runs the tick's own reasoning and acts on none of it</b> — {@link
   * BumpDispatcher#explain} — so that "there are pending bumps and nothing is queued" has an answer
   * at the door rather than in three services' logs. It costs what a tick costs: the candidate walk,
   * one listing read from qits-ci and, for a held candidate, one release-request read (inside its
   * ttl, usually a cache hit). Nothing here closes a window and nothing here dispatches.
   */
  public BumpWindowDto bumpWindow(Instant now) {
    Optional<MtBumpWindow> row = store.bumpWindowRow();
    BumpDispatcher.Decision decision = dispatcher.explain(now);
    return new BumpWindowDto(
        row.map(one -> one.openedAt).orElse(null),
        row.map(one -> one.closesAt).orElse(null),
        row.filter(one -> now.isBefore(one.closesAt)).isPresent(),
        decision.outcome(),
        decision.summary(),
        decision.inFlight(),
        decision.allowed(),
        decision.ciActive(),
        decision.owed(),
        decision.held(),
        decision.stalled().stream()
            .map(
                one ->
                    new BumpWindowDto.StalledBumpDto(
                        one.repository(),
                        one.group(),
                        one.requestId(),
                        one.state(),
                        one.reason()))
            .toList(),
        decision.queue().stream()
            .map(
                one ->
                    new BumpWindowDto.OwedBumpDto(
                        one.repository(),
                        one.group(),
                        one.changes(),
                        one.reason(),
                        one.detail()))
            .toList(),
        decision.pick() == null ? null : decision.pick().candidate().repository());
  }

  public ScanDto scan(UUID id) {
    MtScan row = store.scan(id).orElseThrow(() -> new NoSuchScanException(id));
    return new ScanDto(
        row.id,
        row.scope,
        row.repository,
        row.trigger,
        row.status,
        row.startedAt,
        row.finishedAt,
        row.message);
  }

  private List<GroupDto> groups(
      String repository, List<MtPin> pins, Map<String, MtLatest> latest, List<MtGroup> groups) {
    Map<String, Integer> counts = PendingChanges.countByGroup(pins, latest, groups);
    Map<String, MtBranch> branches = new LinkedHashMap<>();
    for (MtBranch branch : bumps.branches(repository)) {
      branches.put(branch.groupName, branch);
    }
    List<GroupDto> listing = new ArrayList<>();
    for (MtGroup group : groups) {
      MtBranch branch = branches.get(group.name);
      listing.add(
          new GroupDto(
              group.name,
              group.source,
              group.kind,
              branch == null ? BumpService.BRANCH_PREFIX + group.name : branch.branch,
              branch == null ? BranchState.NONE.name() : branch.state,
              branch == null ? null : branch.headSha,
              counts.getOrDefault(group.name, 0)));
    }
    return List.copyOf(listing);
  }

  private static PinDto pin(MtPin pin, Map<String, MtLatest> latest, List<MtGroup> groups) {
    MtLatest row = latest.get(PendingChanges.key(pin.ecosystem, pin.name));
    Optional<String> newer = PendingChanges.newerVersion(pin, latest);
    // DIRECT, always: a pin is a line an author wrote. The field exists because the detail page now
    // serves transitives beside these, and a row has to say which it is.
    return PinDto.direct(
        pin.manifestPath,
        pin.ecosystem,
        pin.name,
        pin.version,
        pin.range,
        pin.kind,
        row == null ? null : row.latest,
        row == null ? null : row.error,
        newer.isPresent(),
        PendingChanges.groupOf(pin, groups).orElse(null),
        pin.location);
  }

  private static BumpDto bump(MtBump row) {
    return new BumpDto(
        row.id,
        row.repository,
        row.groupName,
        // Read through the enum rather than passed straight out: a row written before the column
        // existed carries null, and GROUP is what it is rather than a gap in the answer.
        eu.wohlben.qits.maintenance.model.BumpMode.of(row.mode).name(),
        row.branch,
        row.environment,
        row.trigger,
        row.status,
        row.ciEventId,
        row.ciRunId,
        row.ciRunId == null || row.ciRunId.isBlank()
            ? List.of()
            : List.of(row.ciRunId.split(",")),
        eu.wohlben.qits.maintenance.model.BumpMode.of(row.mode)
                == eu.wohlben.qits.maintenance.model.BumpMode.BASELINES
            ? eu.wohlben.qits.maintenance.bump.CiClient.BASELINES_CONFIG_PATH
            : eu.wohlben.qits.maintenance.bump.CiClient.CONFIG_PATH,
        row.ciRunStatus,
        row.startedAt,
        row.finishedAt,
        row.message,
        row.resultSha,
        row.releaseRequestId,
        row.releaseState,
        row.releaseDetail,
        row.releaseStateAt,
        BumpService.changes(row));
  }
}

package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.automation.DependencyBumpAutomation;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.config.QuietHours;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * A MAIN-ONLY RELEASE REQUEST FOR EVERY REPOSITORY OWED A BUMP AND WITHOUT ONE — as many per tick as
 * qits-ci has free slots, and always from the bottom of the chain.
 *
 * <h2>What it does</h2>
 *
 * <p>A repository is OWED when one of its INTERNAL pins is behind the newest release ({@link
 * PendingChanges}, read off main). Where the repository already has an open release request, that
 * request's pre-run writes the bump — its {@code dependency-bump} automation plans every pin at the
 * fold, and {@code UpstreamReplan} re-plans it when an upstream releases. Where it has none, this
 * opens one: a MAIN-ONLY {@code LOWEST} request, remembered as this service's ({@code
 * mt_release_request}, purpose {@code MAIN_ONLY}) with the pending set it was opened for. Its
 * pre-run writes the bump — one commit, one build — and a pre-run that finds nothing to write
 * withdraws it again.
 *
 * <p><b>This used to cut a {@code maintenance/<group>} branch</b>, under a dispatch window that a
 * cron, a door or the debt itself opened. qits-1133 replaced the branch with the request (R2) and
 * removed the group path and the window with it (R5). The gates the group path learned the hard way
 * are kept, because a main-only request is still a CI build: the capacity gate, bottom of the chain
 * first, least recently dispatched first, the quiet hours and the refusal set.
 *
 * <h2>The gates, in the order they are asked</h2>
 *
 * <ol>
 *   <li><b>The switches</b>: {@code qits.maintenance.bump.enabled}, {@code
 *       qits.maintenance.bump.internal.auto}, and the {@code dependency-bump} kind's own switch — a
 *       main-only request is only worth opening while something will write its bump.
 *   <li><b>Is anything owed</b> that is not HELD: a repository with an open release request of any
 *       kind is held (its own pre-run carries the bump), and so is one whose newest main-only
 *       request was opened for exactly the pending set it has now and is on its way, shipped, or was
 *       withdrawn by its pre-run — the same set would only find nothing again. A person's withdrawal
 *       frees it. An unreadable answer holds, as every read here does.
 *   <li><b>Is this a quiet hour</b> ({@link MaintenanceConfig#bumpQuietHours()}). Empty by default.
 *   <li><b>How many of qits-ci's slots are free</b> — {@link CiClient#queue()}, one read of {@code
 *       GET /ci/api/runs/queue}, and an unreadable snapshot counts as BUSY. {@code free = slots -
 *       active - requested}: the connected, unquarantined runners' slots, minus every run qits-ci
 *       holds (running or queued), minus this service's automation rows qits-ci has not accepted yet.
 *       A RUNNING row already has its run in {@code active}, and counting it twice would hide a slot.
 *       How much qits-ci can take is a fact qits-ci reports about its runners, never a number
 *       configured here (qits-882).
 *   <li><b>Which owed repositories are READY</b> — up to {@code free} of them, from {@link
 *       BumpOrder#nextUpTo}: an upstream that is itself owed goes before its consumer, and never more
 *       than one cycle break per tick.
 * </ol>
 *
 * <h2>Recomputed every tick, never a frozen plan</h2>
 *
 * <p>{@link PendingChanges} is computed on every read, so the candidate list costs one inventory
 * walk and buys the whole point of the ordering: once the bottom repository releases and the next
 * scan has read it, its consumers' pending sets name the version that was just cut.
 *
 * <h2>Least-recently-dispatched first</h2>
 *
 * <p>{@link BumpOrder} decides which candidates are ready, and among equally ready ones it takes the
 * first it was handed. So the walk's order is the arbiter of everything the topology does not
 * decide, and for as long as it was the store's — the alphabet — the tail of the alphabet was the
 * tail of every fan-out (measured 2026-09-13: {@code qits-workspace-daemon} dispatched nine
 * repositories after {@code qits-projects-daemon} for the same two jars, for no reason but its
 * name). The candidates are ordered by {@link MaintenanceStore#lastDispatchedAt()} ascending, a
 * repository never reached counting as {@link Instant#EPOCH}, the name the final tiebreak.
 */
@ApplicationScoped
public class BumpDispatcher {

  private static final Logger LOG = Logger.getLogger(BumpDispatcher.class);

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject CiClient ci;

  @Inject ReleaseRequestClient releases;

  @Inject ArtifactGraph artifacts;

  /** The kind that writes the bump a main-only request is opened for. */
  @Inject DependencyBumpAutomation dependencyBump;

  /**
   * Repositories whose request was REFUSED, and when — not asked for again for {@link
   * MaintenanceConfig#bumpRefusalTtl()}.
   *
   * <p>In memory on purpose: a refusal is a guess that something is wrong with one repository right
   * now, a restart forgives it, and the cost of forgiving too early is one more refused ask.
   */
  private final Map<String, Instant> refused = new LinkedHashMap<>();

  /** What qits-projects last said about a release request, and when it said it. */
  private record Seen(ReleaseRequestClient.ReleaseState state, Instant at) {}

  private final Map<String, Seen> releaseStates = new ConcurrentHashMap<>();

  /** A repository's open release requests, as last read — cached exactly as {@link Seen} is. */
  private record SeenListing(ReleaseRequestClient.Listing listing, Instant at) {}

  private final Map<String, SeenListing> listings = new ConcurrentHashMap<>();

  /**
   * Every repository owed a bump this tick that could still be sent one, and the ones this gate
   * declined to ask for until their refusal is forgiven.
   */
  public record Assessment(List<BumpOrder.Candidate> candidates, List<String> refused) {}

  /**
   * What the gate decided and everything it decided it from.
   *
   * @param outcome the short name of the gate that answered
   * @param summary the sentence for a person
   * @param inFlight automation rows not yet ended, or null when the gate answered before asking
   * @param slots qits-ci's connected, unquarantined runner slots, null when it was not asked or
   *     could not be read
   * @param free how many of those this tick may fill — slots minus qits-ci's active runs minus our
   *     REQUESTED rows, floored at zero — null when it was not asked or could not be read
   * @param ciActive what qits-ci holds, running and queued, null when it was not asked or could not
   *     be read
   * @param owed how many repositories are owed a bump and could still be sent one
   * @param held how many of those are held by a request already on its way
   * @param picks what would be dispatched this tick, in order — empty on every outcome but DISPATCH
   */
  public record Decision(
      String outcome,
      String summary,
      Integer inFlight,
      Integer slots,
      Integer free,
      Integer ciActive,
      int owed,
      int held,
      List<BumpOrder.Pick> picks) {

    static Decision of(String outcome, String summary) {
      return new Decision(outcome, summary, null, null, null, null, 0, 0, List.of());
    }

    /** The first pick, or null. */
    public BumpOrder.Pick pick() {
      return picks.isEmpty() ? null : picks.get(0);
    }
  }

  /**
   * One dispatch decision, and every pick it made sent in order.
   *
   * <p><b>A refusal of one pick does not stop the rest.</b> It lands in {@link #refused} with its
   * WARN and the next pick goes: the picks are all READY, so none was waiting on the one that failed.
   *
   * @return the ids of the release requests that were opened, empty on every other outcome — none
   *     of which is a failure
   */
  public List<UUID> tick() {
    Decision decision = explain(Instant.now());
    List<UUID> sent = new ArrayList<>();
    int stillOwed = decision.owed();
    for (BumpOrder.Pick pick : decision.picks()) {
      stillOwed--;
      dispatch(pick, decision.owed(), stillOwed).ifPresent(sent::add);
    }
    return List.copyOf(sent);
  }

  /** The gates, in the order they are asked, with nothing done about the answer. */
  public Decision explain(Instant now) {
    if (!config.bumpEnabled()) {
      return Decision.of("DISABLED", "qits.maintenance.bump.enabled is false");
    }
    if (!config.bumpInternalAuto()) {
      return Decision.of("DISABLED", "qits.maintenance.bump.internal.auto is false");
    }
    if (!dependencyBump.enabled()) {
      return Decision.of(
          "DISABLED",
          DependencyBumpAutomation.SWITCH + " is false, so nothing would write the bump");
    }

    List<MtBump> active = store.activeBumps();
    int inFlight = active.size();
    // A REQUESTED row is a build this service asked qits-ci for and qits-ci has not accepted yet, so
    // it is in nobody's queue listing — it would be a slot counted free twice.
    int requested =
        (int)
            active.stream().filter(bump -> BumpStatus.REQUESTED.name().equals(bump.status)).count();

    List<BumpOrder.Candidate> candidates = assess().candidates();
    int held = (int) candidates.stream().filter(BumpOrder.Candidate::held).count();

    if (candidates.isEmpty()) {
      return new Decision(
          "NOTHING_OWED", "nothing is owed a bump", inFlight, null, null, null, 0, 0, List.of());
    }

    QuietHours quiet = config.bumpQuietHours();
    if (quiet.covers(now, config.timeZone())) {
      LOG.debugf(
          "%d bump(s) are owed but %s is a quiet hour; nothing is dispatched.",
          candidates.size(), now);
      return new Decision(
          "QUIET_HOURS",
          candidates.size()
              + " bump(s) are owed, and this hour is quiet"
              + " (qits.maintenance.bump.dispatch.quiet-hours="
              + quiet
              + ")",
          inFlight,
          null,
          null,
          null,
          candidates.size(),
          held,
          List.of());
    }

    CiClient.QueueState queue = ci.queue();
    if (!queue.readable()) {
      // Unreadable is BUSY. Dispatching blind is precisely the wavefront this gate exists for.
      LOG.debugf("qits-ci's queue could not be read (%s); nothing is dispatched.", queue.error());
      return new Decision(
          "CI_UNREADABLE",
          "qits-ci's queue could not be read (" + queue.error() + "), which counts as busy",
          inFlight,
          null,
          null,
          null,
          candidates.size(),
          held,
          List.of());
    }
    int slots = queue.slots();
    int ciActive = queue.active();
    if (slots == 0) {
      // No runner connected, or every one quarantined: nothing would claim a run.
      return new Decision(
          "NO_SLOTS",
          "qits-ci has no slot to run anything: no runner is connected, or every one is quarantined",
          inFlight,
          0,
          0,
          ciActive,
          candidates.size(),
          held,
          List.of());
    }
    int free = Math.max(0, slots - ciActive - requested);
    if (free == 0) {
      return new Decision(
          "CI_BUSY",
          "qits-ci has "
              + slots
              + " slot(s), "
              + ciActive
              + " active run(s) and "
              + requested
              + " bump(s) waiting to reach it; nothing is free",
          inFlight,
          slots,
          0,
          ciActive,
          candidates.size(),
          held,
          List.of());
    }

    List<BumpOrder.Pick> picks = BumpOrder.nextUpTo(candidates, artifacts.producers(), free);
    if (picks.isEmpty()) {
      return new Decision(
          "WAITING_ON_RELEASES",
          "all " + candidates.size() + " owed bump(s) wait on a release request already open",
          inFlight,
          slots,
          free,
          ciActive,
          candidates.size(),
          held,
          List.of());
    }
    List<String> names = new ArrayList<>();
    for (BumpOrder.Pick pick : picks) {
      names.add(pick.candidate().repository());
    }
    return new Decision(
        "DISPATCH",
        (picks.size() == 1 ? "the next bump is " : "the next " + picks.size() + " bumps are ")
            + String.join(", ", names)
            + " ("
            + free
            + " of "
            + slots
            + " slot(s) free)",
        inFlight,
        slots,
        free,
        ciActive,
        candidates.size(),
        held,
        List.copyOf(picks));
  }

  private Optional<UUID> dispatch(BumpOrder.Pick pick, int owed, int stillOwed) {
    BumpOrder.Candidate candidate = pick.candidate();
    if (pick.cycleBroken()) {
      // Every candidate waits on another candidate. The graph is not guaranteed acyclic and this
      // must not become a tick in which nothing ever moves.
      LOG.warnf(
          "Every one of the %d owed bumps waits on another; %s is dispatched anyway (it waits on"
              + " %s), and the next tick asks again.",
          owed, candidate.repository(), pick.blockedBy());
    }
    return openMainOnly(candidate, stillOwed);
  }

  /**
   * Opens a MAIN-ONLY {@code LOWEST} release request for one owed repository, and remembers it as
   * this service's with the pending set it was opened for. The answer is the request's id, read as
   * a UUID.
   */
  private Optional<UUID> openMainOnly(BumpOrder.Candidate candidate, int stillOwed) {
    Optional<MtRepository> row = store.repository(candidate.repository());
    String repoId = row.map(repository -> repository.catalogId).orElse(null);
    if (row.isEmpty() || repoId == null || repoId.isBlank()) {
      refuse(candidate, "it has no catalog id to address qits-projects with");
      return Optional.empty();
    }
    String main = row.get().mainBranchOrDefault();
    ReleaseRequestClient.RequestResult result =
        releases.requestMainOnly(
            repoId,
            main,
            ReleaseRequestClient.summary(candidate.group(), candidate.changes().size()));
    switch (result.outcome()) {
      case REQUESTED -> {
        store.recordOpenedRequest(
            result.requestId(),
            candidate.repository(),
            main,
            MtReleaseRequest.MAIN_ONLY,
            candidate.changes(),
            Instant.now());
        listings.remove(repoId);
        LOG.infof(
            "Opened the main-only release request %s of %s for its pre-run to bump %d"
                + " dependencies; %d repositor(ies) are still owed one.",
            result.requestId(), candidate.repository(), candidate.changes().size(), stillOwed);
        try {
          return Optional.of(UUID.fromString(result.requestId()));
        } catch (IllegalArgumentException e) {
          return Optional.empty();
        }
      }
      case RETRY -> {
        LOG.warnf(
            "The main-only release request of %s was not answered: %s; the next tick asks again",
            candidate.repository(), result.message());
        return Optional.empty();
      }
      default -> {
        refuse(candidate, result.message());
        return Optional.empty();
      }
    }
  }

  private void refuse(BumpOrder.Candidate candidate, String why) {
    synchronized (refused) {
      refused.put(candidate.repository(), Instant.now());
    }
    LOG.warnf(
        "Could not open the main-only release request of %s: %s; it is not asked for again for %s.",
        candidate.repository(), why, config.bumpRefusalTtl());
  }

  /**
   * Whether a repository owed a bump is HELD: it has ANY open release request — whose pre-run
   * carries the bump, and which the upstream hook re-plans — or the main-only request this service
   * last opened for the same pending set is on its way, shipped, or was withdrawn because its
   * pre-run found nothing to write. A person's withdrawal frees it; an unreadable answer holds.
   */
  private boolean held(MtRepository row, List<Change> pending) {
    if (row.catalogId == null || row.catalogId.isBlank()) {
      return false;
    }
    ReleaseRequestClient.Listing listing = openRequests(row.catalogId);
    if (!listing.readable() || !listing.requests().isEmpty()) {
      return true;
    }
    Optional<MtReleaseRequest> newest =
        store.newestOpenedRequest(row.name, MtReleaseRequest.MAIN_ONLY);
    if (newest.isEmpty() || !sameChanges(storedChanges(newest.get().changes), pending)) {
      return false;
    }
    if (newest.get().withdrawnAt != null) {
      return true;
    }
    String requestId = newest.get().requestId;
    Instant now = Instant.now();
    Seen seen = releaseStates.get(requestId);
    ReleaseRequestClient.ReleaseState state;
    if (seen != null && seen.at().plus(config.bumpReleaseStateTtl()).isAfter(now)) {
      state = seen.state();
    } else {
      state = releases.state(row.catalogId, requestId);
      releaseStates.put(requestId, new Seen(state, now));
    }
    return !state.withdrawn();
  }

  private ReleaseRequestClient.Listing openRequests(String repoId) {
    Instant now = Instant.now();
    SeenListing seen = listings.get(repoId);
    if (seen != null && seen.at().plus(config.bumpReleaseStateTtl()).isAfter(now)) {
      return seen.listing();
    }
    ReleaseRequestClient.Listing listing = releases.openRequests(repoId);
    listings.put(repoId, new SeenListing(listing, now));
    return listing;
  }

  private static List<Change> storedChanges(String json) {
    List<Change> changes = new ArrayList<>();
    for (Map<String, Object> entry : MaintenanceStore.readObjects(json)) {
      changes.add(
          new Change(
              text(entry, "ecosystem"),
              text(entry, "manifestPath"),
              text(entry, "name"),
              text(entry, "from"),
              text(entry, "to"),
              text(entry, "location")));
    }
    return changes;
  }

  private static String text(Map<String, Object> entry, String key) {
    Object value = entry.get(key);
    return value == null ? null : value.toString();
  }

  /** Whether this repository's refusal is still standing, forgiving the ones that have aged out. */
  private boolean isRefused(String repository, Instant now) {
    synchronized (refused) {
      Instant at = refused.get(repository);
      if (at == null) {
        return false;
      }
      if (at.plus(config.bumpRefusalTtl()).isAfter(now)) {
        return true;
      }
      refused.remove(repository);
      return false;
    }
  }

  /** Every repository owed an INTERNAL bump right now, least-recently-dispatched first. */
  public List<BumpOrder.Candidate> candidates() {
    return assess().candidates();
  }

  /**
   * The walk: every OK repository whose INTERNAL group has something pending, flagged held when a
   * request already carries it, with the refused ones set aside.
   *
   * <p><b>Held is a flag rather than a skip</b>: a held repository is still owed, and dropping it
   * would let its consumers go before the release they are waiting for.
   */
  public Assessment assess() {
    String group = GroupConfig.DEFAULT_GROUP;
    Instant now = Instant.now();
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    List<BumpOrder.Candidate> candidates = new ArrayList<>();
    List<String> refusals = new ArrayList<>();
    for (MtRepository row : store.repositories()) {
      if (!RepositoryStatus.OK.name().equals(row.status)) {
        continue;
      }
      List<MtGroup> groups = store.groups(row.name);
      if (groups.stream().noneMatch(candidate -> candidate.name.equals(group))) {
        continue;
      }
      List<MtPin> pins = store.pins(row.name);
      List<Change> changes = PendingChanges.forGroup(pins, latest, groups, group);
      if (changes.isEmpty()) {
        continue;
      }
      if (isRefused(row.name, now)) {
        refusals.add(row.name);
        continue;
      }
      candidates.add(new BumpOrder.Candidate(row.name, group, changes, held(row, changes)));
    }
    if (!candidates.isEmpty()) {
      Map<String, Instant> lastDispatched = store.lastDispatchedAt();
      candidates.sort(
          Comparator.comparing(
                  (BumpOrder.Candidate candidate) ->
                      lastDispatched.getOrDefault(candidate.repository(), Instant.EPOCH))
              .thenComparing(BumpOrder.Candidate::repository));
    }
    return new Assessment(List.copyOf(candidates), List.copyOf(refusals));
  }

  /**
   * Whether two change lists ask for the same thing, order-insensitively — compared on (ecosystem,
   * name, from, to): the manifest path and the location are how an edit is applied, not what it is.
   */
  private static boolean sameChanges(List<Change> recorded, List<Change> pending) {
    return keys(recorded).equals(keys(pending));
  }

  private static Set<String> keys(List<Change> changes) {
    Set<String> keys = new LinkedHashSet<>();
    for (Change change : changes) {
      keys.add(change.ecosystem() + "\0" + change.name() + "\0" + change.from() + "\0" + change.to());
    }
    return keys;
  }
}

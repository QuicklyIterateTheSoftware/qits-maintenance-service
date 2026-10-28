package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.automation.DependencyBumpAutomation;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.config.QuietHours;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.dto.BumpStandingDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.latest.LatestResolver;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * A MAIN-ONLY RELEASE REQUEST FOR EVERY REPOSITORY OWED A BUMP AND WITHOUT ONE — as many at a time
 * as qits-ci has room for, and always from the bottom of the chain.
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
 * <h2>The capacity gate is CONFIGURED again, not read (epic qits-1481)</h2>
 *
 * <p><b>Behaviour change: qits-882's free slots were read off qits-ci's connected, unquarantined
 * runners, and qits-ci no longer has any to report.</b> A CI run is a lease now, and how many run at
 * once is qits-system's {@code SlotAllocator} to grant against every target's minimums — a fact
 * qits-ci itself does not hold any more, so {@code GET /ci/api/runs/queue} lost {@code runners}
 * (only {@code running} and {@code queued} are left, see {@link CiClient#queue()}). Reading no
 * capacity is not the same as having none to ration by: this service still must not flood a
 * resource-restricted platform with main-only requests, so {@link
 * MaintenanceConfig#bumpCiActiveCap()} replaces the read number with a configured one (default 4).
 * {@code free = max(0, cap - ciActive - requested)} — qits-ci's running-plus-queued count, minus
 * this service's own automation rows it has not accepted yet (a {@code REQUESTED} row is in no
 * listing qits-ci answers, and counting it twice would hide room that is not really free). An
 * unreadable snapshot still counts as BUSY, and a cap of zero still means nothing is ever free
 * (the old {@code NO_SLOTS} wording now says the cap itself, not a missing runner).
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
 *       withdrawn by its pre-run — the same set would only find nothing again — for as long as that
 *       withdrawal is younger than {@link MaintenanceConfig#bumpNothingFoundHold()}: a withdrawal
 *       judged by a step that was itself broken must not hold forever (qits-1696). A person's
 *       withdrawal frees it. An unreadable answer holds, as every read here does.
 *   <li><b>Is this a quiet hour</b> ({@link MaintenanceConfig#bumpQuietHours()}). Empty by default.
 *   <li><b>How much of the configured cap is free</b> — {@link CiClient#queue()}, one read of {@code
 *       GET /ci/api/runs/queue}, and an unreadable snapshot counts as BUSY. {@code free = cap -
 *       active - requested}, floored at zero; a cap of zero is {@code NO_SLOTS} rather than {@code
 *       CI_BUSY} — nothing this service sends is ever allowed to run.
 *   <li><b>Which owed repositories are READY</b> — up to {@code free} of them, from {@link
 *       BumpOrder#nextUpTo}: an upstream that is itself owed goes before its consumer, and never more
 *       than one cycle break per tick — made with whatever room the READY ones left, so a knot is
 *       no longer starved by unrelated READY work (qits-1696).
 * </ol>
 *
 * <h2>The standing is readable (qits-1696)</h2>
 *
 * <p>{@link #standing(Instant)} is this same reasoning answered to a person — {@code GET
 * /maintenance/api/bumps/standing} — and it exists because the knot that starved for hours on
 * 2026-10-28 was invisible from every surface: a repository owed a bump and never sent one looks the
 * same as a scheduler that is dead. It opens nothing, refuses nothing and records nothing. What it
 * does share with {@link #tick()} is two pieces of memory, and neither moves a decision outside the
 * bounds a tick already lives with: the qits-projects answers {@link #held} caches, which a read
 * here may write — so the next tick can reuse an answer up to {@link
 * MaintenanceConfig#bumpReleaseStateTtl()} old where it would have asked afresh, which is the very
 * staleness every tick already accepts from the tick fifteen seconds before it — and the refusal
 * set, which a read here only ever forgives where the refusal has aged out, exactly as the next
 * tick would at the same instant. Nothing is ever added to the refusal set by a read.
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
 *
 * <p><b>Except a repository owed a RE-OFFER, which goes before all of them</b> (qits-1578). When a
 * {@code dependency-bump} ends SUPERSEDED because its release request closed before the bump could
 * land, {@code mt_repository.reoffer_owed_at} is set; such a repository was reached recently by
 * construction, so least-recently-dispatched alone would put it last behind the per-tick cap. The
 * mark sorts it ahead of every unmarked one (each group keeping the order above), never decides
 * whether it is held, and is cleared when its main-only request is opened.
 */
@ApplicationScoped
public class BumpDispatcher {

  private static final Logger LOG = Logger.getLogger(BumpDispatcher.class);

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject CiClient ci;

  @Inject ReleaseRequestClient releases;

  @Inject ArtifactGraph artifacts;

  /** The version lists a shared maven property is decided against (qits-1206). */
  @Inject LatestResolver resolver;

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
   * @param cap {@link MaintenanceConfig#bumpCiActiveCap()} as asked this tick, null when it was not
   *     asked or the queue could not be read
   * @param free how many of the cap this tick may fill — cap minus qits-ci's active runs minus our
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
      Integer cap,
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
    return explain(now, this::assess);
  }

  /**
   * THE STANDING: what {@link #explain} decides, and the whole owed set it decided it from, with
   * nothing done about either (qits-1696) — the read behind {@code GET
   * /maintenance/api/bumps/standing}.
   *
   * <p><b>One walk serves both halves.</b> {@link #assess} asks qits-projects about every owed
   * repository; reading it once and handing the same answer to the decision and to the listing means
   * the two can never disagree about who is held, and the door costs one walk, not two. The walk is
   * made even when a switch answers first, because "disabled, and here is what it would have
   * dispatched" is the very picture a person reaches for this door to see. See the class doc for the
   * little a read shares with the next tick.
   */
  public BumpStandingDto standing(Instant now) {
    Assessment assessment = assess();
    Decision decision = explain(now, () -> assessment);
    Map<String, String> producers = artifacts.producers();
    List<BumpStandingDto.StandingEntryDto> owed = new ArrayList<>();
    for (BumpOrder.Standing entry : BumpOrder.standing(assessment.candidates(), producers)) {
      List<BumpStandingDto.StandingChangeDto> changes = new ArrayList<>();
      for (Change change : entry.candidate().changes()) {
        changes.add(
            new BumpStandingDto.StandingChangeDto(
                change.ecosystem(), change.name(), change.from(), change.to()));
      }
      owed.add(
          new BumpStandingDto.StandingEntryDto(
              entry.candidate().repository(),
              entry.reason(),
              List.copyOf(new TreeSet<>(entry.blockedBy())),
              List.copyOf(changes)));
    }
    List<BumpStandingDto.StandingPickDto> picks = new ArrayList<>();
    for (BumpOrder.Pick pick : decision.picks()) {
      picks.add(
          new BumpStandingDto.StandingPickDto(
              pick.candidate().repository(),
              pick.cycleBroken(),
              List.copyOf(new TreeSet<>(pick.blockedBy()))));
    }
    return new BumpStandingDto(
        decision.outcome(),
        decision.summary(),
        decision.cap(),
        decision.free(),
        decision.ciActive(),
        // Off the walk rather than the decision: a switch answers before the decision counts, and
        // "disabled, 0 owed" beside a listing of twelve would be the decision's bookkeeping talking.
        assessment.candidates().size(),
        (int) assessment.candidates().stream().filter(BumpOrder.Candidate::held).count(),
        List.copyOf(picks),
        List.copyOf(owed),
        assessment.refused());
  }

  private Decision explain(Instant now, Supplier<Assessment> assessment) {
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
    // it is in nobody's queue listing — it would be counted free twice.
    int requested =
        (int)
            active.stream().filter(bump -> BumpStatus.REQUESTED.name().equals(bump.status)).count();

    List<BumpOrder.Candidate> candidates = assessment.get().candidates();
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

    // qits-ci reports no capacity of its own any more (epic qits-1481); this read is for whether it
    // can be asked at all, and for how much of the CONFIGURED cap is already in use.
    CiClient.QueueState queue = ci.queue();
    if (!queue.readable()) {
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
    int cap = config.bumpCiActiveCap();
    int ciActive = queue.active();
    if (cap == 0) {
      // The cap itself refuses everything: a platform that wants no automated bump traffic on CI
      // at all, rather than qits-882's "no runner is connected".
      return new Decision(
          "NO_SLOTS",
          "qits.maintenance.bump.ci-active-cap is 0; nothing may be dispatched",
          inFlight,
          0,
          0,
          ciActive,
          candidates.size(),
          held,
          List.of());
    }
    int free = Math.max(0, cap - ciActive - requested);
    if (free == 0) {
      return new Decision(
          "CI_BUSY",
          "qits-ci's queue is at its cap of "
              + cap
              + " ("
              + ciActive
              + " active run(s) and "
              + requested
              + " bump(s) waiting to reach it); nothing is free",
          inFlight,
          cap,
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
          "all "
              + candidates.size()
              + " owed bump(s) wait, directly or through another owed bump, on a release already on"
              + " its way",
          inFlight,
          cap,
          free,
          ciActive,
          candidates.size(),
          held,
          List.of());
    }
    List<String> names = new ArrayList<>();
    for (BumpOrder.Pick pick : picks) {
      // A cycle break rides along with READY work since qits-1696, so the summary has to say which
      // one is the guess — otherwise it reads as one more READY repository.
      names.add(
          pick.cycleBroken()
              ? pick.candidate().repository()
                  + " (breaking a cycle, it waits on "
                  + String.join(", ", new TreeSet<>(pick.blockedBy()))
                  + ")"
              : pick.candidate().repository());
    }
    return new Decision(
        "DISPATCH",
        (picks.size() == 1 ? "the next bump is " : "the next " + picks.size() + " bumps are ")
            + String.join(", ", names)
            + " ("
            + free
            + " of "
            + cap
            + " free)",
        inFlight,
        cap,
        free,
        ciActive,
        candidates.size(),
        held,
        List.copyOf(picks));
  }

  private Optional<UUID> dispatch(BumpOrder.Pick pick, int owed, int stillOwed) {
    BumpOrder.Candidate candidate = pick.candidate();
    if (pick.cycleBroken()) {
      // It and everything it waits on wait on each other. The graph is not guaranteed acyclic, and
      // since qits-1696 a knot is broken beside READY work rather than only once nothing at all is
      // READY — which an estate of thirty owed repositories never was.
      LOG.warnf(
          "%s and the owed bumps it waits on (%s) wait on each other in a cycle; it is dispatched"
              + " anyway to break it (%d bump(s) owed in all), and the next tick asks again.",
          candidate.repository(), pick.blockedBy(), owed);
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
        // The re-offer a stranded bump was owed is this request (qits-1578).
        store.clearReofferOwed(candidate.repository());
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
   * carries the bump, and which the upstream hook re-plans — or a release of it is still going OUT
   * (qits-1251: {@code RELEASED}, its publish and deployment not yet FAILED — see {@link
   * ReleaseRequestClient.Listed#inFlight()}) — or the main-only request this service last opened for
   * the same pending set is on its way, shipped, or was withdrawn with no bump anywhere in its
   * history AND that withdrawal is still younger than {@link
   * MaintenanceConfig#bumpNothingFoundHold()}. A withdrawal that DID write one does not hold
   * (qits-1173): before qits-1226 a request was withdrawn as "nothing to bump" by the re-fold onto
   * its own bump commit, and that memo held the repository on the same pending set for good.
   *
   * <p><b>A withdrawal with no bump does not hold forever either</b> (qits-1696). "Found nothing to
   * write" is only as trustworthy as the step that reported it, and the step can itself be broken —
   * until this ticket's own CI fix, the dependency-bump step silently failed to commit gitlink moves,
   * so every main-only request it withdrew "found nothing" in the meantime stranded its repository on
   * that pending set for good. Once such a withdrawal is older than {@code bumpNothingFoundHold}, it
   * no longer holds: the dispatcher offers the same set once more, which writes a fresh newest memo
   * and starts the hold over, so a set that really is empty costs one cheap pre-run per hold period
   * rather than being held forever on a report nobody can re-check. A person's withdrawal frees it
   * outright, with no hold at all; an unreadable answer holds.
   *
   * <p><b>A release in flight holds so a main-only request never overtakes it</b> (qits-1251). A
   * {@code RELEASED} request is past {@link ReleaseRequestClient.Listed#open()} — the tag is already
   * cut — so a bare {@code open()} check would read it as free and open a second, main-only request
   * racing it; that request would obsolete the one in flight and re-release the same tag with nothing
   * new in it. A publish or deployment that FAILED is the one case excepted: such a request stays
   * {@code RELEASED} until a person retries it, and holding on it would starve this repository's
   * bumps for good, so the main-only request that overtakes it is the recovery.
   */
  private boolean held(MtRepository row, List<Change> pending) {
    if (row.catalogId == null || row.catalogId.isBlank()) {
      return false;
    }
    ReleaseRequestClient.Listing listing = requests(row.catalogId);
    if (!listing.readable()
        || listing.requests().stream().anyMatch(request -> request.open() || request.inFlight())) {
      return true;
    }
    Optional<MtReleaseRequest> newest =
        store.newestOpenedRequest(row.name, MtReleaseRequest.MAIN_ONLY);
    if (newest.isEmpty() || !sameChanges(storedChanges(newest.get().changes), pending)) {
      return false;
    }
    String requestId = newest.get().requestId;
    if (newest.get().withdrawnAt != null) {
      if (MaintenanceStore.wroteABump(
          store.automationHistory(requestId, DependencyBumpAutomation.KIND))) {
        return false;
      }
      // qits-1696: "found nothing to write" is a report from the dependency-bump step, and the step
      // can itself be the thing that is broken — it silently failed to commit gitlink moves until
      // this ticket's own fix, so every withdrawal it produced in the meantime read as proof of an
      // empty set that was not actually empty. Holding on that report for good would strand such a
      // repository forever, so the hold expires: once the withdrawal is older than {@link
      // MaintenanceConfig#bumpNothingFoundHold()} it no longer holds, and the dispatcher offers the
      // same pending set once more. Opening a new request writes a new newest memo, so a set that
      // really is empty costs one cheap pre-run per hold period rather than being held forever.
      return newest.get().withdrawnAt.plus(config.bumpNothingFoundHold()).isAfter(Instant.now());
    }
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

  /**
   * A repository's whole release-request listing, open and {@code RELEASED} alike (qits-1251): the
   * unfiltered read {@link #held} needs to see a release in flight, cached exactly as every other
   * read here is, behind {@link MaintenanceConfig#bumpReleaseStateTtl()}.
   */
  private ReleaseRequestClient.Listing requests(String repoId) {
    Instant now = Instant.now();
    SeenListing seen = listings.get(repoId);
    if (seen != null && seen.at().plus(config.bumpReleaseStateTtl()).isAfter(now)) {
      return seen.listing();
    }
    ReleaseRequestClient.Listing listing = releases.requests(repoId);
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
    Set<String> reofferOwed = new LinkedHashSet<>();
    for (MtRepository row : store.repositories()) {
      if (!RepositoryStatus.OK.name().equals(row.status)) {
        continue;
      }
      List<MtGroup> groups = store.groups(row.name);
      if (groups.stream().noneMatch(candidate -> candidate.name.equals(group))) {
        continue;
      }
      List<MtPin> pins = store.pins(row.name);
      List<Change> changes =
          PendingChanges.forGroup(pins, latest, groups, group, resolver::published);
      if (changes.isEmpty()) {
        continue;
      }
      if (isRefused(row.name, now)) {
        refusals.add(row.name);
        continue;
      }
      candidates.add(new BumpOrder.Candidate(row.name, group, changes, held(row, changes)));
      if (row.reofferOwedAt != null) {
        reofferOwed.add(row.name);
      }
    }
    if (!candidates.isEmpty()) {
      Map<String, Instant> lastDispatched = store.lastDispatchedAt();
      // OWED A RE-OFFER FIRST (qits-1578): a repository whose bump was stranded on a request that
      // closed was, by construction, reached recently — least-recently-dispatched alone would put it
      // last behind the cap. The mark only reorders; held() above is untouched by it.
      candidates.sort(
          Comparator.comparing(
                  (BumpOrder.Candidate candidate) -> !reofferOwed.contains(candidate.repository()))
              .thenComparing(
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

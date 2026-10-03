package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.config.QuietHours;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtBumpWindow;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
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
import org.jboss.logging.Logger;

/**
 * AS MANY BUMPS AS qits-ci HAS FREE SLOTS FOR, AND ALWAYS FROM THE BOTTOM OF THE CHAIN.
 *
 * <h2>What it replaced</h2>
 *
 * <p>The nightly cron used to walk the inventory and call {@link BumpService#request} for every
 * eligible repository in one tight loop — no capacity check, no stagger, no cap and no ordering.
 * The first live run of it asked for 30 bumps at 02:00 and qits-ci was handed all thirty builds as
 * one wavefront. Backpressure existed but was purely reactive: a 503 from the trigger left the bump
 * REQUESTED and the poller re-sent it, which absorbs an overload after it has been created rather
 * than declining to create it.
 *
 * <p><b>So the cron stopped being the thing that dispatches and became the thing that opens a
 * window.</b> The two decisions were welded together and they answer to different facts: WHEN a
 * branch is welcome to arrive is a property of the night, and HOW MANY may be in flight is a
 * property of qits-ci at that instant. {@link #open} is the first; {@link #tick} is the second, and
 * it runs on the same short interval the bump poller does.
 *
 * <h2>DEBT ARMS THIS, NOT THE HOUR — the fifth live failure</h2>
 *
 * <p><b>And then the window became the thing it was built to replace.</b> Splitting the two
 * decisions left {@link #open} with exactly two callers: the 02:00 cron and the by-hand door. So
 * every gate below could pass — an idle qits-ci, nothing of ours in flight, a chain of owed
 * repositories sitting there — and nothing was dispatched, because the first gate could only be
 * opened by a timestamp. Measured on 2026-09-11: {@code @qits/ui-components} was cut at 10:44,
 * fifteen repositories were owed by 10:45, thirteen of them with no branch and nothing in flight,
 * and at 17:20 the window door still answered "no bump dispatch window is open" with qits-ci idle
 * the whole time. The intended primary upgrade path — something becomes owed, qits-ci goes idle,
 * the bump is dispatched — had never once run on its own.
 *
 * <p>It also cannot be recovered by a wider window: one {@code @qits/ui-components} release is
 * roughly twenty-eight dispatches, because every frontend ships inside its service as a gitlink and
 * the service's own bump cannot even begin until the frontend's release has landed and been
 * rescanned. Squeezed into one six-hour wavefront that does not finish, and what does not finish
 * does not carry.
 *
 * <p><b>So the window is now a CONSEQUENCE OF DEBT.</b> The tick asks the same questions in the same
 * order with or without a window row, and when it finds something dispatchable owed it opens the
 * window itself. Nothing else about the gate moves: the capacity gate, bottom of the chain first,
 * holds, stalls and the refusal set are all exactly as they were. What is left of the clock is {@link
 * MaintenanceConfig#bumpQuietHours()}, which says "not now" out loud instead of leaving it as an
 * implication of a cron, and which suppresses this arming only — {@code POST /bumps/window} is the
 * explicit override and is not quiet-hours gated, because a person pressing it has said so.
 *
 * <h2>The gates, in the order they are asked</h2>
 *
 * <ol>
 *   <li><b>Is the window open — or is one owed</b>. It closes by itself: when it expires, when the
 *       switches say no, and, the one that matters most, <b>as soon as nothing is owed and nothing
 *       of ours is still running</b>. A window that closed because the work is done is the ordinary
 *       ending, and a window that closed with work still owed is re-opened by the next tick that
 *       finds that work.
 *   <li><b>How many of qits-ci's slots are free</b> — {@link CiClient#queue()}, one read of {@code
 *       GET /ci/api/runs/queue}, and an unreadable snapshot counts as BUSY. The one thing a gate must
 *       never do is treat "I could not ask" as "nothing is going". {@code free = slots - active -
 *       requested}: the connected, unquarantined runners' slots, minus every run qits-ci holds
 *       (running or queued), minus the bumps of ours qits-ci has not accepted yet — REQUESTED, a
 *       build this service asked for that is not in that listing to be counted. A RUNNING bump
 *       already has its run in {@code active}, and counting it twice would hide a slot.
 *   <li><b>Which owed bumps are READY</b> — up to {@code free} of them per tick, from {@link
 *       BumpOrder#nextUpTo}. Never more than one cycle break per tick, however many slots are free.
 * </ol>
 *
 * <h2>Up to the free slots, not one at a time when idle — qits-882</h2>
 *
 * <p>The gate used to compare two counts — this service's unfinished bumps and qits-ci's whole
 * active listing — against one configured in-flight cap that defaulted to 1: one bump per tick,
 * and only when qits-ci was completely idle. That was right while qits-ci ran one build at
 * a time, and wrong the day runners arrived. Measured 2026-10-03: {@code GET /bumps/window} answered
 * CI_BUSY, "1 active run, 1 may be", with two READY repositories owed, while qits-ci had the runners
 * {@code qits-ci} (2 slots, 1 held) and {@code workstation} (6 slots) connected — eight slots, seven
 * of them free. The cap is gone rather than raised: how much qits-ci can take is a fact qits-ci
 * reports about its runners, and a number configured here would be wrong the next time a runner
 * connected, disconnected or was quarantined.
 *
 * <h2>Recomputed every tick, never a night's frozen plan</h2>
 *
 * <p>{@link PendingChanges} is computed on every read, so recomputing the candidate list costs
 * nothing and buys the whole point of the ordering: once the bottom bump releases and the next scan
 * has read it, its consumers' pending sets name the version that was just cut. A plan frozen at
 * 02:00 would hand every consumer the pin it was already going to get.
 *
 * <h2>Pending is read off main, so a bumped repository stays owed — and is HELD, not re-sent</h2>
 *
 * <p><b>The one live failure this gate had was re-dispatching the same repository every tick.</b> A
 * bump writes the {@code maintenance/dependencies} branch and opens a release request; {@link
 * PendingChanges} reads the pins on <i>main</i>, and main does not move until that release lands and
 * the next scan re-reads it. The bump itself has ended, so {@code activeBump} is gone, so the very
 * next tick saw the repository owed again and sent it — a second CI run that could only come back
 * NOTHING_TO_DO, then a third, for as long as the window was open.
 *
 * <p>So a candidate is HELD when <b>the newest bump row for that group ended without failing</b> —
 * SUCCEEDED or NOTHING_TO_DO — <b>and recorded the same set of changes it would carry now</b>. Same
 * set, order-insensitive, compared on (ecosystem, name, from, to): the manifest path and the
 * location are how an edit is applied, not what it is. A held candidate is not dispatched, stays in
 * the owed set so {@link BumpOrder} keeps its consumers waiting, and keeps the window open — a night
 * whose whole remaining chain is waiting on releases must not end early.
 *
 * <p><b>Nothing unwinds a hold, because nothing has to.</b> The release lands, a scan re-reads main,
 * the pending set empties and the repository stops being a candidate at all — there is no expiry, no
 * timer and no state to reconcile. If the release never lands, the release-state read below is what
 * ends the wait. A FAILED bump holds nothing:
 * a failure has to be retryable, and a changed pending set holds nothing either — a new upstream
 * release arrived, and that is a different bump.
 *
 * <h2>The window is a ROW, because this service redeploys itself in the middle of one</h2>
 *
 * <p>It was first an {@code AtomicReference<Instant>} here, with a paragraph arguing that losing it
 * to a restart was the cheap correct answer — a process down at 02:00 misses the night today too.
 * <b>That was wrong, and wrong for a reason particular to this service: qits-maintenance is one of
 * the repositories qits-maintenance bumps.</b> Measured live on 2026-09-10 — nineteen bumps
 * dispatched one at a time from 06:32, then the bump of {@code qits-maintenance-platform-service}
 * itself succeeded at 08:01, its release deployed at 08:11, the container was replaced, and nothing
 * was dispatched again: eleven repositories owed, four hours of window left, and no cron until the
 * next night. A restart mid-window is not this design's rare accident, it is its ordinary outcome,
 * since a successful bump here becomes a release and a release becomes a redeploy of this
 * container.
 *
 * <p>So the window is {@code mt_bump_window} — one row, {@link MtBumpWindow#INTERNAL}, deleted when
 * it closes. A restart resumes the night where it was. What it does NOT do is make the window
 * eternal: {@code closesAt} is compared with the clock exactly as the field was, so a window whose
 * service was down for its whole six hours comes back already over and closes on its first tick.
 * The cost is one primary-key read every fifteen seconds; see {@link
 * eu.wohlben.qits.maintenance.persistence.MaintenanceStore#bumpWindow()}.
 *
 * <p><b>What {@code closesAt} MEANS once debt opens the window: a safety valve and a reset, never a
 * guillotine.</b> It used to be the end of the night, and the end of the night silently dropped
 * whatever the chain had not reached — which is exactly what makes this class of failure invisible.
 * Now the expiry closes the row <b>saying how many it cut short and which they are</b>, and the same
 * tick goes on to ask the debt question again: if the work is still owed and the hour is not a quiet
 * one, a fresh window opens and the queue keeps draining. What expiring still buys is the periodic
 * reset it always was — the refusal set and the cached release states start clean — and a real stop
 * the moment a quiet hour is configured and reached.
 *
 * <h2>A hold has to ask what became of the release it is waiting for</h2>
 *
 * <p>The hold above rests on one assumption — that the release the bump opened is on its way — and
 * <b>the fourth live failure of this gate was that assumption being false and nothing noticing.</b>
 * Measured on 2026-09-10: of the night's twenty bumps, nineteen released; the twentieth,
 * {@code qits-deployments-platform-service}, had its release request REJECTED at 07:14 because its
 * gating build does not compile. Four hours later the window was still open, qits-ci was idle, that
 * one repository was still the only thing owed, nothing had been dispatched since 10:54, and no line
 * anywhere said why. A dead release and a release in flight were the same state to this service, and
 * a dead one holds for ever: nothing unwinds a hold except main moving, and main was never going to
 * move.
 *
 * <p>So a held candidate's release request is read — {@link ReleaseRequestClient#state} — and there
 * are three answers, not two:
 *
 * <ul>
 *   <li><b>PENDING, READY, RELEASED</b> — HELD, exactly as before. Something is coming.
 *   <li><b>REJECTED, FAILED, CONFLICTED, WITHDRAWN</b> — <b>STALLED</b>: dropped from the candidate
 *       list altogether. It stops blocking its consumers (they are waiting on a version that is not
 *       going to be cut, and building against the pin that exists is the honest answer), and it
 *       stops keeping the window open (a night must not stay open for work that cannot be done).
 *       It is reported, with qits-projects' own sentence, on {@code GET /bumps/window}.
 *   <li><b>Unreadable</b> — HELD. A peer that could not be asked is never evidence, which is the
 *       same reading the CI gate takes of an unreadable queue.
 * </ul>
 *
 * <p><b>Stalled is re-asked every tick and never stored as a verdict</b>, because qits-projects
 * re-arms REJECTED, FAILED and CONFLICTED back to PENDING on the next merged sha — a push to the
 * branch, a sibling's release, a pending tag reaching main. A request that comes back to life is
 * simply held again on the tick after it does, with nothing to unwind. What IS written to the row is
 * the observation ({@code mt_bump.release_state}, V11), so a bump standing since the morning explains
 * itself in a listing.
 *
 * <p><b>A stalled candidate is never re-dispatched by the clock, and that is deliberate.</b> Its
 * branch already carries the change, so a fresh bump could only come back NOTHING_TO_DO; what the
 * repository needs is a person, or the upstream release that re-arms the fold. Pressing Bump by hand
 * goes through {@link BumpService#request} and is not gated here at all.
 */
@ApplicationScoped
public class BumpDispatcher {

  private static final Logger LOG = Logger.getLogger(BumpDispatcher.class);

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject CiClient ci;

  @Inject BumpService bumps;

  @Inject ReleaseRequestClient releases;

  @Inject ArtifactGraph artifacts;

  /**
   * Repositories whose request was REFUSED this window, and which are therefore not asked for
   * again until the next one.
   *
   * <p>Without it a candidate that cannot be requested at all — a store that will not answer for
   * that row, a group that vanished between the read and the call — is picked again every fifteen
   * seconds for the length of the window, and, being the bottom of the chain, it holds up
   * everything behind it. One WARN and move on is what the loop-and-fire did too.
   *
   * <p><b>This one stays in memory even though the window no longer does</b>, and a restart
   * therefore forgives it. That is the right way round: a refusal is a guess that something is
   * wrong with one repository right now, the process it was a guess about is gone, and the cost of
   * being wrong is one CI run rather than a night that stops. The window is the opposite — losing
   * it costs the whole remaining chain — which is exactly why only one of the two is a row.
   *
   * <p><b>And it forgives by itself after {@code bump.internal.window}</b>, which is what it used to
   * get from the next cron clearing it. With the window opened by debt, a refusal that outlived its
   * cause could otherwise be permanent: a repository nothing can request is not a candidate, so
   * nothing is owed, so no window opens, so nothing ever clears the entry. One window's length later
   * it is asked again, and the worst case is still one CI run.
   */
  private final Map<String, Instant> refused = new LinkedHashMap<>();

  /**
   * What qits-projects last said about a release request, and when it said it.
   *
   * <p>The ask is one GET per held candidate per tick, and a window whose whole remaining chain is
   * waiting on releases would make that same call every fifteen seconds for hours. So an answer is
   * reused for {@code qits.maintenance.bump.dispatch.release-state-ttl} — long enough to collapse
   * the repetition, far shorter than any release takes to settle, and bounded by the number of
   * requests this service has opened rather than by time (a window's opening clears it).
   */
  private record Seen(ReleaseRequestClient.ReleaseState state, Instant at) {}

  private final Map<String, Seen> releaseStates = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Opens the dispatch window by hand — {@code POST /bumps/window}, and the ungated cron.
   *
   * <p><b>It is no longer how the ordinary window opens.</b> Debt opens that one, on the tick that
   * finds it; this is the override, and it is deliberately not quiet-hours gated, because a person
   * pressing the door has already said "now".
   */
  public void open(Instant now) {
    open(now, "it was opened by hand");
  }

  private void open(Instant now, String why) {
    Instant closes = now.plus(config.bumpWindow());
    synchronized (refused) {
      refused.clear();
    }
    releaseStates.clear();
    store.openBumpWindow(now, closes);
    LOG.infof(
        "The bump dispatch window is open until %s (%s); bumps go from the bottom of the chain, as"
            + " many per tick as qits-ci has free slots.",
        closes, why);
  }

  /** Closes it, saying why. Idempotent — a window that is already shut logs nothing. */
  public void close(String why) {
    if (store.bumpWindow().isPresent()) {
      store.closeBumpWindow();
      LOG.infof("The bump dispatch window is closed: %s.", why);
    }
  }

  /** Whether anything would be dispatched at all right now. */
  public boolean windowOpen(Instant now) {
    return store.bumpWindow().filter(now::isBefore).isPresent();
  }

  /**
   * A repository that is owed a bump and cannot be given one, because the release its last bump
   * asked for has stopped happening.
   *
   * @param repository the repository, as the catalog spells it
   * @param group the group whose branch is waiting
   * @param requestId the release request in qits-projects
   * @param state that request's state — REJECTED, FAILED, CONFLICTED, WITHDRAWN
   * @param reason that service's own sentence, which is usually the failing gating run
   */
  public record Stalled(
      String repository, String group, String requestId, String state, String reason) {}

  /**
   * A repository owed a bump that this gate declined to ask for, and is not asking for again until
   * the refusal is forgiven.
   *
   * @param repository the repository
   * @param group the group whose branch it would be
   * @param changes how many changes that bump would carry
   */
  public record Refused(String repository, String group, int changes) {}

  /** Every repository owed a bump this tick, split into what may be sent and what is stuck. */
  public record Assessment(
      List<BumpOrder.Candidate> candidates, List<Stalled> stalled, List<Refused> refused) {}

  /**
   * ONE LINE OF THE OWED SET, in the order the queue will be drained.
   *
   * <p>The listing on {@code GET /bumps} only holds bumps that were <b>dispatched</b>, and the
   * window door 404'd whenever no window was open — so "fifteen owed, nothing sent" and "the
   * scheduler is dead" were the same picture from every surface this service has. This is the
   * missing one: everything owed, why each entry is where it is, computed by the same walk the tick
   * makes.
   *
   * @param repository the repository, as the catalog spells it
   * @param group the group whose branch is owed
   * @param changes how many changes the bump would carry
   * @param reason {@code READY}, {@code BLOCKED}, {@code HELD}, {@code STALLED} or {@code REFUSED}
   * @param detail the sentence for that reason — what it waits on, or what said no
   */
  public record Owed(
      String repository, String group, int changes, String reason, String detail) {}

  /**
   * What the gate decided and everything it decided it from — the tick's whole reasoning, so that
   * "there are pending bumps and nothing is queued" is a question this service answers about itself
   * rather than one somebody reconstructs from three services' logs.
   *
   * @param outcome the short name of the gate that answered
   * @param summary the sentence for a person
   * @param inFlight bumps of ours not yet ended, or null when the gate answered before asking
   * @param slots qits-ci's connected, unquarantined runner slots, null when it was not asked or
   *     could not be read
   * @param free how many of those this tick may fill — slots minus qits-ci's active runs minus our
   *     REQUESTED bumps, floored at zero — null when it was not asked or could not be read
   * @param ciActive what qits-ci holds, running and queued, null when it was not asked or could not
   *     be read
   * @param owed how many repositories are owed a bump and could still be sent one
   * @param held how many of those are waiting on a release in flight
   * @param stalled the ones waiting on a release that has stopped
   * @param queue the whole owed set in dispatch order, each entry with its reason
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
      List<Stalled> stalled,
      List<Owed> queue,
      List<BumpOrder.Pick> picks) {

    static Decision of(String outcome, String summary) {
      return new Decision(
          outcome, summary, null, null, null, null, 0, 0, List.of(), List.of(), List.of());
    }

    /** The first pick — what {@code next} on the window door names — or null. */
    public BumpOrder.Pick pick() {
      return picks.isEmpty() ? null : picks.get(0);
    }
  }

  /**
   * One dispatch decision, and every pick it made sent in order.
   *
   * <p><b>A refusal of one pick does not stop the rest.</b> It lands in {@link #refused} with its
   * WARN exactly as a lone pick's always did, and the next pick goes: the picks are all READY, so
   * none of them was waiting on the one that failed.
   *
   * @return the bumps that were asked for, empty on every other outcome — none of which is a
   *     failure
   */
  public List<UUID> tick() {
    Decision decision = decide(Instant.now(), true);
    List<UUID> sent = new ArrayList<>();
    int stillOwed = decision.owed();
    for (BumpOrder.Pick pick : decision.picks()) {
      stillOwed--;
      dispatch(pick, decision.owed(), stillOwed).ifPresent(sent::add);
    }
    return List.copyOf(sent);
  }

  /**
   * The same reasoning with nothing done about it — no window closed, no bump sent — for the door
   * that answers "why is nothing happening".
   */
  public Decision explain(Instant now) {
    return decide(now, false);
  }

  /**
   * The gates, in the order they are asked.
   *
   * @param commit whether to act on what is decided: close a window that is over or empty, and
   *     answer a pick that the caller will dispatch. A read-only pass changes nothing at all except
   *     the cached release states, which are an observation either way.
   */
  private Decision decide(Instant now, boolean commit) {
    Optional<Instant> closes = store.bumpWindow();
    boolean open = closes.isPresent() && now.isBefore(closes.get());
    if (closes.isPresent() && !open && commit) {
      // THE EXPIRY SAYS WHAT IT CUT SHORT and then gets out of the way: the debt question below is
      // asked on this same tick, so a chain that is still owed simply carries on under a fresh
      // window. Silently dropping the rest of a chain is what made this class of failure invisible.
      closeExpired();
      open = false;
    }
    if (!config.bumpEnabled()) {
      if (commit) {
        close("qits.maintenance.bump.enabled is false");
      }
      return Decision.of("DISABLED", "qits.maintenance.bump.enabled is false");
    }
    if (!config.bumpInternalAuto()) {
      if (commit) {
        close("qits.maintenance.bump.internal.auto is false");
      }
      return Decision.of("DISABLED", "qits.maintenance.bump.internal.auto is false");
    }

    List<MtBump> active = store.activeBumps();
    int inFlight = active.size();
    // A REQUESTED bump is a build this service asked qits-ci for and qits-ci has not accepted yet,
    // so it is in nobody's queue listing — it would be a slot counted free twice. A RUNNING one has
    // its run in that listing already. Targeted bumps count too, deliberately: everything else about
    // a targeted bump is outside this gate, but it IS a CI run this service asked for.
    int requested =
        (int)
            active.stream().filter(bump -> BumpStatus.REQUESTED.name().equals(bump.status)).count();

    // THE WALK RUNS EVEN WHEN THE ANSWER IS ALREADY NO, because "what is owed" is the question this
    // service could not answer about itself. `GET /bumps` holds only bumps that were dispatched, so
    // between two dispatches — which is most of the time — a reader had nothing at all to look at.
    // It costs one inventory walk per fifteen seconds; a single needless CI run costs more.
    Assessment assessment = assess();
    List<BumpOrder.Candidate> candidates = assessment.candidates();
    List<Stalled> stalled = assessment.stalled();
    List<Owed> owedList = queueOf(assessment);
    int heldCount = (int) candidates.stream().filter(BumpOrder.Candidate::held).count();

    if (candidates.isEmpty()) {
      // NOTHING DISPATCHABLE IS OWED, and a window whose remainder is stalled closes here too. It
      // must: staying open for work that cannot be done is a night that never ends and a gate that
      // reports nothing. The sentence names the stalled repositories, which is the one line the
      // fourth live failure of this gate did not have.
      //
      // BUT NOT WHILE A BUMP OF OURS IS STILL GOING. The repository being bumped right now is not a
      // candidate — an active bump is a skip — so an empty list would otherwise shut the window on
      // the night's last bump while it runs. The in-flight gate used to keep that from happening as
      // a side effect of answering first; it is gone (qits-882), so the rule is said here instead.
      String why =
          stalled.isEmpty()
              ? "nothing is owed a bump"
              : "nothing is owed a bump that can be sent; "
                  + stalled.size()
                  + " wait on a release that has stopped ("
                  + stalledNames(stalled)
                  + ")";
      if (commit && inFlight == 0) {
        close(why);
      }
      return new Decision(
          stalled.isEmpty() ? "NOTHING_OWED" : "ALL_STALLED",
          why,
          inFlight,
          null,
          null,
          null,
          0,
          0,
          stalled,
          owedList,
          List.of());
    }

    // SOMETHING DISPATCHABLE IS OWED — which, with no window row, is the whole of the reason to open
    // one. This is the arming the design always described and never had: no hour is consulted, and
    // the only thing that can still say "not now" says so by name.
    if (!open) {
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
            heldCount,
            stalled,
            owedList,
            List.of());
      }
      if (commit) {
        open(now, candidates.size() + " repositor(ies) are owed a bump");
      }
    }

    CiClient.QueueState queue = ci.queue();
    if (!queue.readable()) {
      // Unreadable is BUSY. Dispatching blind here is precisely the wavefront this gate exists for.
      LOG.debugf("qits-ci's queue could not be read (%s); nothing is dispatched.", queue.error());
      return new Decision(
          "CI_UNREADABLE",
          "qits-ci's queue could not be read (" + queue.error() + "), which counts as busy",
          inFlight,
          null,
          null,
          null,
          candidates.size(),
          heldCount,
          stalled,
          owedList,
          List.of());
    }
    int slots = queue.slots();
    int ciActive = queue.active();
    if (slots == 0) {
      // No runner connected, or every one quarantined: nothing would claim a run, so sending one is
      // a build that sits QUEUED until somebody notices. Its own outcome, because "busy" would send
      // a reader to look for runs that are not there.
      LOG.debugf(
          "qits-ci has 0 slots (no runner connected and unquarantined), %d active run(s); nothing is"
              + " dispatched.",
          ciActive);
      return new Decision(
          "NO_SLOTS",
          "qits-ci has no slot to run anything: no runner is connected, or every one is quarantined",
          inFlight,
          0,
          0,
          ciActive,
          candidates.size(),
          heldCount,
          stalled,
          owedList,
          List.of());
    }
    int free = Math.max(0, slots - ciActive - requested);
    if (free == 0) {
      LOG.debugf(
          "qits-ci has %d slot(s), %d active run(s) and %d bump(s) of ours waiting to reach it; 0"
              + " free, nothing is dispatched.",
          slots, ciActive, requested);
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
          heldCount,
          stalled,
          owedList,
          List.of());
    }

    List<BumpOrder.Pick> picks = BumpOrder.nextUpTo(candidates, artifacts.producers(), free);
    if (picks.isEmpty()) {
      // Everything owed is waiting on a release that has already been asked for. An ordinary state
      // and not a stall: DEBUG, no cycle break, and the window stays open for what comes after it.
      LOG.debugf(
          "All %d owed bump(s) are waiting on a release of their own branch (%d of %d slot(s) free);"
              + " nothing is dispatched.",
          candidates.size(), free, slots);
      return new Decision(
          "WAITING_ON_RELEASES",
          "all " + candidates.size() + " owed bump(s) wait on a release of their own branch",
          inFlight,
          slots,
          free,
          ciActive,
          candidates.size(),
          heldCount,
          stalled,
          owedList,
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
        heldCount,
        stalled,
        owedList,
        List.copyOf(picks));
  }

  private static String stalledNames(List<Stalled> stalled) {
    List<String> names = new ArrayList<>();
    for (Stalled one : stalled) {
      names.add(one.repository() + " " + one.state());
    }
    return String.join(", ", names);
  }

  private Optional<UUID> dispatch(BumpOrder.Pick pick, int owed, int stillOwed) {
    BumpOrder.Candidate candidate = pick.candidate();
    if (pick.cycleBroken()) {
      // Every candidate waits on another candidate. The graph is not guaranteed acyclic and this
      // must not become a night in which nothing moves.
      LOG.warnf(
          "Every one of the %d owed bumps waits on another; %s is dispatched anyway (it waits on"
              + " %s), and the next tick asks again.",
          owed, candidate.repository(), pick.blockedBy());
    }
    try {
      UUID id = bumps.request(candidate.repository(), candidate.group(), BumpTrigger.SCHEDULED);
      LOG.infof(
          "Dispatched the scheduled bump %s of %s/%s (%d changes); %d repositor(ies) are still"
              + " owed one.",
          id, candidate.repository(), candidate.group(), candidate.changes().size(), stillOwed);
      return Optional.of(id);
    } catch (RuntimeException e) {
      synchronized (refused) {
        refused.put(candidate.repository(), Instant.now());
      }
      LOG.warnf(
          "Could not dispatch the scheduled bump of %s/%s: %s; it is not asked for again for %s.",
          candidate.repository(), candidate.group(), e.getMessage(), config.bumpWindow());
      return Optional.empty();
    }
  }

  /** Whether this repository's refusal is still standing, forgiving the ones that have aged out. */
  private boolean isRefused(String repository, Instant now) {
    synchronized (refused) {
      Instant at = refused.get(repository);
      if (at == null) {
        return false;
      }
      if (at.plus(config.bumpWindow()).isAfter(now)) {
        return true;
      }
      refused.remove(repository);
      return false;
    }
  }

  /**
   * The window's expiry, said out loud.
   *
   * <p>It closes the row and names what was still owed when it did. The tick then asks the debt
   * question again, so this is a reset and a line in the log rather than an ending — but the line
   * has to exist: a window that expired mid-chain used to take the rest of the chain with it and say
   * nothing at all, which is exactly what makes this class of failure invisible.
   */
  private void closeExpired() {
    Assessment assessment = assess();
    int owed = assessment.candidates().size();
    if (owed == 0) {
      close("it reached the end of " + config.bumpWindow() + " with nothing owed");
      return;
    }
    List<String> names = new ArrayList<>();
    for (BumpOrder.Candidate candidate : assessment.candidates()) {
      names.add(candidate.repository());
    }
    LOG.infof(
        "The bump dispatch window reached the end of %s with %d repositor(ies) still owed a bump"
            + " (%s); it is closed and this tick opens a fresh one unless the hour is quiet.",
        config.bumpWindow(), owed, String.join(", ", names));
    close("it reached the end of " + config.bumpWindow() + " with " + owed + " still owed");
  }

  /**
   * The owed set as a reader gets it: dispatchable ones in {@link BumpOrder}'s order, then the ones
   * waiting on a release that has stopped, then the ones this gate refused to ask for.
   *
   * <p>The three groups are one listing rather than three fields because the question behind the
   * door is one question — "what is owed, and why has none of it gone" — and answering it out of
   * three shapes is how the previous four investigations of this gate went.
   */
  private List<Owed> queueOf(Assessment assessment) {
    List<Owed> owed = new ArrayList<>();
    for (BumpOrder.Standing standing :
        BumpOrder.standing(assessment.candidates(), artifacts.producers())) {
      BumpOrder.Candidate candidate = standing.candidate();
      String detail =
          switch (standing.reason()) {
            case "READY" -> "nothing owed sits below it";
            case "BLOCKED" -> "it waits on " + String.join(", ", standing.blockedBy());
            default -> "its branch is pushed and it waits on its own release";
          };
      owed.add(
          new Owed(
              candidate.repository(),
              candidate.group(),
              candidate.changes().size(),
              standing.reason(),
              detail));
    }
    for (Stalled one : assessment.stalled()) {
      owed.add(
          new Owed(
              one.repository(),
              one.group(),
              0,
              "STALLED",
              "its release request " + one.requestId() + " is " + one.state() + ": " + one.reason()));
    }
    for (Refused one : assessment.refused()) {
      owed.add(
          new Owed(
              one.repository(),
              one.group(),
              one.changes(),
              "REFUSED",
              "this gate could not ask for it and is not asking again for " + config.bumpWindow()));
    }
    return List.copyOf(owed);
  }

  /**
   * Every repository owed an INTERNAL bump right now, least-recently-bumped first.
   *
   * <p>The filters are the ones the nightly loop always had — status OK, the group exists,
   * something is pending, no writer on that branch — and they are applied here rather than there
   * because they are now a question asked every fifteen seconds instead of once a night. See {@code
   * BumpSchedule} for why each of them is a skip rather than a failure.
   *
   * <p><b>The last of them is a flag rather than a skip.</b> A repository whose branch is already
   * pushed and waiting on a release is still owed — see this class's javadoc — so it is returned
   * {@linkplain BumpOrder.Candidate#held() held} instead of being dropped: dropping it would let its
   * consumers go early and would let the window close on a chain that is only half sent.
   *
   * <p>One extra row read per candidate per tick, and only for the repositories that got as far as
   * having something pending — at roughly fifty repositories that is cheaper than the CI run a
   * single re-dispatch costs.
   *
   * <h2>THE ORDER IS THE CALLER'S, AND IT IS LEAST-RECENTLY-BUMPED FIRST</h2>
   *
   * <p>{@link BumpOrder} decides which candidates are <i>ready</i> — an upstream that is itself owed
   * a bump always goes before its consumer, and nothing here touches that — but among candidates
   * that are equally ready it simply takes the first one it was handed. <b>So whatever order this
   * walk produces is the arbiter of everything the topology does not decide</b>, and for as long as
   * it was {@link MaintenanceStore#repositories()}'s that arbiter was the alphabet.
   *
   * <p>That is starvation rather than unfairness, because of how slowly this queue drained: one bump
   * went at a time then and each is HELD until its own release lands, so a fan-out over the whole
   * estate moved at roughly one repository every five to fifteen minutes and the tail of the
   * alphabet was always the tail of the night — every night, for the same repositories. Measured 2026-09-13:
   * {@code qits-projects-daemon} and {@code qits-workspace-daemon} consume the identical two jars
   * from one {@code qits-coding-agents} release and are equally ready the moment it lands; the first
   * was dispatched at 19:53 and the second at 21:28, nine repositories later, for no reason but its
   * name. {@code qits-workspace-daemon}, {@code qits-workspace-editor-oci} and {@code
   * qits-workspaces-service} were last in every estate-wide fan-out there has been.
   *
   * <p>So the candidates are ordered by {@link MaintenanceStore#lastScheduledBumpAt()} ascending —
   * the repository the clock reached longest ago goes first, one it has never reached at all counts
   * as {@link Instant#EPOCH} and therefore goes before all of them, and the NAME is kept as the
   * final tiebreak so two equal timestamps still produce one stable order rather than whatever the
   * database happened to return. A repository dispatched today sinks to the back of tomorrow's
   * queue by construction, and no repository can be permanently last.
   */
  public List<BumpOrder.Candidate> candidates() {
    return assess().candidates();
  }

  /** The same walk, keeping what it had to drop and why. */
  public Assessment assess() {
    String group = GroupConfig.DEFAULT_GROUP;
    Instant now = Instant.now();
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    List<BumpOrder.Candidate> candidates = new ArrayList<>();
    List<Stalled> stalled = new ArrayList<>();
    List<Refused> refusals = new ArrayList<>();
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
      if (store.activeBump(row.name, group).isPresent()) {
        continue;
      }
      // THE REFUSAL IS ASKED HERE RATHER THAN AT THE TOP OF THE LOOP, one read later than it used
      // to be, so that a refused repository is still REPORTED as owed. Skipping it before its
      // pending set was computed made it vanish from every surface at once — which is the same
      // silence this ticket is about, one repository at a time.
      if (isRefused(row.name, now)) {
        refusals.add(new Refused(row.name, group, changes.size()));
        continue;
      }
      Hold hold = hold(row, group, changes);
      if (hold.stalled() != null) {
        stalled.add(hold.stalled());
        continue;
      }
      candidates.add(new BumpOrder.Candidate(row.name, group, changes, hold.held()));
    }
    // LEAST-RECENTLY-BUMPED FIRST. The walk above is the store's listing order, which is the
    // alphabet; handing that to BumpOrder made the first letter of a name the arbiter of everything
    // the dependency topology does not decide, and a queue this slow turns that into permanent
    // starvation of the tail. See this class's `candidates()` for the 2026-09-13 measurement. The
    // name stays as the last tiebreak so the order is still deterministic.
    if (!candidates.isEmpty()) {
      Map<String, Instant> lastBumped = store.lastScheduledBumpAt();
      candidates.sort(
          Comparator.comparing(
                  (BumpOrder.Candidate candidate) ->
                      lastBumped.getOrDefault(candidate.repository(), Instant.EPOCH))
              .thenComparing(BumpOrder.Candidate::repository));
    }
    return new Assessment(List.copyOf(candidates), List.copyOf(stalled), List.copyOf(refusals));
  }

  /**
   * How a candidate stands against the bump it has already had: free, held, or stalled.
   *
   * @param held these exact changes are on a branch whose release is on its way
   * @param stalled …or on a branch whose release has stopped, with the reason
   */
  private record Hold(boolean held, Stalled stalled) {

    static final Hold FREE = new Hold(false, null);
    static final Hold HELD = new Hold(true, null);
  }

  /**
   * Whether this group's branch has already been bumped for exactly these changes and is waiting on
   * the release that will move main.
   */
  private Hold hold(MtRepository row, String group, List<Change> pending) {
    Optional<MtBump> newest = store.newestBump(row.name, group);
    if (newest.isEmpty()) {
      return Hold.FREE;
    }
    MtBump bump = newest.get();
    BumpStatus status = BumpStatus.valueOf(bump.status);
    if (status != BumpStatus.SUCCEEDED && status != BumpStatus.NOTHING_TO_DO) {
      // REQUESTED and RUNNING never reach here — an active bump is a skip above — and FAILED must
      // stay retryable: the branch it was going to push is not there to be released.
      return Hold.FREE;
    }
    if (!sameChanges(BumpService.changes(bump), pending)) {
      return Hold.FREE;
    }
    return releaseHold(row, group, bump);
  }

  /**
   * Whether the release that bump asked for is still one to wait for.
   *
   * <p>Held on everything that is not a plain "this has stopped": a request id nothing can be asked
   * about (the {@code converged} sentinel, an ask that has not been made yet — the sweep is still
   * re-attempting it), a repository with no catalog id to address qits-projects with, and any answer
   * that could not be read. <b>{@code refused} is the one sentinel that stalls</b>: qits-projects
   * refused the ask itself, so there is no request and nothing is coming.
   */
  private Hold releaseHold(MtRepository row, String group, MtBump bump) {
    String requestId = bump.releaseRequestId;
    if (requestId == null || requestId.isBlank() || ReleaseRequestClient.CONVERGED.equals(requestId)) {
      return Hold.HELD;
    }
    if (ReleaseRequestClient.REFUSED.equals(requestId)) {
      return new Hold(
          false,
          new Stalled(row.name, group, null, "REFUSED", bump.message == null ? "" : bump.message));
    }
    if (row.catalogId == null || row.catalogId.isBlank()) {
      return Hold.HELD;
    }
    ReleaseRequestClient.ReleaseState state = releaseState(row.catalogId, requestId, bump);
    if (!state.stalled()) {
      return Hold.HELD;
    }
    return new Hold(
        false,
        new Stalled(
            row.name,
            group,
            requestId,
            state.state(),
            state.detail() == null ? "" : state.detail()));
  }

  /**
   * qits-projects' answer about one request, reused within the ttl and written onto the bump row
   * whenever it is freshly read.
   */
  private ReleaseRequestClient.ReleaseState releaseState(
      String catalogId, String requestId, MtBump bump) {
    Instant now = Instant.now();
    Seen seen = releaseStates.get(requestId);
    if (seen != null && seen.at().plus(config.bumpReleaseStateTtl()).isAfter(now)) {
      return seen.state();
    }
    ReleaseRequestClient.ReleaseState state = releases.state(catalogId, requestId);
    releaseStates.put(requestId, new Seen(state, now));
    if (state.readable()) {
      String before = bump.releaseState;
      store.bumpReleaseState(bump.id, state.state(), state.detail(), now);
      if (state.stalled() && !state.state().equals(before)) {
        // ONE WARN WHEN IT TURNS, and none while it stays that way. This is the line whose absence
        // made the estate look idle for four hours.
        LOG.warnf(
            "The release request %s of %s is %s, so its bump is not waited on any longer: %s",
            requestId, bump.repository, state.state(), state.sentence());
      }
    } else {
      LOG.debugf("The release request %s could not be read: %s", requestId, state.error());
    }
    return state;
  }

  /**
   * Whether two change lists ask for the same thing, order-insensitively.
   *
   * <p><b>Compared on (ecosystem, name, from, to) and not on the whole record.</b> Those four are
   * what the bump is FOR; {@code manifestPath} and {@code location} say how the edit is applied and
   * a scan that re-read the same repository can legitimately spell them differently — a pin that
   * moved file, a property that became an element — without any dependency having moved. Comparing
   * those too would report a new bump every time a manifest was reshaped, which is the
   * re-dispatch loop this exists to stop.
   */
  private static boolean sameChanges(List<Change> recorded, List<Change> pending) {
    return keys(recorded).equals(keys(pending));
  }

  private static Set<String> keys(List<Change> changes) {
    Set<String> keys = new LinkedHashSet<>();
    for (Change change : changes) {
      keys.add(change.ecosystem() + " " + change.name() + " " + change.from() + " " + change.to());
    }
    return keys;
  }
}

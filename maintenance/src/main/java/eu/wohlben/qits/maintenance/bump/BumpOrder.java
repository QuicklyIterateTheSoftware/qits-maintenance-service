package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WHICH OWED BUMP GOES FIRST — the bottom of the dependency chain, always.
 *
 * <p><b>The failure this exists to stop is not load, it is waste.</b> A library's bump and its
 * consumer's bump asked for in the same breath means the consumer builds against the pin it is
 * about to be handed anyway, and needs a second bump the next night to pick up the release the
 * first one produced. Dispatching the deepest upstream first and recomputing afterwards collapses
 * those two nights into one hop that lands the real version.
 *
 * <p><b>The relation is read off the CHANGES, not off the whole graph.</b> A candidate is held back
 * by another candidate only when one of its own pending changes names something that candidate
 * publishes — which is exactly "the thing it is waiting on sits below it". A dependency on a
 * repository with nothing owed is not a reason to wait: that repository is already released and its
 * version is the one in {@code mt_latest}.
 *
 * <p><b>Two spellings of "who publishes this", because there are two kinds of edge.</b> A maven,
 * npm or image coordinate is matched through {@link ArtifactGraph#producers()}, which is the
 * released-artifact ledger. A GITLINK pin has no artifact at all — its {@code name} IS the
 * submodule's repository name, which is the same string the catalog lists — so it is matched by
 * name directly, and only for that ecosystem, so a package that happens to be called like a
 * repository cannot invent an edge.
 *
 * <p><b>A cycle degrades to a pick, never to a stall.</b> {@code ArtifactGraph} says plainly that
 * cycles occur and that the graph is not guaranteed acyclic, so "these candidates wait on each
 * other" is a state this has to have an answer for: the least-blocked member of the knot goes, the
 * caller says so in the log, and the next tick asks again against a graph one release smaller. The
 * alternative — waiting for an unblocked candidate that cannot exist — is a knot that is never
 * bumped at all.
 *
 * <p><b>And a knot is broken beside READY work, not only when there is none (qits-1696).</b> The
 * break used to be the fallback for a tick in which NOT ONE free candidate was READY, which read as
 * "a cycle is an estate-wide stall" and was true only of a small estate. On 2026-10-28 {@code
 * qits-system-service} owed a gitlink bump of {@code qits-system-frontend}, which owed {@code
 * @qits/system-golden-masters}, which {@code qits-system-service} publishes — a two-node knot —
 * while some thirty unrelated repositories were owed and READY. Thirty READY candidates are never
 * none, so the fallback never fired and the knot sat untouched for hours behind work it had nothing
 * to do with. A knot is a fact about its own members, not about how busy the rest of the estate is:
 * so a tick now takes its READY candidates first and then, if room is left, at most ONE cycle
 * break.
 *
 * <p><b>"In a cycle" is meant exactly.</b> A candidate is a break only when everything it waits on
 * is FREE and waits, in turn, back on it — its whole wait set lies inside its own knot, so nothing
 * outside the knot is going to move it. A candidate that also waits on something outside — a held
 * candidate whose release is in flight, a READY one going this very tick, or the member of some
 * other knot — is not a break: that upstream goes first by the ordinary rule, and dispatching the
 * consumer now is precisely the build against the stale pin the ordering is for. A knot whose every
 * member waits on something outside it is therefore left alone until that something lands, which it
 * does by itself; a knot nothing outside reaches always has a member to break.
 *
 * <h2>Held candidates: owed, blocking, and not the pick</h2>
 *
 * <p><b>A candidate that has already been bumped for exactly these changes is HELD</b>, and the
 * distinction between "held" and "not a candidate" is the whole of the fix. {@code PendingChanges}
 * reads the pins on <i>main</i>; a bump writes a branch and opens a release request, so main does
 * not move until that release lands. Between the two the repository is still owed by every
 * measurement this service can take, and dropping it from the list would say two false things at
 * once: that it may be dispatched again (it was, every fifteen seconds, burning a CI run that found
 * nothing to do) and that its consumers are free to go (they were, and they built against the old
 * pin — the two nights for one hop this class exists to collapse).
 *
 * <p>So held is a third state and it is spelled out here rather than at the caller: {@link
 * #nextUpTo} counts held candidates in {@code owed}, so they keep blocking, and never picks one, so
 * they never re-dispatch. <b>When every candidate is held the answer is no pick at all</b> — the ordinary
 * waiting state of a night whose releases are in flight, not a failure and not a reason for the
 * caller to close its window.
 *
 * <p><b>And a cycle is judged among the free candidates only.</b> A free candidate blocked solely by
 * held ones is not in a cycle: the thing it waits for is a release already on its way, and the
 * moment it lands the next tick sees an unblocked candidate. Breaking that "cycle" would dispatch
 * precisely the build against the stale pin the ordering is for. Only a ring of <i>free</i>
 * candidates waiting on one another is a knot nothing but a pick can undo.
 *
 * <p><b>That judgement is per knot now, not estate-wide (qits-1696).</b> Before, one free candidate
 * anywhere that waited on a release in flight vetoed every break, on the reasoning that a release
 * landing would hand the next tick something unblocked. Once READY work and a break share a tick
 * that veto is the starvation all over again in a new form: during a fan-out the consumers of last
 * tick's picks are always waiting on a release, so an unrelated knot would wait for a quiet estate
 * that never comes. A release in flight unblocks its own consumers; it cannot untie a knot it is not
 * part of.
 *
 * <h2>What this orders, and what it does not</h2>
 *
 * <p><b>This orders WHICH PINS ARE READY, and it never decided which branch anything lands on.</b>
 * The reason a library goes before its consumer is that the consumer's {@code to} is not worth
 * writing yet — the version it would be handed is not released — and that is a fact about the pin,
 * not about the ref it would be written to. So the arrival of a second bump mode changes nothing
 * here: a targeted bump names its own branch, and the ordering has no opinion about branches to
 * lose.
 *
 * <p><b>THE LISTING ORDER IS THE CALLER'S, AND IT DECIDES EVERYTHING THE TOPOLOGY DOES NOT.</b>
 * Every method here walks {@code candidates} in the order it was handed and takes the first one that
 * qualifies, so among candidates that are equally ready — nothing owed below either of them — the
 * caller's order is the whole arbiter. That was worth saying out loud the day the caller's order
 * turned out to be the alphabet: {@code BumpDispatcher.assess} built the list by walking {@code
 * MaintenanceStore.repositories()}, which sorts by name, so a repository's first letter decided when
 * it was bumped. One bump went at a time then and each is held until its own release lands, so an
 * estate-wide fan-out drained at roughly one repository every five to fifteen minutes and the tail of
 * the alphabet was the tail of every night — the same repositories, every time, which is starvation
 * rather than jitter. Measured 2026-09-13: {@code qits-projects-daemon} and {@code
 * qits-workspace-daemon} consume the identical two jars from one {@code qits-coding-agents} release
 * and are ready at the same instant; the first was dispatched at 19:53, the second at 21:28, nine
 * repositories later. {@code qits-workspace-*} was last in every fan-out there had been.
 *
 * <p>So the caller now hands these lists over <b>least-recently-bumped first</b> — ascending by the
 * newest SCHEDULED {@code mt_bump.started_at} of each repository, never-bumped first, name as the
 * final tiebreak. <b>Nothing in this class changed for it</b>, and nothing here should: the
 * topological rule above still overrules the order outright (an owed upstream goes before its
 * consumer however long ago either was bumped), and this class's contract is exactly what it always
 * was — a stable order in, the first qualifying entry out.
 *
 * <p><b>Targeted bumps are not candidates and cannot be.</b> {@code BumpDispatcher.assess} builds
 * this list out of what the inventory says is PENDING, which is a question about a repository's pins
 * on <i>main</i>; a targeted bump exists because a caller asked for named changes on a named branch,
 * which nothing here could have derived and nothing here should second-guess. The consequence worth
 * stating: a targeted bump neither blocks a candidate nor is blocked by one, and if the pins it
 * carries were computed by somebody else against an unreleased version, this class is not what would
 * have caught it — the caller owns that decision along with the branch.
 */
public final class BumpOrder {

  private BumpOrder() {}

  /**
   * One repository owed a bump, with the changes that bump would carry.
   *
   * @param repository the repository name, as the catalog spells it
   * @param group the group whose branch it would go on
   * @param changes the group's pending changes, computed this tick
   * @param held whether these exact changes have already been bumped and are waiting on a release —
   *     still owed, still blocking whatever sits above it, never the pick
   */
  public record Candidate(String repository, String group, List<Change> changes, boolean held) {

    /** An ordinary, dispatchable candidate. */
    public Candidate(String repository, String group, List<Change> changes) {
      this(repository, group, changes, false);
    }
  }

  /**
   * The candidate to send now.
   *
   * @param candidate the pick
   * @param blockedBy the owed upstreams it still has, empty on an ordinary pick
   * @param cycleBroken whether it was picked despite being blocked, because it and everything it
   *     waits on wait on each other — a knot of free candidates nothing outside will untie
   */
  public record Pick(Candidate candidate, Set<String> blockedBy, boolean cycleBroken) {}

  /**
   * The next viable bump: the first free candidate nothing else owed sits below, in the order the
   * caller listed them — which is least-recently-bumped first, for the reason the class doc gives —
   * and when there is none, the one cycle break {@link #nextUpTo} would make.
   *
   * <p><b>Exactly the head of {@code nextUpTo(candidates, producers, 1)}</b>, and written as that
   * rather than beside it. The two used to be separate loops that agreed by construction, which
   * was true right up to the day {@link #nextUpTo} learned to break a knot beside READY work
   * (qits-1696): two copies of "what is a cycle" is one copy that drifts. With room for one, a READY
   * candidate always wins over a break — a guess is never taken over a certainty.
   *
   * <p><b>{@code owed} is built from ALL the candidates and the pick is taken from the free ones.</b>
   * The two sets are deliberately different — that is how a repository waiting on a release it has
   * already been bumped for goes on holding its consumers back without being sent again.
   *
   * @param candidates every repository owed a bump this tick, held ones included, in a stable order
   *     — the caller's, and the tiebreak among equally ready candidates: see the class doc
   * @param producers {@link ArtifactGraph#producers()} — coordinate to publishing repository
   * @return the bump to send, or empty when there is none to send right now — no candidates at all,
   *     or every free one still waiting, directly or through another, on a release in flight
   */
  public static Optional<Pick> next(List<Candidate> candidates, Map<String, String> producers) {
    return nextUpTo(candidates, producers, 1).stream().findFirst();
  }

  /**
   * UP TO {@code n} BUMPS TO SEND NOW — every free candidate nothing else owed sits below, in the
   * caller's order, as many as qits-ci has room for, and with room left over at most one cycle
   * break.
   *
   * <p><b>Two READY candidates can always go together</b>: neither has an owed upstream, so neither
   * is waiting on the other, and sending both in one tick costs nothing the ordering exists to
   * prevent. That is what makes a batch safe here and is the whole of qits-882 — the dispatcher used
   * to hand out one per tick only when qits-ci was idle, while eight slots sat seven-free. <b>{@code
   * n} is {@link BumpDispatcher}'s free CI room</b>: the CI leases qits-system's connected,
   * unquarantined targets would grant now, minus what qits-ci holds queued and this service's own
   * requested rows (qits-1769; between epic qits-1481 and then it was a configured cap).
   *
   * <p><b>The cycle rule is never multiplied, and since qits-1696 it is never starved either.</b>
   * The READY candidates are taken first, up to {@code n}. If that leaves room, ONE more pick may
   * follow: the least-blocked free candidate whose whole wait set lies inside its own knot (see the
   * class doc for exactly what that means), marked {@code cycleBroken} and carrying its {@code
   * blockedBy}, ties going to the caller's order. It used to be that a break was made only when NOT
   * ONE free candidate was READY — and an estate always has something READY, so a two-node knot
   * between {@code qits-system-service} and {@code qits-system-frontend} waited for hours behind
   * thirty unrelated bumps on 2026-10-28. A break is still a guess, so one per tick against a graph
   * one release smaller remains the most this ever makes, however much room is free and however
   * many knots there are; READY work is never displaced for it, because the guess only ever takes
   * room the certainties left.
   *
   * @param candidates every repository owed a bump this tick, held ones included, in a stable order
   * @param producers {@link ArtifactGraph#producers()} — coordinate to publishing repository
   * @param n how many may go — the room free under the configured cap; zero or less is an empty
   *     answer
   * @return the picks in dispatch order: up to {@code n} READY ones, then at most one cycle break
   *     when they left room. For {@code n == 1} the head is exactly what {@link #next} returns
   */
  public static List<Pick> nextUpTo(
      List<Candidate> candidates, Map<String, String> producers, int n) {
    if (n <= 0 || candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    Set<String> owed = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      owed.add(candidate.repository());
    }
    List<Pick> picks = new ArrayList<>();
    for (Candidate candidate : candidates) {
      if (candidate.held()) {
        continue;
      }
      if (owedUpstreams(candidate, producers, owed).isEmpty()) {
        picks.add(new Pick(candidate, Set.of(), false));
        if (picks.size() == n) {
          return List.copyOf(picks);
        }
      }
    }
    cycleBreak(candidates, producers, owed).ifPresent(picks::add);
    return List.copyOf(picks);
  }

  /**
   * The one knot member worth guessing on, if there is a knot at all: among the free candidates
   * whose every owed upstream is free AND waits back on them, the one with the fewest owed upstreams,
   * the caller's order breaking ties.
   *
   * <p><b>"Waits back on it" is reachability over the free candidates' edges</b>, not a direct
   * back-edge: a three-node ring is as much a knot as a two-node one. Held candidates contribute no
   * edges — a held upstream disqualifies its consumer outright, because that wait ends with a
   * release already in flight — and a READY candidate has none to contribute, so it can neither be a
   * break nor lie on a ring.
   */
  private static Optional<Pick> cycleBreak(
      List<Candidate> candidates, Map<String, String> producers, Set<String> owed) {
    Set<String> free = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      if (!candidate.held()) {
        free.add(candidate.repository());
      }
    }
    Map<String, Set<String>> waitsOn = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      if (!candidate.held()) {
        waitsOn.put(candidate.repository(), owedUpstreams(candidate, producers, owed));
      }
    }
    Candidate leastBlocked = null;
    Set<String> leastBlockedBy = null;
    for (Candidate candidate : candidates) {
      if (candidate.held()) {
        continue;
      }
      Set<String> upstreams = waitsOn.get(candidate.repository());
      if (upstreams.isEmpty() || !free.containsAll(upstreams)) {
        // READY (it was a pick, or there was no room for it), or waiting on a release in flight:
        // neither is a knot, and the second would be the stale build on purpose.
        continue;
      }
      boolean knotted = true;
      for (String upstream : upstreams) {
        if (!reaches(upstream, candidate.repository(), waitsOn)) {
          // An upstream outside its own knot goes first by the ordinary rule; wait for it.
          knotted = false;
          break;
        }
      }
      if (knotted && (leastBlockedBy == null || upstreams.size() < leastBlockedBy.size())) {
        leastBlocked = candidate;
        leastBlockedBy = upstreams;
      }
    }
    if (leastBlocked == null) {
      return Optional.empty();
    }
    return Optional.of(new Pick(leastBlocked, Set.copyOf(leastBlockedBy), true));
  }

  /** Whether {@code from} waits, through any chain of free candidates, on {@code to}. */
  private static boolean reaches(String from, String to, Map<String, Set<String>> waitsOn) {
    Set<String> seen = new HashSet<>();
    Deque<String> frontier = new ArrayDeque<>();
    frontier.push(from);
    while (!frontier.isEmpty()) {
      String at = frontier.pop();
      if (at.equals(to)) {
        return true;
      }
      if (seen.add(at)) {
        frontier.addAll(waitsOn.getOrDefault(at, Set.of()));
      }
    }
    return false;
  }

  /**
   * Where one owed repository stands in the queue.
   *
   * @param candidate the repository and the changes its bump would carry
   * @param reason {@code READY} (nothing owed sits below it — the first one is the pick), {@code
   *     BLOCKED} (it waits on other owed repositories) or {@code HELD} (its branch is pushed and it
   *     waits on its own release)
   * @param blockedBy the owed upstreams it waits on, empty unless BLOCKED
   */
  public record Standing(Candidate candidate, String reason, Set<String> blockedBy) {}

  /**
   * THE WHOLE OWED SET IN THE ORDER IT WILL BE HANDED OUT, each entry saying why it is where it is.
   *
   * <p>{@link #nextUpTo} answers one question — what goes now — and that was the only thing anybody
   * could ask. "Fifteen repositories are owed and nothing has been sent" and "the scheduler is
   * dead" were therefore the same picture from every surface, which is what let this class's
   * arming bug sit unnoticed for a day. This is the same reasoning laid out for a reader rather
   * than collapsed to a pick: READY first in the caller's listing order — least-recently-bumped
   * first, so the head of this list IS what {@link #next} returns whenever anything is READY, and a
   * reader can see why a repository is where it is rather than reading an alphabet — then BLOCKED
   * by fewest owed upstreams, then HELD.
   *
   * <p>It decides nothing. A caller that dispatched the head of this list instead of calling {@link
   * #nextUpTo} would skip the cycle rule, which is the one place the two differ: a knot's members
   * are all BLOCKED here, and which of them is broken is {@link #nextUpTo}'s guess alone.
   */
  public static List<Standing> standing(List<Candidate> candidates, Map<String, String> producers) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    Set<String> owed = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      owed.add(candidate.repository());
    }
    List<Standing> ready = new ArrayList<>();
    List<Standing> blocked = new ArrayList<>();
    List<Standing> held = new ArrayList<>();
    for (Candidate candidate : candidates) {
      if (candidate.held()) {
        held.add(new Standing(candidate, "HELD", Set.of()));
        continue;
      }
      Set<String> upstreams = owedUpstreams(candidate, producers, owed);
      if (upstreams.isEmpty()) {
        ready.add(new Standing(candidate, "READY", Set.of()));
      } else {
        blocked.add(new Standing(candidate, "BLOCKED", upstreams));
      }
    }
    blocked.sort(Comparator.comparingInt(one -> one.blockedBy().size()));
    List<Standing> all = new ArrayList<>(ready);
    all.addAll(blocked);
    all.addAll(held);
    return List.copyOf(all);
  }

  /**
   * The candidates this one is waiting on — the owed repositories that publish something it is
   * about to bump to.
   */
  public static Set<String> owedUpstreams(
      Candidate candidate, Map<String, String> producers, Set<String> owed) {
    Set<String> upstreams = new LinkedHashSet<>();
    for (Change change : candidate.changes()) {
      String producer = producerOf(change, producers, owed);
      if (producer == null || producer.equals(candidate.repository()) || !owed.contains(producer)) {
        continue;
      }
      upstreams.add(producer);
    }
    return upstreams;
  }

  private static String producerOf(Change change, Map<String, String> producers, Set<String> owed) {
    if (change == null || change.name() == null) {
      return null;
    }
    String published = producers.get(ArtifactGraph.producerKey(change.ecosystem(), change.name()));
    if (published != null) {
      return published;
    }
    // A submodule is named by the repository it is, and no artifact row is involved at all.
    boolean gitlink = Ecosystem.GITLINK.wireName().equals(change.ecosystem());
    return gitlink && owed.contains(change.name()) ? change.name() : null;
  }
}

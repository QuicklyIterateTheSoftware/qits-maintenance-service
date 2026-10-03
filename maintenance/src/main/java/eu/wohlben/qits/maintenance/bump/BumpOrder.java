package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.ArrayList;
import java.util.Comparator;
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
 * cycles occur and that the graph is not guaranteed acyclic, so "every candidate is blocked" is a
 * state this has to have an answer for: the least-blocked candidate goes, the caller says so in the
 * log, and the next tick asks again against a graph one release smaller. The alternative — waiting
 * for an unblocked candidate that cannot exist — is a night in which nothing is bumped at all.
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
 * <p>So held is a third state and it is spelled out here rather than at the caller: {@link #next}
 * counts held candidates in {@code owed}, so they keep blocking, and never picks one, so they never
 * re-dispatch. <b>When every candidate is held the answer is no pick at all</b> — the ordinary
 * waiting state of a night whose releases are in flight, not a failure and not a reason for the
 * caller to close its window.
 *
 * <p><b>And a cycle is judged among the free candidates only.</b> A free candidate blocked solely by
 * held ones is not in a cycle: the thing it waits for is a release already on its way, and the
 * moment it lands the next tick sees an unblocked candidate. Breaking that "cycle" would dispatch
 * precisely the build against the stale pin the ordering is for. Only when every free candidate
 * waits on another <i>free</i> candidate is there a knot nothing but a pick can undo.
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
   * @param cycleBroken whether it was picked despite being blocked, because everything was
   */
  public record Pick(Candidate candidate, Set<String> blockedBy, boolean cycleBroken) {}

  /**
   * The next viable bump: the first free candidate nothing else owed sits below, in the order the
   * caller listed them — which is least-recently-bumped first, for the reason the class doc gives.
   *
   * <p><b>{@code owed} is built from ALL the candidates and the pick is taken from the free ones.</b>
   * The two sets are deliberately different — that is how a repository waiting on a release it has
   * already been bumped for goes on holding its consumers back without being sent again.
   *
   * @param candidates every repository owed a bump this tick, held ones included, in a stable order
   *     — the caller's, and the tiebreak among equally ready candidates: see the class doc
   * @param producers {@link ArtifactGraph#producers()} — coordinate to publishing repository
   * @return the bump to send, or empty when there is none to send right now — no candidates at all,
   *     or every free one still waiting on a release in flight
   */
  public static Optional<Pick> next(List<Candidate> candidates, Map<String, String> producers) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    Set<String> owed = new LinkedHashSet<>();
    Set<String> free = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      owed.add(candidate.repository());
      if (!candidate.held()) {
        free.add(candidate.repository());
      }
    }

    Candidate leastBlocked = null;
    Set<String> leastBlockedBy = null;
    boolean waitingOnARelease = false;
    for (Candidate candidate : candidates) {
      if (candidate.held()) {
        continue;
      }
      Set<String> upstreams = owedUpstreams(candidate, producers, owed);
      if (upstreams.isEmpty()) {
        return Optional.of(new Pick(candidate, Set.of(), false));
      }
      if (upstreams.stream().noneMatch(free::contains)) {
        // Blocked only by held candidates: a release already in flight is what it waits for, and
        // that wait ends by itself. Counting this as a cycle would dispatch the stale build.
        waitingOnARelease = true;
        continue;
      }
      if (leastBlockedBy == null || upstreams.size() < leastBlockedBy.size()) {
        leastBlocked = candidate;
        leastBlockedBy = upstreams;
      }
    }
    if (leastBlocked == null || waitingOnARelease) {
      return Optional.empty();
    }
    // Every free one waits on another free one: a cycle, and somebody has to move first.
    return Optional.of(new Pick(leastBlocked, Set.copyOf(leastBlockedBy), true));
  }

  /**
   * UP TO {@code n} BUMPS TO SEND NOW — every free candidate nothing else owed sits below, in the
   * caller's order, as many as qits-ci has free slots for.
   *
   * <p><b>Two READY candidates can always go together</b>: neither has an owed upstream, so neither
   * is waiting on the other, and sending both in one tick costs nothing the ordering exists to
   * prevent. That is what makes a batch safe here and is the whole of qits-882 — the dispatcher used
   * to hand out one per tick only when qits-ci was idle, while eight slots sat seven-free.
   *
   * <p><b>The cycle rule is unchanged and never multiplied.</b> Only when NOT ONE free candidate is
   * READY does this fall back to {@link #next} — a single least-blocked pick marked {@code
   * cycleBroken}, or nothing when everything waits on a release in flight. A cycle break is a
   * guess, and one guess per tick against a graph one release smaller is the most this should ever
   * make, however many slots are free.
   *
   * @param candidates every repository owed a bump this tick, held ones included, in a stable order
   * @param producers {@link ArtifactGraph#producers()} — coordinate to publishing repository
   * @param n how many may go — qits-ci's free slots; zero or less is an empty answer
   * @return the picks in dispatch order: up to {@code n} READY ones, else at most one cycle break,
   *     else none. For {@code n == 1} the head is exactly what {@link #next} returns
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
    List<Pick> ready = new ArrayList<>();
    for (Candidate candidate : candidates) {
      if (candidate.held()) {
        continue;
      }
      if (owedUpstreams(candidate, producers, owed).isEmpty()) {
        ready.add(new Pick(candidate, Set.of(), false));
        if (ready.size() == n) {
          break;
        }
      }
    }
    if (!ready.isEmpty()) {
      return List.copyOf(ready);
    }
    return next(candidates, producers).map(List::of).orElse(List.of());
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
   * <p>{@link #next} answers one question — what goes now — and that was the only thing anybody
   * could ask. "Fifteen repositories are owed and nothing has been sent" and "the scheduler is
   * dead" were therefore the same picture from every surface, which is what let this class's
   * arming bug sit unnoticed for a day. This is the same reasoning laid out for a reader rather
   * than collapsed to a pick: READY first in the caller's listing order — least-recently-bumped
   * first, so the head of this list IS what {@link #next} returns and a reader can see why a
   * repository is where it is rather than reading an alphabet — then BLOCKED by fewest owed
   * upstreams, then HELD.
   *
   * <p>It decides nothing. A caller that dispatched the head of this list instead of calling {@link
   * #next} would skip the cycle rule, which is the one place the two differ.
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

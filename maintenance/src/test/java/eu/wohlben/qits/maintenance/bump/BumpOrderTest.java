package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * WHICH OWED BUMP GOES FIRST.
 *
 * <p>A plain unit test and not a {@code @QuarkusTest}: the ordering is a function of two maps, and
 * the only way to write the interesting cases — a three-hop chain, a cycle, a dependency on
 * somebody who is NOT owed — is to state the graph rather than build an estate that happens to have
 * one.
 */
class BumpOrderTest {

  private static final String LIB = "qits-eventstream-javalib";
  private static final String SERVICE = "qits-ci-service";
  private static final String WRAPPER = "qits-qits";

  /** {@code (ecosystem, name)} to the repository that publishes it, as {@code ArtifactGraph} answers it. */
  private static final Map<String, String> PRODUCERS =
      Map.of(
          ArtifactGraph.producerKey("maven", "eu.wohlben.qits:qits-eventstream"), LIB,
          ArtifactGraph.producerKey("docker", "qits/qits-ci"), SERVICE);

  private static BumpOrder.Candidate candidate(String repository, Change... changes) {
    return new BumpOrder.Candidate(repository, "dependencies", List.of(changes));
  }

  /** Owed, already bumped, waiting on the release that will move its main. */
  private static BumpOrder.Candidate held(String repository, Change... changes) {
    return new BumpOrder.Candidate(repository, "dependencies", List.of(changes), true);
  }

  private static Change on(String ecosystem, String name) {
    return new Change(ecosystem, "pom.xml", name, "1", "2", "property");
  }

  /**
   * THE WASTE THIS EXISTS TO STOP. The library and the service that consumes it are both owed a
   * bump; sending the service first builds it against the pin it is about to be handed anyway.
   */
  @Test
  void theDeepestUpstreamGoesFirstWhateverTheListingOrderIs() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            // The consumer is FIRST in listing order, which is exactly the arrangement a loop over
            // the inventory would have got wrong.
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            candidate(LIB, on("maven", "io.quarkus.platform:quarkus-bom")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertEquals(LIB, pick.candidate().repository(), "the library is what nothing else waits below");
    assertFalse(pick.cycleBroken());
  }

  /**
   * A dependency on a repository with NOTHING owed is not a reason to wait: that release already
   * happened, and its version is the one the change is moving to.
   */
  @Test
  void anUpstreamThatIsNotOwedABumpBlocksNothing() {
    List<BumpOrder.Candidate> candidates =
        List.of(candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertEquals(SERVICE, pick.candidate().repository());
    assertTrue(pick.blockedBy().isEmpty());
  }

  /**
   * <b>A GITLINK HAS NO ARTIFACT AT ALL</b>, and its name IS the repository — the same string the
   * catalog lists. The wrapper waiting on a submodule is the estate's commonest bottom-of-chain
   * edge and the artifact ledger cannot see it.
   */
  @Test
  void aSubmoduleEdgeIsMatchedByNameBecauseNoArtifactCarriesIt() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(WRAPPER, on(Ecosystem.GITLINK.wireName(), SERVICE)),
            candidate(SERVICE, on("maven", "io.quarkus.platform:quarkus-bom")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertEquals(SERVICE, pick.candidate().repository(), "the wrapper carries the submodule, not the other way round");
  }

  /** And only for GITLINK: a package that happens to be called like a repository invents no edge. */
  @Test
  void aPackageNamedLikeARepositoryIsNotASubmodule() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(WRAPPER, on("npm", SERVICE)),
            candidate(SERVICE, on("maven", "io.quarkus.platform:quarkus-bom")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertEquals(WRAPPER, pick.candidate().repository(), "nothing blocks the wrapper, so listing order decides");
  }

  /**
   * <b>A CYCLE DEGRADES TO A PICK, NEVER TO A STALL.</b> {@code ArtifactGraph} says cycles occur
   * and the graph is not guaranteed acyclic, so the answer to "everything is blocked" has to be a
   * repository and a WARN — a night in which nothing is bumped is the worse outcome.
   */
  @Test
  void aCycleStillDispatchesSomething() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            candidate(LIB, on("docker", "qits/qits-ci")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertTrue(pick.cycleBroken(), "both wait on the other, and one of them has to move");
    assertFalse(pick.blockedBy().isEmpty(), "and the log says what it was waiting on");
  }

  @Test
  void nothingOwedIsNoPick() {
    assertEquals(Optional.empty(), BumpOrder.next(List.of(), PRODUCERS));
  }

  /**
   * <b>THE RE-DISPATCH LOOP, STATED AS AN ORDERING FACT.</b> The library's branch is pushed and its
   * release is open; pending is read off main, so it is still owed and still a candidate. Picking it
   * again is the wasted CI run that came back NOTHING_TO_DO every fifteen seconds — so it is never
   * the pick, and the free candidate behind it goes instead.
   */
  @Test
  void aHeldCandidateIsNeverThePickAndAFreeOneGoesInstead() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            held(LIB, on("maven", "io.quarkus.platform:quarkus-bom")),
            candidate(WRAPPER, on("npm", "@angular/core")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertEquals(WRAPPER, pick.candidate().repository(), "the held library is not dispatchable");
    assertFalse(pick.cycleBroken());
  }

  /**
   * <b>AND HELD STILL BLOCKS.</b> A repository that has pushed its branch stops being dispatchable
   * but has not released; a consumer sent now builds against the pin the pending release is about to
   * replace, which is exactly the two-nights-for-one-hop waste this class exists to collapse.
   */
  @Test
  void aCandidateWhoseUpstreamIsHeldStillWaits() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            held(LIB, on("maven", "io.quarkus.platform:quarkus-bom")));

    assertEquals(
        Optional.empty(),
        BumpOrder.next(candidates, PRODUCERS),
        "the library's release is in flight and the service waits for it");
  }

  /**
   * Every candidate held is the ordinary waiting state of a night whose releases are all in flight —
   * no pick, and emphatically not a cycle: the caller must not break anything and must not close its
   * window on it.
   */
  @Test
  void everythingHeldDispatchesNothing() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            held(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            held(LIB, on("docker", "qits/qits-ci")));

    assertEquals(Optional.empty(), BumpOrder.next(candidates, PRODUCERS));
  }

  /**
   * A knot is only a knot among the candidates that could actually move. Held candidates are in
   * {@code owed} so they block, but a "cycle" that runs through one of them unties itself the moment
   * that release lands — breaking it would dispatch the stale build on purpose.
   */
  @Test
  void aCycleThroughAHeldCandidateIsNotBroken() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            held(LIB, on("docker", "qits/qits-ci")));

    assertEquals(Optional.empty(), BumpOrder.next(candidates, PRODUCERS));
  }

  /**
   * <b>QITS-882: AS MANY AS THERE ARE FREE SLOTS, IN THE CALLER'S ORDER.</b> Every READY candidate
   * can go in the same tick — none waits on another — so with slots for them all, they all go, in
   * exactly the listing order (least-recently-bumped first, at the caller).
   */
  @Test
  void everyReadyCandidateGoesUpToTheFreeSlotsInListingOrder() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate("qits-c", on("maven", "io.quarkus.platform:quarkus-bom")),
            candidate("qits-a", on("maven", "io.quarkus.platform:quarkus-bom")),
            candidate("qits-b", on("maven", "io.quarkus.platform:quarkus-bom")),
            candidate("qits-d", on("maven", "io.quarkus.platform:quarkus-bom")),
            candidate("qits-e", on("maven", "io.quarkus.platform:quarkus-bom")));

    assertEquals(
        List.of("qits-c", "qits-a", "qits-b"),
        names(BumpOrder.nextUpTo(candidates, PRODUCERS, 3)),
        "three slots, three of the five, the first three as listed");
    assertEquals(5, BumpOrder.nextUpTo(candidates, PRODUCERS, 7).size(), "never more than are READY");
    assertTrue(BumpOrder.nextUpTo(candidates, PRODUCERS, 0).isEmpty(), "no slot, no pick");
    assertTrue(
        BumpOrder.nextUpTo(candidates, PRODUCERS, 3).stream().noneMatch(BumpOrder.Pick::cycleBroken));
  }

  /**
   * <b>Free slots never send a consumer beside its owed upstream.</b> The service waits on the
   * library whatever the capacity — the extra slot stays empty rather than building the service
   * against the pin it is about to be handed — while an unrelated READY repository does go.
   */
  @Test
  void freeSlotsDoNotLetAConsumerGoBesideItsOwedUpstream() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            candidate(LIB, on("maven", "io.quarkus.platform:quarkus-bom")),
            held(WRAPPER, on("npm", "left-pad")));

    assertEquals(List.of(LIB), names(BumpOrder.nextUpTo(candidates, PRODUCERS, 5)));
  }

  /**
   * <b>ONE CYCLE BREAK PER TICK, HOWEVER MANY SLOTS ARE FREE.</b> Breaking a cycle is a guess; the
   * next tick asks again against a graph one release smaller. Two guesses at once would be two
   * builds against stale pins where one would do.
   */
  @Test
  void aCycleBreaksOnceEvenWithManyFreeSlots() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            candidate(LIB, on("docker", "qits/qits-ci")));

    List<BumpOrder.Pick> picks = BumpOrder.nextUpTo(candidates, PRODUCERS, 8);

    assertEquals(1, picks.size(), "one knot, one pick");
    assertTrue(picks.get(0).cycleBroken());
    assertEquals(BumpOrder.next(candidates, PRODUCERS).orElseThrow(), picks.get(0));
  }

  /** Everything waiting on a release in flight is still nothing, with slots or without. */
  @Test
  void aCycleThroughAHeldCandidateIsNotBrokenWhateverTheSlots() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            held(LIB, on("docker", "qits/qits-ci")));

    assertTrue(BumpOrder.nextUpTo(candidates, PRODUCERS, 8).isEmpty());
  }

  /** {@code n == 1} is exactly {@link BumpOrder#next}, so the single-slot estate is unchanged. */
  @Test
  void oneSlotIsExactlyNext() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            candidate(WRAPPER, on("npm", "left-pad")),
            candidate(LIB, on("maven", "io.quarkus.platform:quarkus-bom")));

    assertEquals(
        List.of(BumpOrder.next(candidates, PRODUCERS).orElseThrow()),
        BumpOrder.nextUpTo(candidates, PRODUCERS, 1));
  }

  private static List<String> names(List<BumpOrder.Pick> picks) {
    return picks.stream().map(pick -> pick.candidate().repository()).toList();
  }
}

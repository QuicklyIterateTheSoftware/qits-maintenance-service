package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.error.NoSuchRepositoryException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The settle door's three rules</b> (qits-1258), held against a settle the test controls with
 * latches — no peer, no database, no sleep deciding an outcome: one settle per (request, fold) however
 * many ask; an answer within the budget, UNKNOWN "settling" when the settle outlasts it, the real
 * one once it is done; one request's stuck settle never delays another's; and a refusal inside the
 * budget reaches the caller as the exception it was. And (qits-1697) an answer nobody waited out is
 * kept for the next ask of its fold, once, within its bound, with a fresh settle started behind it.
 */
class SettleGateTest {

  private static final String REQUEST_A = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a";

  private static final String REQUEST_B = "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b";

  private static final String FOLD_1 = "1111111111111111111111111111111111111111";

  private static final String FOLD_2 = "2222222222222222222222222222222222222222";

  private static final Duration LONG = Duration.ofSeconds(20);

  private static final Duration SHORT = Duration.ofMillis(300);

  /** A settle the test drives: each (request, fold) blocks on its own latch until released. */
  private final class Held implements SettleGate.Settler {

    final AtomicInteger calls = new AtomicInteger();

    final List<String> asked = new CopyOnWriteArrayList<>();

    final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();

    final Map<String, CountDownLatch> entered = new ConcurrentHashMap<>();

    final Map<String, CountDownLatch> finished = new ConcurrentHashMap<>();

    CountDownLatch gate(String requestId, String fold) {
      return gates.computeIfAbsent(requestId + fold, key -> new CountDownLatch(1));
    }

    CountDownLatch entered(String requestId, String fold) {
      return entered.computeIfAbsent(requestId + fold, key -> new CountDownLatch(1));
    }

    CountDownLatch finished(String requestId, String fold) {
      return finished.computeIfAbsent(requestId + fold, key -> new CountDownLatch(1));
    }

    void open(String requestId, String fold) {
      gate(requestId, fold).countDown();
    }

    @Override
    public ReleaseRequestAutomationsDto settle(String requestId, AutomationService.Fold fold) {
      int call = calls.incrementAndGet();
      asked.add(requestId + "@" + fold.foldSha());
      entered(requestId, fold.foldSha()).countDown();
      try {
        if (!gate(requestId, fold.foldSha()).await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      finished(requestId, fold.foldSha()).countDown();
      return answer(requestId, fold.foldSha(), "FRESH", "decided by settle " + call);
    }
  }

  private static ReleaseRequestAutomationsDto answer(
      String requestId, String foldSha, String state, String detail) {
    return new ReleaseRequestAutomationsDto(
        requestId,
        foldSha,
        List.of(
            new AutomationDto(
                "estate-pins", "Estate pins", state, detail, null, List.of(), null, null,
                Instant.now(), null, null)));
  }

  /** What the gate answers while a settle runs: AutomationService.settling's shape. */
  private static ReleaseRequestAutomationsDto interim(
      String requestId, String foldSha, String detail) {
    return answer(requestId, foldSha, "UNKNOWN", detail);
  }

  private static AutomationService.Fold fold(String sha) {
    return new AutomationService.Fold("qits-qits", sha, null, null, List.of("main"), null);
  }

  private final ExecutorService callers = Executors.newCachedThreadPool();

  private SettleGate gate;

  @AfterEach
  void stop() {
    callers.shutdownNow();
    if (gate != null) {
      gate.shutdown();
    }
  }

  /** Waits — bounded — until no settle of that request is in flight any more. */
  private static void idle(SettleGate gate, String requestId) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (gate.inFlight(requestId)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("the settle never ended");
      }
      Thread.onSpinWait();
    }
  }

  /** The gate's clock, moved by hand. */
  private final AtomicLong now = new AtomicLong();

  private SettleGate keeping(SettleGate.Settler settler, Duration budget) {
    return new SettleGate(
        settler, SettleGateTest::interim, budget, 4, Duration.ofMinutes(2), now::get);
  }

  /** Waits — bounded — until a caller thread is parked waiting on a settle. */
  private static void parked(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (thread.getState() != Thread.State.TIMED_WAITING) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("the caller never waited: " + thread.getState());
      }
      Thread.onSpinWait();
    }
  }

  /** (a) Two asks for one request and one fold settle it ONCE, and both get that one answer. */
  @Test
  void twoAsksForOneFoldRunOneSettle() throws Exception {
    Held held = new Held();
    gate = new SettleGate(held, SettleGateTest::interim, LONG, 4);

    Future<ReleaseRequestAutomationsDto> first =
        callers.submit(() -> gate.settle(REQUEST_A, fold(FOLD_1)));
    assertTrue(held.entered(REQUEST_A, FOLD_1).await(10, TimeUnit.SECONDS));
    Thread[] second = new Thread[1];
    CountDownLatch started = new CountDownLatch(1);
    Future<ReleaseRequestAutomationsDto> repeat =
        callers.submit(
            () -> {
              second[0] = Thread.currentThread();
              started.countDown();
              return gate.settle(REQUEST_A, fold(FOLD_1));
            });
    assertTrue(started.await(10, TimeUnit.SECONDS));
    parked(second[0]);
    held.open(REQUEST_A, FOLD_1);

    ReleaseRequestAutomationsDto one = first.get(10, TimeUnit.SECONDS);
    ReleaseRequestAutomationsDto two = repeat.get(10, TimeUnit.SECONDS);
    assertEquals(1, held.calls.get(), "the repeat joined the settle in flight");
    assertSame(one, two, "and was given its answer");
    assertEquals("FRESH", one.automations().getFirst().state());
  }

  /**
   * (b) A settle that outlasts the budget is answered within it — UNKNOWN, settling — and keeps
   * running; the ask after it ends gets the real answer.
   */
  @Test
  void aSettleOutlastingTheBudgetIsAnsweredSettlingAndTheNextAskGetsTheAnswer() throws Exception {
    Held held = new Held();
    gate = new SettleGate(held, SettleGateTest::interim, SHORT, 4);

    long start = System.nanoTime();
    ReleaseRequestAutomationsDto early = gate.settle(REQUEST_A, fold(FOLD_1));
    long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertTrue(took < 5_000, "answered within the budget, not after the settle: " + took + " ms");
    AutomationDto entry = early.automations().getFirst();
    assertEquals("UNKNOWN", entry.state());
    assertEquals(SettleGate.SETTLING, entry.detail());
    assertEquals(FOLD_1, early.foldSha());

    held.open(REQUEST_A, FOLD_1);
    assertTrue(held.finished(REQUEST_A, FOLD_1).await(10, TimeUnit.SECONDS), "it kept running");
    // Asked again after it ended: joined while it was still leaving, or settled afresh — never the
    // settling answer, and never a stale one served for ever.
    ReleaseRequestAutomationsDto later = gate.settle(REQUEST_A, fold(FOLD_1));
    assertEquals("FRESH", later.automations().getFirst().state(), later.toString());
    ReleaseRequestAutomationsDto again = gate.settle(REQUEST_A, fold(FOLD_1));
    assertEquals("FRESH", again.automations().getFirst().state());
    assertTrue(held.calls.get() >= 2, "a finished settle leaves the table: the next ask runs one");
  }

  /** (c) One request's stuck settle holds one thread, and another request settles beside it. */
  @Test
  void aStuckRequestDoesNotDelayAnother() throws Exception {
    Held held = new Held();
    gate = new SettleGate(held, SettleGateTest::interim, SHORT, 2);
    held.open(REQUEST_B, FOLD_1);

    // A sticks; and asked again and again, it still holds ONE thread, not one per ask.
    for (int ask = 0; ask < 5; ask++) {
      assertEquals(
          "UNKNOWN", gate.settle(REQUEST_A, fold(FOLD_1)).automations().getFirst().state());
    }
    // Queued behind A, B would outlast the same short budget and be answered UNKNOWN: FRESH is the
    // proof that it ran on a thread A does not hold.
    ReleaseRequestAutomationsDto other = gate.settle(REQUEST_B, fold(FOLD_1));

    assertEquals("FRESH", other.automations().getFirst().state(), "B is decided, not settling");
    assertEquals(
        List.of(REQUEST_A + "@" + FOLD_1, REQUEST_B + "@" + FOLD_1), held.asked,
        "A was settled once for all its asks");
    held.open(REQUEST_A, FOLD_1);
  }

  /** (d) A refusal inside the budget is the caller's, as the very exception the settle threw. */
  @Test
  void aRefusalInsideTheBudgetIsRethrownAsItWas() {
    NoSuchRepositoryException refusal = NoSuchRepositoryException.scanQueued("qits-qits");
    gate =
        new SettleGate(
            (requestId, fold) -> {
              throw refusal;
            },
            SettleGateTest::interim,
            LONG,
            4);

    NoSuchRepositoryException thrown =
        assertThrows(NoSuchRepositoryException.class, () -> gate.settle(REQUEST_A, fold(FOLD_1)));
    assertSame(refusal, thrown);
  }

  /** A malformed ask is a 400 at once, and nothing is settled for it. */
  @Test
  void aMalformedAskIsRefusedBeforeAnythingRuns() {
    Held held = new Held();
    gate = new SettleGate(held, SettleGateTest::interim, LONG, 4);

    assertThrows(BadRequestException.class, () -> gate.settle("not-a-uuid", fold(FOLD_1)));
    assertThrows(BadRequestException.class, () -> gate.settle(REQUEST_A, fold("main")));
    assertThrows(BadRequestException.class, () -> gate.settle(REQUEST_A, null));
    assertEquals(0, held.calls.get());
  }

  /**
   * A NEWER fold of a request being settled never runs beside the older one: past the budget it is
   * answered settling, about its own fold.
   */
  @Test
  void aNewerFoldIsNotSettledBesideTheOlder() throws Exception {
    Held held = new Held();
    gate = new SettleGate(held, SettleGateTest::interim, SHORT, 4);
    held.open(REQUEST_A, FOLD_2);

    assertEquals(
        "UNKNOWN", gate.settle(REQUEST_A, fold(FOLD_1)).automations().getFirst().state());
    ReleaseRequestAutomationsDto newer = gate.settle(REQUEST_A, fold(FOLD_2));

    assertEquals("UNKNOWN", newer.automations().getFirst().state());
    assertEquals(FOLD_2, newer.foldSha(), "answered about the fold it was asked about");
    assertEquals(List.of(REQUEST_A + "@" + FOLD_1), held.asked, "not run beside the older");
    held.open(REQUEST_A, FOLD_1);
  }

  /** …and within its budget it outlasts the older settle, then runs its own. */
  @Test
  void aNewerFoldIsSettledOnceTheOlderEnds() throws Exception {
    Held held = new Held();
    gate = new SettleGate(held, SettleGateTest::interim, LONG, 4);
    held.open(REQUEST_A, FOLD_2);

    Future<ReleaseRequestAutomationsDto> older =
        callers.submit(() -> gate.settle(REQUEST_A, fold(FOLD_1)));
    assertTrue(held.entered(REQUEST_A, FOLD_1).await(10, TimeUnit.SECONDS));
    Thread[] asker = new Thread[1];
    CountDownLatch started = new CountDownLatch(1);
    Future<ReleaseRequestAutomationsDto> newer =
        callers.submit(
            () -> {
              asker[0] = Thread.currentThread();
              started.countDown();
              return gate.settle(REQUEST_A, fold(FOLD_2));
            });
    assertTrue(started.await(10, TimeUnit.SECONDS));
    parked(asker[0]);
    assertEquals(List.of(REQUEST_A + "@" + FOLD_1), held.asked, "it waits; it does not run");

    held.open(REQUEST_A, FOLD_1);

    assertEquals(FOLD_1, older.get(10, TimeUnit.SECONDS).foldSha());
    ReleaseRequestAutomationsDto settled = newer.get(10, TimeUnit.SECONDS);
    assertEquals("FRESH", settled.automations().getFirst().state());
    assertEquals(FOLD_2, settled.foldSha());
    assertEquals(List.of(REQUEST_A + "@" + FOLD_1, REQUEST_A + "@" + FOLD_2), held.asked);
  }

  /**
   * (qits-1697, the reproduction) A settle that outlasts the budget used to hand its answer to a
   * future nobody waited on any more; a kind trigger stores no row for was then decided afresh, and
   * answered settling, on every ask for ever. Now the ask after it ends is given that answer.
   */
  @Test
  void aSettleNobodyWaitedOutIsServedToTheNextAskOfItsFold() throws Exception {
    Held held = new Held();
    gate = keeping(held, SHORT);

    ReleaseRequestAutomationsDto early = gate.settle(REQUEST_A, fold(FOLD_1));
    assertEquals(SettleGate.SETTLING, early.automations().getFirst().detail());

    held.open(REQUEST_A, FOLD_1);
    idle(gate, REQUEST_A);
    ReleaseRequestAutomationsDto later = gate.settle(REQUEST_A, fold(FOLD_1));

    assertEquals("FRESH", later.automations().getFirst().state(), later.toString());
    assertEquals("decided by settle 1", later.automations().getFirst().detail());
  }

  /** Serving a kept answer starts a fresh settle, so the ask after it sees rows stored since. */
  @Test
  void servingAKeptAnswerStartsAFreshSettle() throws Exception {
    Held held = new Held();
    gate = keeping(held, SHORT);

    gate.settle(REQUEST_A, fold(FOLD_1));
    held.open(REQUEST_A, FOLD_1);
    idle(gate, REQUEST_A);
    assertEquals(1, held.calls.get());

    assertEquals(
        "decided by settle 1",
        gate.settle(REQUEST_A, fold(FOLD_1)).automations().getFirst().detail());
    idle(gate, REQUEST_A);
    assertEquals(2, held.calls.get(), "the serve started a settle behind it");

    // Nobody waited for that one either: it is what the next ask is given, and it starts another.
    assertEquals(
        "decided by settle 2",
        gate.settle(REQUEST_A, fold(FOLD_1)).automations().getFirst().detail());
    idle(gate, REQUEST_A);
    assertEquals(3, held.calls.get());
  }

  /** A kept answer is about its fold: an ask of another fold is never given it. */
  @Test
  void aKeptAnswerIsNotServedToAnotherFold() throws Exception {
    Held held = new Held();
    gate = keeping(held, SHORT);
    held.open(REQUEST_A, FOLD_2);

    gate.settle(REQUEST_A, fold(FOLD_1));
    held.open(REQUEST_A, FOLD_1);
    idle(gate, REQUEST_A);

    ReleaseRequestAutomationsDto newer = gate.settle(REQUEST_A, fold(FOLD_2));
    assertEquals(FOLD_2, newer.foldSha());
    assertEquals("decided by settle 2", newer.automations().getFirst().detail());
    // …and the older fold's answer was dropped, not kept for later: this ask settles afresh.
    ReleaseRequestAutomationsDto older = gate.settle(REQUEST_A, fold(FOLD_1));
    assertEquals("decided by settle 3", older.automations().getFirst().detail());
    assertEquals(
        List.of(REQUEST_A + "@" + FOLD_1, REQUEST_A + "@" + FOLD_2, REQUEST_A + "@" + FOLD_1),
        held.asked);
  }

  /** A kept answer older than the bound is dropped, never served. */
  @Test
  void aKeptAnswerPastItsBoundIsNotServed() throws Exception {
    Held held = new Held();
    gate = keeping(held, SHORT);

    gate.settle(REQUEST_A, fold(FOLD_1));
    held.open(REQUEST_A, FOLD_1);
    idle(gate, REQUEST_A);
    now.addAndGet(Duration.ofMinutes(2).plusMillis(1).toNanos());

    ReleaseRequestAutomationsDto later = gate.settle(REQUEST_A, fold(FOLD_1));
    assertEquals("decided by settle 2", later.automations().getFirst().detail());
    assertEquals(2, held.calls.get());
  }

  /** An answer a waiter received within its budget is not kept: the next ask settles afresh. */
  @Test
  void anAnswerGivenDirectlyIsNotKept() throws Exception {
    Held held = new Held();
    gate = keeping(held, LONG);
    held.open(REQUEST_A, FOLD_1);

    assertEquals(
        "decided by settle 1",
        gate.settle(REQUEST_A, fold(FOLD_1)).automations().getFirst().detail());
    idle(gate, REQUEST_A);
    assertEquals(
        "decided by settle 2",
        gate.settle(REQUEST_A, fold(FOLD_1)).automations().getFirst().detail());
    idle(gate, REQUEST_A);
    assertEquals(2, held.calls.get(), "no background settle was started for a kept copy");
  }

  /** A settle that fails past the budget keeps nothing: the next ask settles afresh. */
  @Test
  void aFailedSettleIsNotKept() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    gate =
        keeping(
            (requestId, fold) -> {
              int call = calls.incrementAndGet();
              if (call == 1) {
                try {
                  release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("the git host fell over");
              }
              return answer(requestId, fold.foldSha(), "FRESH", "decided by settle " + call);
            },
            SHORT);

    assertEquals(
        SettleGate.SETTLING, gate.settle(REQUEST_A, fold(FOLD_1)).automations().getFirst().detail());
    release.countDown();
    idle(gate, REQUEST_A);

    ReleaseRequestAutomationsDto later = gate.settle(REQUEST_A, fold(FOLD_1));
    assertEquals("decided by settle 2", later.automations().getFirst().detail());
    assertEquals(2, calls.get());
  }
}

package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.error.MaintenanceException;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The settle door's gate</b> (qits-1258): every {@code POST /release-requests/{id}/automations}
 * goes through here rather than straight into {@link AutomationService#trigger}.
 *
 * <h2>The incident</h2>
 *
 * <p>qits-projects asks every open request's automations to be settled every thirty seconds, and
 * waits ten. The door used to settle on the HTTP thread, start to finish. A nine-branch wrapper
 * request (qits-qits-rr-137) planned its estate pins per source branch, hundreds of git-host reads,
 * and never fitted in ten seconds: qits-projects gave up on the answer and asked again, while the
 * abandoned settle kept reading. Every retry started another one beside it, the git host queued
 * them until a single read took seconds, and then EVERY request's settle — each one cheap on its own
 * — timed out behind that queue. No release request on the estate moved for an hour.
 *
 * <p>The estate plan is cheap again (one tree read per fold), but the door must not depend on every
 * settle being cheap, so it keeps three rules.
 *
 * <h2>The three rules</h2>
 *
 * <ol>
 *   <li><b>Single flight.</b> At most one settle per request runs at a time. An ask for the fold
 *       that is being settled joins it and gets its answer — it never starts a second settle; a
 *       repeat of the same (request, fold) is exactly what qits-projects' retry is. An ask for a
 *       NEWER fold of the same request waits, within its budget, for the older settle to end and
 *       then starts its own; it never runs beside it. A finished settle leaves the table at once, so
 *       the next ask runs {@link AutomationService#trigger} again — which is cheap, answered from
 *       the rows the first one stored — rather than being served a stale answer for ever.
 *   <li><b>Isolation.</b> Settles run on a small pool of their own ({@value #THREADS}, default
 *       {@value #DEFAULT_THREADS} named daemon threads), never on the HTTP worker and never on the one
 *       {@code WorkQueue} thread every scan and dispatch shares. Because of the first rule a slow
 *       request holds at most one of those threads, so the others keep settling everybody else. A
 *       full pool queues a bounded number of settles ({@value #QUEUE}) and refuses the rest, which
 *       are answered as still settling and asked again.
 *   <li><b>A bounded answer.</b> The door waits at most {@value #BUDGET} (default {@value
 *       #DEFAULT_BUDGET}, comfortably under qits-projects' ten seconds). A settle not done by then
 *       keeps running and stores its rows; the door answers at once with what is already stored for
 *       the fold and every other kind UNKNOWN, "{@value #SETTLING}" ({@link
 *       AutomationService#settling}). UNKNOWN is the word every caller already holds the request on
 *       and asks again about (see {@link ReleaseRequestAutomationsDto}), which is why qits-projects
 *       needed no change: its next ask joins the settle or finds it finished.
 * </ol>
 *
 * <p><b>A refusal still reaches the caller.</b> The cheap checks — a request id, a sha, a work item,
 * a repository — are made before anything is handed to the pool, and a settle that fails inside the
 * budget rethrows its own exception: a 400 is a 400 and an unscanned repository's 404 is a 404, as
 * they were when the door settled inline.
 *
 * <p><b>Nothing needs the HTTP request on the pool thread.</b> {@link AutomationService#trigger}
 * asks no identity, every store method opens its own transaction and request context, and the peer
 * client mints its own bearer — the same reasons the {@code WorkQueue} thread can dispatch.
 */
@ApplicationScoped
public class SettleGate {

  private static final Logger LOG = Logger.getLogger(SettleGate.class);

  /** How long the door waits for a settle before answering what is stored so far. */
  public static final String BUDGET = "qits.maintenance.automations.settle.budget";

  /** {@link #BUDGET}'s default: half of qits-projects' ten-second timeout. */
  public static final String DEFAULT_BUDGET = "5s";

  /** How many settles may run at once — each of a different request. */
  public static final String THREADS = "qits.maintenance.automations.settle.threads";

  /** {@link #THREADS}' default. */
  public static final String DEFAULT_THREADS = "4";

  /** How many settles may wait for a thread before an ask is answered as still settling. */
  public static final int QUEUE = 64;

  /** What a kind not decided yet says while its fold's settle is still running. */
  public static final String SETTLING =
      "settling: the automations of this fold are still being decided; ask again";

  /** What runs one settle: {@link AutomationService#trigger}. */
  @FunctionalInterface
  public interface Settler {
    ReleaseRequestAutomationsDto settle(String requestId, AutomationService.Fold fold);
  }

  /** What answers while one runs: {@link AutomationService#settling}. */
  @FunctionalInterface
  public interface Interim {
    ReleaseRequestAutomationsDto answer(String requestId, String foldSha, String detail);
  }

  /** One request's settle in progress: the fold it is for, and its outcome once there is one. */
  private record Flight(String foldSha, CompletableFuture<ReleaseRequestAutomationsDto> outcome) {}

  @Inject AutomationService automations;

  @ConfigProperty(name = BUDGET, defaultValue = DEFAULT_BUDGET)
  Duration budget;

  @ConfigProperty(name = THREADS, defaultValue = DEFAULT_THREADS)
  int threads;

  private Settler settler;

  private Interim interim;

  private ThreadPoolExecutor pool;

  /** The settle running for each request, by request id: the single-flight table. */
  private final ConcurrentHashMap<String, Flight> flights = new ConcurrentHashMap<>();

  /** For CDI. */
  SettleGate() {}

  /** A gate around any settle — how a test holds one in flight without a peer or a database. */
  public SettleGate(Settler settler, Interim interim, Duration budget, int threads) {
    this.settler = settler;
    this.interim = interim;
    this.budget = budget;
    this.threads = threads;
    this.pool = pool(threads);
  }

  @PostConstruct
  void init() {
    settler = automations::trigger;
    interim = automations::settling;
    pool = pool(threads);
  }

  private static ThreadPoolExecutor pool(int threads) {
    int size = Math.max(1, threads);
    AtomicInteger counter = new AtomicInteger();
    return new ThreadPoolExecutor(
        size,
        size,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUE),
        runnable -> {
          // Named, so a settle that hangs is identifiable in a thread dump; a daemon, so a peer that
          // never answers does not hold a JVM that is shutting down.
          Thread thread = new Thread(runnable, "qits-maintenance-settle-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  /** The budget the door waits for a settle. */
  public Duration budget() {
    return budget;
  }

  /**
   * Settles one fold of one request, or answers within the budget that it is still being settled.
   *
   * @throws eu.wohlben.qits.maintenance.error.BadRequestException a malformed ask, before anything
   *     runs
   * @throws RuntimeException whatever the settle threw, when it ended inside the budget
   */
  public ReleaseRequestAutomationsDto settle(String requestId, AutomationService.Fold fold) {
    String foldSha = AutomationService.validate(requestId, fold);
    long deadline = System.nanoTime() + budget.toNanos();
    while (true) {
      Flight flight = flights.get(requestId);
      if (flight == null) {
        Flight mine = new Flight(foldSha, new CompletableFuture<>());
        flight = flights.putIfAbsent(requestId, mine);
        if (flight == null) {
          flight = mine;
          if (!launch(requestId, fold, mine)) {
            return interim.answer(requestId, foldSha, SETTLING);
          }
        }
      }
      long left = deadline - System.nanoTime();
      if (flight.foldSha().equals(foldSha)) {
        return await(flight, left, requestId, foldSha);
      }
      // ANOTHER FOLD OF THIS REQUEST is being settled. Its answer is not this one's, and running
      // beside it is what rule one forbids: wait for it to end, then try again for our own fold.
      if (left <= 0 || !outlast(flight, left)) {
        return interim.answer(requestId, foldSha, SETTLING);
      }
    }
  }

  /** Hands one settle to the pool; false when the pool is full (or shut down) and it did not go. */
  private boolean launch(String requestId, AutomationService.Fold fold, Flight flight) {
    try {
      pool.execute(() -> run(requestId, fold, flight));
      return true;
    } catch (RejectedExecutionException e) {
      flights.remove(requestId, flight);
      flight.outcome().cancel(false);
      LOG.warnf(
          "The settle of release request %s at %s was not started: %d settles are queued already",
          requestId, flight.foldSha(), QUEUE);
      return false;
    }
  }

  private void run(String requestId, AutomationService.Fold fold, Flight flight) {
    ReleaseRequestAutomationsDto answer = null;
    Throwable failure = null;
    try {
      answer = settler.settle(requestId, fold);
    } catch (Throwable thrown) {
      failure = thrown;
    }
    // OUT OF THE TABLE BEFORE THE OUTCOME IS PUBLISHED: an ask that sees the outcome and asks
    // again must start a fresh settle rather than find this finished one still holding the slot.
    flights.remove(requestId, flight);
    if (failure == null) {
      flight.outcome().complete(answer);
      return;
    }
    if (failure instanceof MaintenanceException) {
      // A refusal (400, 404, 409): the caller is told when it is still waiting; nothing broke here.
      LOG.debugf("The settle of release request %s at %s was refused: %s", requestId,
          flight.foldSha(), failure.getMessage());
    } else {
      LOG.warnf(failure, "The settle of release request %s at %s failed", requestId,
          flight.foldSha());
    }
    flight.outcome().completeExceptionally(failure);
  }

  /** The flight's answer, its own exception, or — past the budget — what is stored so far. */
  private ReleaseRequestAutomationsDto await(
      Flight flight, long nanos, String requestId, String foldSha) {
    try {
      return flight.outcome().get(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      return interim.answer(requestId, foldSha, SETTLING);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return interim.answer(requestId, foldSha, SETTLING);
    } catch (java.util.concurrent.CancellationException e) {
      return interim.answer(requestId, foldSha, SETTLING);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(cause);
    }
  }

  /** Waits for another fold's settle to end, however it ends; false when the time ran out first. */
  private static boolean outlast(Flight flight, long nanos) {
    try {
      flight.outcome().get(nanos, TimeUnit.NANOSECONDS);
      return true;
    } catch (ExecutionException | java.util.concurrent.CancellationException e) {
      return true;
    } catch (TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  void onShutdown(@Observes ShutdownEvent event) {
    shutdown();
  }

  /** Stops taking settles; one in flight is given a moment to store its rows, then interrupted. */
  public void shutdown() {
    if (pool == null) {
      return;
    }
    pool.shutdown();
    try {
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pool.shutdownNow();
    }
  }
}

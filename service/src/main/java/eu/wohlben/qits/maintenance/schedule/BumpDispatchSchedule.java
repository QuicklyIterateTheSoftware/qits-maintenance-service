package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * What opens the owed repositories' main-only release requests, as many per tick as qits-ci has
 * free slots.
 *
 * <p><b>The tick is short and the decision is small</b>: ask whether anything is owed and how many
 * of qits-ci's slots are free and, if any are, open that many of the deepest owed repositories'
 * requests; what the tick returns — the requests it opened — is for the tests. Everything about why
 * — the gates, the holds, the ordering, the cycle rule — is {@link BumpDispatcher}'s; what lives here
 * is the clock and the one guarantee a schedule owes: a failure is a line in the log and a retry
 * fifteen seconds later, never a dead scheduler thread.
 *
 * <p><b>It is the only thing that arms a dispatch</b>, and debt is what it answers to: there is no
 * cron and no window (both retired with group bumps, qits-1133 R5). On 2026-09-11 fifteen
 * repositories sat owed from 10:44 to 17:20 with an empty CI queue because only a 02:00 cron could
 * open the window; nothing waits for an hour now except a configured quiet one.
 *
 * <p><b>It shares {@code bump.poll-interval} with {@link BumpPollSchedule} on purpose</b>: far below
 * the length of any pipeline and far above the cost of the read. Its own timer rather than a line in
 * the poll sweep, because the sweep moves work already started and this decides whether to start
 * any, and {@code SKIP} on one must not delay the other.
 */
@ApplicationScoped
public class BumpDispatchSchedule {

  private static final Logger LOG = Logger.getLogger(BumpDispatchSchedule.class);

  @Inject BumpDispatcher dispatcher;

  @Scheduled(
      every = "{qits.maintenance.bump.poll-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onSchedule() {
    try {
      dispatcher.tick();
    } catch (RuntimeException e) {
      LOG.errorf(e, "The bump dispatch tick failed; the next one retries.");
    }
  }
}

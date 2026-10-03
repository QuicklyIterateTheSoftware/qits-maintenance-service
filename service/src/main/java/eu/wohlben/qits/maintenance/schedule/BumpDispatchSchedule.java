package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * What hands the owed bumps out, as many per tick as qits-ci has free slots.
 *
 * <p><b>The tick is short and the decision is small</b>: ask whether anything is owed and how many
 * of qits-ci's slots are free and, if any are, send that many of the deepest owed bumps; what the
 * tick returns — the bumps it asked for — is for the tests. Everything about why — the gates, the
 * ordering, the cycle rule, the window it opens for itself — is {@link BumpDispatcher}'s; what lives
 * here is the clock and the one guarantee a schedule owes: a failure is a line in the log and a
 * retry fifteen seconds later, never a dead scheduler thread.
 *
 * <p><b>THIS IS NOW THE ONLY THING THAT ARMS A DISPATCH.</b> It used to wait for {@link
 * BumpSchedule} to open a window at 02:00, which meant the primary upgrade path — something becomes
 * owed, qits-ci goes idle, the bump goes — could not happen at any other hour: on 2026-09-11
 * fifteen repositories sat owed from 10:44 to 17:20 with an empty CI queue in front of them. The
 * dispatcher opens the window itself now, and this tick is what asks it to.
 *
 * <p><b>It shares {@code bump.poll-interval} with {@link BumpPollSchedule} on purpose.</b> That
 * interval is already sized as "far below the length of any pipeline and far above the cost of the
 * read", which is exactly what this needs: the gate is one GET of qits-ci's queue snapshot, and
 * the thing it is waiting for is a build finishing. A second knob would be a second number to keep
 * consistent with the first for no gain.
 *
 * <p><b>Its own timer rather than a line inside the poll sweep</b>, because the two are different
 * jobs with different failure modes — the sweep moves work this service already started, and this
 * decides whether to start any — and {@code SKIP} on one must not delay the other.
 *
 * <p><b>What it costs when nothing is owed is one inventory walk.</b> The dispatcher used to answer
 * on a null window before reading anything, which is precisely how it never noticed the work; the
 * walk it does instead is the same three-table read {@code PendingChanges} does on every page load,
 * and a single needless CI run costs more than a day of it.
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

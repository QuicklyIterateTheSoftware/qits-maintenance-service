package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.bump.BumpService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * What moves an unfinished automation run along.
 *
 * <p><b>Two jobs, one sweep.</b> A row qits-ci answered 503 to is REQUESTED with its changes intact
 * and is SENT AGAIN — under the same event id, so a dispatch that did reach qits-ci records no second
 * run. A row that is RUNNING has its ci run read, and a terminal run ends it. (A third job, asking
 * qits-projects again for a group bump's release, went with group bumps in qits-1133 R5.)
 *
 * <p><b>The polling is not done here.</b> This only queues work; the reads and the writes happen on
 * the single worker thread, so a poll never interleaves with a scan rewriting the same rows.
 *
 * <p><b>It is also the restart recovery.</b> A row left RUNNING by a process that died is picked up
 * by the first sweep after the successor boots — the CI run outlived this service and its answer is
 * still there to read.
 */
@ApplicationScoped
public class BumpPollSchedule {

  private static final Logger LOG = Logger.getLogger(BumpPollSchedule.class);

  @Inject BumpService bumps;

  @Scheduled(
      every = "{qits.maintenance.bump.poll-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onSchedule() {
    try {
      bumps.sweep();
    } catch (RuntimeException e) {
      LOG.errorf(e, "The bump sweep failed; the next one retries.");
    }
  }
}

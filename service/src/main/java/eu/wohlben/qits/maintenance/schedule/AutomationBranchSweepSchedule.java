package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.automation.AutomationBranchSweep;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The clock behind {@link AutomationBranchSweep}: a few minutes after boot, then hourly.
 *
 * <p><b>Code constants, not keys.</b> Nothing about when a closed request's branch goes is a
 * deployment's decision — an hour is the latency nobody waits on, and the boot pass is what clears
 * whatever accumulated while the service was down.
 *
 * <p><b>On the worker, not on the scheduler thread</b>: the sweep reads the automation rows a
 * dispatch writes and talks to the git host, and both belong behind the one thread (see "The
 * worker"). A task never throws out of the queue, so a failed pass is a log line and the next hour
 * retries it.
 */
@ApplicationScoped
public class AutomationBranchSweepSchedule {

  @Inject AutomationBranchSweep sweep;

  @Inject WorkQueue queue;

  @Scheduled(
      every = "1h",
      delayed = "5m",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onSchedule() {
    queue.submit("sweep the closed requests' automation branches", sweep::sweep);
  }
}

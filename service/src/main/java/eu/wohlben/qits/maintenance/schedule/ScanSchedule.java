package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The clock's reading half: the two scans. What is owed a bump is {@link BumpDispatchSchedule}'s,
 * on a timer of its own.
 *
 * <p><b>Two crons, one per scope, and they are far apart on purpose.</b> An internal release lands
 * many times a day and is one hop away, so six hours is a cheap question. An external index is
 * somebody else's and moves on nobody's schedule, so once a day is enough.
 *
 * <p><b>Both re-read every manifest.</b> The scope governs the registry half only — the git-host
 * half is what keeps the inventory from reporting changes against pins somebody removed yesterday.
 *
 * <p><b>{@code enabled} is whether the clock may scan at all</b>, and that is the whole of what it
 * decides. A scan asks for no bumps at all any more, whoever triggered it; what the clock does about
 * what a scan found is the dispatcher's ({@link BumpDispatchSchedule}), gated by its own keys.
 *
 * <p><b>{@code SKIP} on a concurrent execution</b>, and the work queue behind it. A scan that
 * outlives its own schedule is never joined by a second one — and even if SKIP let one through, the
 * single worker thread would run them one after the other rather than at once.
 */
@ApplicationScoped
public class ScanSchedule {

  private static final Logger LOG = Logger.getLogger(ScanSchedule.class);

  @Inject MaintenanceConfig config;

  @Inject ScanService scans;

  @Scheduled(
      cron = "{qits.maintenance.scan.internal.cron}",
      timeZone = "{qits.maintenance.time-zone}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInternalSchedule() {
    start(ScanScope.INTERNAL);
  }

  @Scheduled(
      cron = "{qits.maintenance.scan.external.cron}",
      timeZone = "{qits.maintenance.time-zone}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onExternalSchedule() {
    start(ScanScope.EXTERNAL);
  }

  private void start(ScanScope scope) {
    if (!config.scanEnabled()) {
      LOG.infof(
          "Scanning is disabled (qits.maintenance.scan.enabled=false); the %s scan is skipped.",
          scope);
      return;
    }
    try {
      UUID id = scans.request(scope, null, ScanTrigger.SCHEDULED);
      LOG.infof("Queued the scheduled %s scan %s.", scope, id);
    } catch (RuntimeException e) {
      // A background chore's failure is a line in the log and a retry on the next schedule, never a
      // dead scheduler thread or a service that stops answering.
      LOG.errorf(e, "The scheduled %s scan could not be queued; the next schedule retries.", scope);
    }
  }
}

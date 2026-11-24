package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.sbom.SbomIngestService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The belt behind the SBOM ingest: every artifact row still waiting for its document, re-queued.
 *
 * <p><b>Hourly rather than on the bump poll, and that is the difference between the two shapes of
 * work.</b> The bump sweep runs every fifteen seconds because a bump is a live CI run whose ending
 * somebody is watching. A PENDING artifact row is not live at all — it is a fetch that was queued
 * and lost, by a restart or a shutdown mid-work — and the ordinary recovery for that is {@code
 * RestartRecovery}, which runs it at boot. What is left for this timer is the narrow case a boot
 * does not cover: a queue submission dropped while the process kept running.
 *
 * <p><b>It is not a retry of MISSING or FAILED.</b> Neither is swept, deliberately: a 404 is the
 * ordinary answer for a release published before the SBOM route existed, and a released version is
 * immutable, so asking every hour asks about the same bytes. Two other things move them: the daily
 * SBOM check re-reads every MISSING or FAILED row whose version qits-artifacts now holds
 * ({@code SbomCheckService}, qits-739), and {@code POST /artifacts/ingest} re-reads one now, for a
 * person who knows something changed and will not wait for the night.
 *
 * <p><b>A no-op whenever nothing is pending</b>, which is nearly always: it is one indexed read of
 * {@code mt_artifact} by status and a return.
 */
@ApplicationScoped
public class SbomSweepSchedule {

  private static final Logger LOG = Logger.getLogger(SbomSweepSchedule.class);

  @Inject SbomIngestService sboms;

  @Scheduled(
      cron = "{qits.maintenance.sbom.sweep-cron}",
      timeZone = "{qits.maintenance.time-zone}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onSchedule() {
    try {
      sboms.sweep();
    } catch (RuntimeException e) {
      // A background chore's failure is a line in the log and a retry on the next hour, never a
      // dead scheduler thread.
      LOG.errorf(e, "The sbom sweep failed; the next one retries.");
    }
  }
}

package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.sbomcheck.SbomCheckService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * The daily SBOM check (qits-621 / qits-668): every released software artifact still in
 * qits-artifacts with no usable bill of materials, reported — and ticketed once {@code
 * qits.maintenance.sbom.check.file-tickets} is on, which it ships off. See {@link
 * SbomCheckService} for what counts and how a ticket is filed and closed.
 *
 * <p>{@code SKIP} on a run still going, as every schedule here; and {@link SbomCheckService#run}
 * holds its own lock, so the clock and {@code POST /sbom-check/runs} never file one group twice.
 *
 * <p><b>A failed run is an ERROR line and the previous report stands.</b> The one failure that
 * reaches here on purpose is a presence probe qits-artifacts could not answer: the run refuses to
 * guess, stores nothing, and tomorrow's run asks again.
 */
@ApplicationScoped
public class SbomCheckSchedule {

  private static final Logger LOG = Logger.getLogger(SbomCheckSchedule.class);

  @Inject SbomCheckService check;

  @Scheduled(
      cron = "{qits.maintenance.sbom.check.cron}",
      timeZone = "{qits.maintenance.time-zone}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onSchedule() {
    try {
      check.run(Instant.now());
    } catch (RuntimeException e) {
      LOG.errorf(e, "The daily sbom check failed; the previous report stands until the next one.");
    }
  }
}

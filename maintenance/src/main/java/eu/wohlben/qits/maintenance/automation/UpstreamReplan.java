package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>The one "latest moved" hook</b> (qits-1133): where {@code mt_latest} advances — a scan's poll,
 * a {@code SoftwareRelease}, an {@code SCMRelease} — every consumer of that dependency with an OPEN
 * release request has its {@code dependency-bump} re-planned on the request's current fold, so the
 * upgrade lands in the release that is already on its way instead of on a branch of its own.
 *
 * <p><b>Behind {@code qits.maintenance.pre-run.upstream.enabled}, which ships false.</b> Off, this is
 * a no-op and nothing about the bump path moves.
 *
 * <h2>What it does per consumer</h2>
 *
 * <ul>
 *   <li><b>An open request that is not READY</b> — PENDING, REJECTED, FAILED, CONFLICTED: {@link
 *       AutomationService#replan}. A FRESH plan opens nothing; a RUN plan's commit joins the request,
 *       re-folds it and (over there) cancels a QA run that was going. A READY request is not touched
 *       (Q2): it has its verdict and a person's push still re-folds it as before.
 *   <li><b>The starvation guard.</b> A busy upstream could restart one request's pre-run for ever, so
 *       after {@value #STARVATION_RESTARTS} upstream restarts without a QA verdict — its {@code CI}
 *       gate PASSED or FAILED at the current fold, or the request no longer PENDING — it is left
 *       alone until it has one. A verdict resets the count.
 *   <li><b>No open request</b>: nothing here. The dispatcher's next tick finds the repository owed
 *       and, with the switch on, opens a main-only LOWEST request whose pre-run writes the bump —
 *       behind the same capacity gate and chain order every bump has always waited behind.
 * </ul>
 *
 * <p><b>Never inside the caller's transaction.</b> Two of the three callers are bus listeners whose
 * claim transaction must not hold an HTTP call open; the hook submits to {@link WorkQueue} and
 * returns, which is the outbox rule {@code SoftwareReleaseListener} already keeps.
 */
@ApplicationScoped
public class UpstreamReplan {

  private static final Logger LOG = Logger.getLogger(UpstreamReplan.class);

  /** Upstream restarts a request may have without a QA verdict before it is left alone. */
  public static final int STARVATION_RESTARTS = 3;

  @Inject MaintenanceConfig config;

  @Inject MaintenanceStore store;

  @Inject ReleaseRequestClient releases;

  @Inject AutomationService automations;

  @Inject WorkQueue queue;

  /** {@code mt_latest} advanced for one dependency. A no-op while the switch is off. */
  public void latestMoved(Ecosystem ecosystem, String name) {
    if (!config.preRunUpstreamEnabled() || ecosystem == null || name == null) {
      return;
    }
    queue.submit(
        "re-plan the consumers of " + ecosystem.wireName() + " " + name,
        () -> replanConsumers(ecosystem, name));
  }

  /** Every consumer of one dependency, each open request of each re-planned. */
  public void replanConsumers(Ecosystem ecosystem, String name) {
    if (!config.preRunUpstreamEnabled()) {
      return;
    }
    Set<String> consumers = new TreeSet<>();
    for (MtPin pin : store.allPins()) {
      if (ecosystem.wireName().equals(pin.ecosystem)
          && name.equals(pin.name)
          && PendingChanges.kindOf(pin).actionable()) {
        consumers.add(pin.repository);
      }
    }
    for (String consumer : consumers) {
      try {
        replan(consumer);
      } catch (RuntimeException e) {
        // One consumer's failure costs that consumer, never the rest of the list.
        LOG.warnf("The upstream re-plan of %s for %s %s failed: %s", consumer,
            ecosystem.wireName(), name, e.toString());
      }
    }
  }

  /** One consumer: re-plan each open, not-READY request, minding the starvation guard. */
  void replan(String repository) {
    Optional<MtRepository> row = store.repository(repository);
    if (row.isEmpty() || row.get().catalogId == null || row.get().catalogId.isBlank()) {
      return;
    }
    ReleaseRequestClient.Listing listing = releases.openRequests(row.get().catalogId);
    if (!listing.readable()) {
      LOG.warnf("The open release requests of %s could not be read: %s", repository,
          listing.error());
      return;
    }
    List<ReleaseRequestClient.Listed> open = listing.requests();
    if (open.isEmpty()) {
      LOG.debugf("%s has no open release request; the dispatcher opens one when it is owed",
          repository);
      return;
    }
    for (ReleaseRequestClient.Listed request : open) {
      if ("READY".equals(request.state())) {
        LOG.debugf("The release request %s of %s is READY and is not re-planned by an upstream",
            request.id(), repository);
        continue;
      }
      Instant now = Instant.now();
      if (request.verdict()) {
        store.resetUpstreamRestarts(request.id(), now);
      }
      int restarts = store.upstreamRestarts(request.id());
      if (restarts >= STARVATION_RESTARTS) {
        LOG.infof(
            "The release request %s of %s was restarted %d times by upstream releases without a QA"
                + " verdict; it is left alone until it has one",
            request.id(), repository, restarts);
        continue;
      }
      try {
        UUID id =
            automations.replan(
                repository, request.id(), DependencyBumpAutomation.KIND, BumpTrigger.UPSTREAM);
        if (id != null) {
          store.recordUpstreamRestart(request.id(), repository, now);
          LOG.infof("An upstream release re-planned the dependency bump of %s for %s as %s",
              repository, request.id(), id);
        }
      } catch (RuntimeException e) {
        // A 409 (one already active, the request settled) or an undecidable plan: the next upstream
        // release asks again, and the request's own fold trigger is what settles it meanwhile.
        LOG.infof("The upstream re-plan of %s for %s was not opened: %s", repository,
            request.id(), e.getMessage());
      }
    }
  }
}

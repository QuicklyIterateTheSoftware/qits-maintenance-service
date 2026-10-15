package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
 * <p><b>Always on since R5</b>, which removed {@code qits.maintenance.pre-run.upstream.enabled}
 * with the group path it used to fall back to. The one switch left is the {@code dependency-bump}
 * kind's own ({@value DependencyBumpAutomation#SWITCH}): off, there is nothing to re-plan and this
 * is a no-op.
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
 *       gate PASSED or FAILED at the current fold, or the request past both PENDING and REJECTED —
 *       it is left alone until it has one. A plain REJECTED is not enough (qits-1199): qits-projects
 *       also lands a request there when a pre-run automation fails, before any QA ran. A verdict
 *       resets the count.
 *   <li><b>No open request</b>: nothing here. The dispatcher's next tick finds the repository owed
 *       and opens a main-only LOWEST request whose pre-run writes the bump —
 *       behind the same capacity gate and chain order every bump has always waited behind.
 * </ul>
 *
 * <h2>And the estate (qits-1224)</h2>
 *
 * <p>A wrapper holds no INTERNAL pin — its gitlinks are {@code estate-pins}', never the bump's —
 * so the hook above never reaches it, and its {@code estate-pins} verdict is stored per fold: a
 * request folded before a member released is FRESH at that fold for ever. {@link #released} closes
 * that: a release recorded in the ledger re-plans {@code estate-pins} on every open, not-READY
 * request of every wrapper of the member's project, through the same door, guard and restart
 * count. The plan itself reads the request's FOLD — its {@code .gitmodules} and one recursive tree
 * listing (qits-1258) — and answers FRESH where the member is not declared or its pin is current, so
 * "of the member's project" is the membership rule the plan already applies ({@code
 * EstatePinsAutomation.newestRelease}), and nothing finer is guessed at from main's gitlinks — a
 * request's fold may declare a submodule main does not. A RUN plan is one commit on the kind's own
 * branch, joined like any other automation's.
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

  @Inject DependencyBumpAutomation dependencyBump;

  @Inject EstatePinsAutomation estatePins;

  @Inject MaintenanceStore store;

  @Inject ReleaseRequestClient releases;

  @Inject AutomationService automations;

  @Inject WorkQueue queue;

  /** {@code mt_latest} advanced for one dependency. A no-op while dependency-bump is off. */
  public void latestMoved(Ecosystem ecosystem, String name) {
    if (!dependencyBump.enabled() || ecosystem == null || name == null) {
      return;
    }
    queue.submit(
        "re-plan the consumers of " + ecosystem.wireName() + " " + name,
        () -> replanConsumers(ecosystem, name));
  }

  /**
   * Every consumer of one dependency, each open request of each re-planned. Only an INTERNAL pin
   * makes a consumer: the bump never moves an EXTERNAL one (qits-1164), so its latest moving can
   * change no plan.
   */
  public void replanConsumers(Ecosystem ecosystem, String name) {
    if (!dependencyBump.enabled()) {
      return;
    }
    Set<String> consumers = new TreeSet<>();
    for (MtPin pin : store.allPins()) {
      if (ecosystem.wireName().equals(pin.ecosystem)
          && name.equals(pin.name)
          && PendingChanges.kindOf(pin) == PinKind.INTERNAL) {
        consumers.add(pin.repository);
      }
    }
    for (String consumer : consumers) {
      try {
        replan(consumer, DependencyBumpAutomation.KIND);
      } catch (RuntimeException e) {
        // One consumer's failure costs that consumer, never the rest of the list.
        LOG.warnf("The upstream re-plan of %s for %s %s failed: %s", consumer,
            ecosystem.wireName(), name, e.toString());
      }
    }
  }

  /**
   * A release of one repository was recorded in the release ledger (qits-1224). A no-op while
   * estate-pins is off.
   *
   * <p><b>After the ledger write, and not off {@link #latestMoved}</b>: the estate plan reads a
   * member's newest release from {@code mt_release}, so a re-plan queued before that row is written
   * could plan against the previous release and answer FRESH. And it fires on a durable redelivery
   * too, where the latest column has already moved and {@code latestMoved} is not called again.
   */
  public void released(String repository) {
    if (!estatePins.enabled() || repository == null) {
      return;
    }
    queue.submit(
        "re-plan the estate pins over " + repository, () -> replanWrappers(repository));
  }

  /**
   * Every wrapper of the released repository's project, each open request of each re-planned for
   * its estate pins. The repository itself is never its own member.
   */
  public void replanWrappers(String repository) {
    if (!estatePins.enabled()) {
      return;
    }
    Optional<MtRepository> member = store.repository(repository);
    if (member.isEmpty() || member.get().project == null) {
      return;
    }
    Set<String> wrappers = new TreeSet<>();
    for (MtRepository row : store.repositories()) {
      if (!repository.equals(row.name)
          && Objects.equals(member.get().project, row.project)
          && RepositoryArchetype.of(row.archetype)
              .filter(archetype -> archetype == RepositoryArchetype.PROJECT)
              .isPresent()) {
        wrappers.add(row.name);
      }
    }
    for (String wrapper : wrappers) {
      try {
        replan(wrapper, EstatePinsAutomation.KIND);
      } catch (RuntimeException e) {
        // One wrapper's failure costs that wrapper, never the rest of the list.
        LOG.warnf("The estate re-plan of %s for a release of %s failed: %s", wrapper, repository,
            e.toString());
      }
    }
  }

  /** One repository: re-plan one kind on each open, not-READY request, minding the guard. */
  void replan(String repository, String kind) {
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
                repository, request.id(), kind, BumpTrigger.UPSTREAM);
        if (id != null) {
          store.recordUpstreamRestart(request.id(), repository, now);
          LOG.infof("An upstream release re-planned the %s of %s for %s as %s", kind,
              repository, request.id(), id);
        }
      } catch (RuntimeException e) {
        // A 409 (one already active, the request settled) or an undecidable plan: the next upstream
        // release asks again, and the request's own fold trigger is what settles it meanwhile.
        LOG.infof("The upstream re-plan of %s's %s for %s was not opened: %s", repository, kind,
            request.id(), e.getMessage());
      }
    }
  }
}

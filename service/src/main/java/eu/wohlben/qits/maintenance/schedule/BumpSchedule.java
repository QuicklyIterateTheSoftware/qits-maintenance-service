package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * The nightly internal bump: one branch per repository whose own releases have moved on.
 *
 * <h2>It no longer dispatches, and it no longer opens a window either</h2>
 *
 * <p>This cron used to walk the inventory and ask for every eligible bump in one tight loop. That
 * put the whole night's builds into qits-ci as one wavefront and it put a library's bump and its
 * consumer's in the same breath, so the consumer built against the pin it was about to be handed
 * anyway. Both were fixed by moving the DISPATCH somewhere else: the cron opened a window and
 * {@link BumpDispatchSchedule} handed out one bump at a time, from the bottom of the dependency
 * chain, while qits-ci was idle — and, since qits-882, as many per tick as qits-ci has free slots.
 *
 * <p><b>Which left the hour as the only thing that could arm any of it, and that was the next
 * defect.</b> {@link BumpDispatcher} now opens its own window the moment something dispatchable is
 * owed — see its javadoc for the day fifteen repositories waited for a timestamp — so in gated
 * mode this cron does nothing at all. It is kept because {@code bump.dispatch.gated=false} still
 * fires from here, and because a line at 02:00 is where somebody goes looking.
 *
 * <p><b>{@code qits.maintenance.bump.dispatch.gated=false} brings the old loop back</b>, unchanged,
 * in {@link #requestInternalBumps()}. It is kept reachable rather than deleted because how much a
 * qits-ci can take at once is a property of a deployment.
 *
 * <h2>It is its own cron, and that is the point of it existing at all</h2>
 *
 * <p>This used to be the tail of a SCHEDULED scan, gated by {@code qits.maintenance.bump.auto}. That
 * welded two decisions together. A scan is a READ — the catalog, every manifest, half the registries
 * — and its schedule is set by how fast those facts go stale; a bump is a WRITE into somebody else's
 * repository, and its schedule is set by when a branch is welcome to arrive. Coupled, the external
 * scan's 01:00 read decided when the internal half got a branch, and moving either cron silently
 * moved the other's meaning.
 *
 * <p><b>02:00, deliberately after both scans.</b> The internal scan runs at 00:30 and the external at
 * 01:00, so by 02:00 both halves of the inventory were written today and the pending set this reads
 * is the freshest one there is. It is also clear of qits-platform-orchestrator's 03:00 gc and
 * qits-platform-mirror's 03:20 eviction, so no two of the platform's nightly chores share a minute on
 * the same postgres.
 *
 * <h2>The INTERNAL half only</h2>
 *
 * <p>It asks for {@link GroupConfig#DEFAULT_GROUP} and nothing else. The two halves are reviewed by
 * different eyes: our own releases are a version bump somebody merges, and somebody else's framework
 * major is an opinion. {@code external} is manual-only for now —
 * {@code qits.maintenance.bump.external.auto} exists as the shape of the switch and is read only to
 * refuse, so a deployment that sets it gets a WARN rather than a variable that silently does nothing.
 *
 * <p>A repository whose own file declares a FINER grouping still has its {@code dependencies} group,
 * because {@code GroupConfig} appends the kind pair after whatever the file declares. What it does
 * not have is its configured groups bumped by the clock — those are a person's press. That is a
 * deliberate narrowing rather than an oversight: a repository that grouped {@code @angular/*} onto
 * its own branch asked for that branch to be a decision.
 *
 * <h2>Coalescing: why N releases are ONE branch, ONE build and ONE release</h2>
 *
 * <p><b>{@link BumpService#request} freezes ALL of the group's pending changes onto the row at
 * request time</b>, so however many internal releases landed between two nightly runs — five
 * libraries, or the same library five times — the next run composes exactly one payload, which is one
 * branch push, one CI build, one ReleaseRequest and one release. That is the whole of the storm fix:
 * the alternative, a bump per announced release, is what turns one library's release train into a
 * build per downstream repository per hop.
 *
 * <h2>The two skips, and neither is a failure</h2>
 *
 * <ul>
 *   <li><b>a repository whose status is not OK</b> — UNREACHABLE means the git host could not be
 *       read and the pins are yesterday's, CONFIG_ERROR means the repository's own grouping file did
 *       not parse and a payload composed against the fallback would land on a branch its author
 *       configured against, and <b>ABSENT means there is no such repository to bump</b> — either the
 *       git host does not hold it or the catalog stopped listing it. That last one is what the first
 *       live run of this schedule found out the hard way on 2026-09-03: 30 bumps asked for, 23
 *       FAILED with {@code no run recorded for MaintenanceBump} against pre-rename ghosts whose rows
 *       nothing had ever reconciled away. This filter was already right; what was wrong was that a
 *       ghost's status still said OK. The fix is {@code ScanService.reconcile}, and this line is
 *       what makes it enough;
 *   <li><b>a group with an active bump</b> — one branch, one writer. The store refuses it anyway,
 *       inside the transaction where the refusal belongs; the read here is what keeps the ordinary
 *       case out of the log as an exception.
 * </ul>
 *
 * <p><b>{@code SKIP} on a concurrent execution</b>, like every other timer here, and the single
 * worker thread behind it: a sweep that outlives its own schedule is never joined by a second one.
 */
@ApplicationScoped
public class BumpSchedule {

  private static final Logger LOG = Logger.getLogger(BumpSchedule.class);

  /** The reserved-key WARN is said ONCE per process, not once a night — it is a deployment mistake
   * to fix, not a condition to monitor, and a nightly line would be noise for ever. */
  private final AtomicBoolean warnedAboutExternal = new AtomicBoolean();

  @Inject MaintenanceConfig config;

  @Inject MaintenanceStore store;

  @Inject BumpService bumps;

  @Inject BumpDispatcher dispatcher;

  @Scheduled(
      cron = "{qits.maintenance.bump.internal.cron}",
      timeZone = "{qits.maintenance.time-zone}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInternalSchedule() {
    try {
      refuseExternalAuto();
      if (config.bumpDispatchGated()) {
        openTheDispatchWindow();
      } else {
        requestInternalBumps();
      }
    } catch (RuntimeException e) {
      // A background chore's failure is a line in the log and a retry on the next schedule, never a
      // dead scheduler thread or a service that stops answering.
      LOG.errorf(e, "The nightly internal bump could not be run; the next schedule retries.");
    }
  }

  /**
   * The gated night: <b>nothing at all, because the dispatcher arms itself on debt.</b>
   *
   * <p>This used to open the window, and that was the whole of the defect it is now free of: {@link
   * BumpDispatcher#open} had two callers — this hour and the by-hand door — so a release cut at
   * 10:44 waited until 02:00 with an idle qits-ci in front of it. The tick asks the debt question
   * every fifteen seconds and opens its own window when something dispatchable is owed, so an hour
   * that opened one as well could only mean one thing: a way around {@code
   * qits.maintenance.bump.dispatch.quiet-hours}, which is now where "not now" is said.
   *
   * <p><b>The cron is kept for the ungated mode</b> ({@code bump.dispatch.gated=false}), where it
   * still is the thing that fires, and as the line in the log at the hour somebody looks for it.
   */
  private void openTheDispatchWindow() {
    LOG.infof(
        "Bump dispatch is gated and arms itself on debt — nothing is opened by the clock. Quiet"
            + " hours are qits.maintenance.bump.dispatch.quiet-hours; POST /bumps/window is the"
            + " override.");
  }

  /** One bump per OK repository whose INTERNAL group has something pending and no writer. */
  private void requestInternalBumps() {
    if (!config.bumpEnabled()) {
      LOG.infof(
          "Bumping is disabled (qits.maintenance.bump.enabled=false); the nightly internal bump is"
              + " skipped.");
      return;
    }
    if (!config.bumpInternalAuto()) {
      LOG.infof(
          "The nightly internal bump is off (qits.maintenance.bump.internal.auto=false); the"
              + " buttons still work.");
      return;
    }
    String group = GroupConfig.DEFAULT_GROUP;
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    int asked = 0;
    for (MtRepository row : store.repositories()) {
      if (!RepositoryStatus.OK.name().equals(row.status)) {
        continue;
      }
      List<MtPin> pins = store.pins(row.name);
      List<MtGroup> groups = store.groups(row.name);
      if (groups.stream().noneMatch(candidate -> candidate.name.equals(group))) {
        // A repository whose own file took the name for itself, or one whose groups have not been
        // written yet. Either way there is nothing here the clock owns.
        continue;
      }
      int pending = PendingChanges.countByGroup(pins, latest, groups).getOrDefault(group, 0);
      if (pending == 0) {
        continue;
      }
      if (store.activeBump(row.name, group).isPresent()) {
        continue;
      }
      try {
        UUID id = bumps.request(row.name, group, BumpTrigger.SCHEDULED);
        asked++;
        LOG.infof(
            "Requested the scheduled bump %s of %s/%s (%d changes)", id, row.name, group, pending);
      } catch (RuntimeException e) {
        // A refusal here is a branch somebody else started writing between the read above and this
        // call, or a store that would not answer. Either way it costs one repository and the next
        // night asks again.
        LOG.warnf(
            "Could not request the scheduled bump of %s/%s: %s", row.name, group, e.getMessage());
      }
    }
    LOG.infof("The nightly internal bump asked for %d branch(es).", asked);
  }

  /** {@code bump.external.auto} is a reserved key: flipping it says so rather than doing nothing. */
  private void refuseExternalAuto() {
    if (config.bumpExternalAuto() && warnedAboutExternal.compareAndSet(false, true)) {
      LOG.warnf(
          "qits.maintenance.bump.external.auto is set, and automatic EXTERNAL bumping is not"
              + " implemented — the `%s` group is bumped by the button only. The key is reserved so"
              + " the deployment surface does not change the day it is.",
          GroupConfig.EXTERNAL_GROUP);
    }
  }
}

package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.automation.AutomationService;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.error.BumpDisabledException;
import eu.wohlben.qits.maintenance.error.NoSuchGroupException;
import eu.wohlben.qits.maintenance.error.NoSuchRepositoryException;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * A bump: what it asks qits-ci to change, and what it makes of the answer.
 *
 * <p><b>This service decides WHAT changes; a CI step applies them.</b> Nothing here clones,
 * rewrites a file or pushes a ref. The payload names a file, a location and two versions, and the
 * step that reads it is the only thing that touches the repository — which is what keeps this
 * service out of every repository's write path.
 *
 * <p><b>The branch head is read TWICE and that is the whole of "did it do anything".</b> Once
 * before the trigger, once when the run ends. A green run whose branch did not move means the step
 * found the versions already there: NOTHING_TO_DO, which is a real outcome and reads very
 * differently from SUCCEEDED in a list of nightly bumps.
 *
 * <p><b>Only the head is compared, never a commit count.</b> One bump is up to TWO commits — the
 * maven step and the node/docker step each clone, commit and push — so a bump that moved the branch
 * by two commits is the ordinary case and a service that expected one would report every mixed
 * group as broken.
 *
 * <p><b>The push is ff-only and never forced</b> — that rule lives in the pipeline, not here — so a
 * red run against a branch that MOVED is somebody's hand-written commit that this service refused
 * to overwrite. That is the STALE state: they own the branch now, and nothing bumps it again until
 * it is gone.
 *
 * <p><b>The one exception is a REBUILD, and it is leased</b> (qits-1081). When the repository's
 * newest release has not reached main, the branch is cut from that tag rather than from main — see
 * {@link BumpBase} — and a branch that exists without it is sent as {@code replaceHead}: the step
 * rebuilds it on the tag under {@code --force-with-lease} on exactly that head, so a branch that
 * moved after this service read it is still refused rather than overwritten. A STALE branch is
 * never offered for a rebuild.
 *
 * <p><b>A GREEN ENDING ASKS FOR THE RELEASE WHENEVER THE BRANCH IS AHEAD OF MAIN</b> — which is not
 * the same test as "did this run push", and used to be. A branch nobody asks about is a branch that
 * sits there, so the ending opens a release request in qits-projects; see {@link
 * ReleaseRequestClient} for what that ask is and why it is convergent.
 *
 * <p>This used to read "SUCCEEDED asks for the release itself, NOTHING_TO_DO does not: there is
 * nothing to release". The second half was a guess dressed as a fact. NOTHING_TO_DO says the head
 * did not move ACROSS THIS RUN, and a head read that raced the step's own push is enough to produce
 * it for a branch the step really did write — measured on qits-mirror-platform-service, 2026-09-16:
 * a run recorded NOTHING_TO_DO at 01:17:19 whose branch carried a commit authored 01:17:06, sitting
 * unreleased on an old qits-integrations while the dispatcher held the repository for a release
 * nobody had asked for. So the status still describes the run and the ask now follows the branch.
 *
 * <p><b>STALE still does not ask, and that has not moved.</b> Somebody owns the branch by hand and
 * asking for their commits to be released is precisely the thing that must not happen. Nor does a
 * branch that is level with main: there is genuinely nothing on it, which is the one case the old
 * wording always had right.
 *
 * <p><b>The ask is where this service's part ends.</b> It opens a request; the quality gates settle
 * it, Auto Release tags it and the merge back to main follows the deployment, all in qits-projects.
 * Nothing here waits for a version, polls the request or records a release.
 *
 * <h2>Every paragraph above is about a branch this service OWNS</h2>
 *
 * <p>{@code maintenance/<group>}, named from the group, created by the step, tracked by an {@code
 * mt_branch} row and deleted by the release that lands it. Nobody else commits to it, which is
 * exactly what licenses reading its head twice and calling the difference the run's work.
 *
 * <h2>The release-request automations are not read here at all</h2>
 *
 * <p><b>An AUTOMATION row is {@link AutomationService}'s</b> (qits-978): its dispatch, its caps and
 * its ending are written once there for every kind, and this class only routes — {@link #dispatch}
 * and the poll's ending hand such a row over, and the sweep reaches it like any other active bump.
 * What were the TARGETED and BASELINES modes are the {@code estate-pins} and {@code
 * screenshot-baselines} kinds — a write onto a branch the CALLER owns, judged by its CI run, and a
 * write onto a branch of the automation's own, head-compared and joined. The two doors that used to
 * delegate here, {@code POST /{name}/branches/bumps} and {@code POST
 * /{name}/release-requests/{requestId}/screenshot-baselines}, are retired (qits-1006); {@link
 * AutomationService#run} and the {@code POST /release-requests/{id}/automations/{kind}/runs} door
 * are the one address for a manual re-run now. Neither kind writes an {@code mt_branch} row or asks
 * for a release: the request the commit belongs to is already open.
 */
@ApplicationScoped
public class BumpService {

  private static final Logger LOG = Logger.getLogger(BumpService.class);

  /**
   * Every maintenance branch is under this prefix. It is also what the release deletes: a request's
   * named sources are dropped when it lands, and the {@code SCMDeleteBranch} that follows is what
   * puts the branch row back to NONE.
   */
  public static final String BRANCH_PREFIX = "maintenance/";

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject CiClient ci;

  @Inject GitHostReader gitHost;

  @Inject ReleaseRequestClient releases;

  /** What a group bump's branch is cut from, and whether it is rebuilt (qits-1081). */
  @Inject BumpBase bases;

  @Inject WorkQueue queue;

  /** The engine every release-request automation runs on; AUTOMATION rows are its to read. */
  @Inject AutomationService automations;

  /**
   * Opens a bump and queues its dispatch.
   *
   * @throws NoSuchRepositoryException the inventory has no such repository — a 404
   * @throws NoSuchGroupException that repository declares no such group — a 404
   * @throws eu.wohlben.qits.maintenance.error.BumpAlreadyActiveException one is going — a 409
   * @throws BumpDisabledException {@code qits.maintenance.bump.enabled} is false — a 409
   */
  public UUID request(String repository, String group, BumpTrigger trigger) {
    if (!config.bumpEnabled()) {
      throw new BumpDisabledException();
    }
    // Read for its refusal: a repository the inventory does not hold has no pins to bump and no
    // coordinate to name in a payload.
    store.repository(repository).orElseThrow(() -> new NoSuchRepositoryException(repository));
    List<MtGroup> groups = store.groups(repository);
    if (groups.stream().noneMatch(candidate -> candidate.name.equals(group))) {
      throw new NoSuchGroupException(repository, group);
    }
    List<Change> changes =
        PendingChanges.forGroup(
            store.pins(repository), PendingChanges.index(store.allLatest()), groups, group);

    // THE CHANGES ARE FROZEN AT REQUEST TIME. A payload recomputed at dispatch would not be the one
    // the operator saw when they pressed the button, and a retry after a 503 would send a different
    // list than the first attempt under the same dedupe key.
    UUID id =
        store.openBump(
            repository,
            group,
            BRANCH_PREFIX + group,
            config.environment(),
            trigger,
            changes,
            Instant.now());
    queue.submit("bump " + id + " of " + repository + "/" + group, () -> dispatch(id));
    LOG.infof(
        "Opened the %s bump %s of %s/%s with %d changes",
        trigger, id, repository, group, changes.size());
    return id;
  }

  /**
   * Sends one bump to qits-ci, or defers it.
   *
   * <p>Idempotent by design: a bump that is not REQUESTED any more has already been sent, and the
   * event id travelling with the payload means a second send that DID reach qits-ci records no
   * second run.
   */
  public void dispatch(UUID id) {
    Optional<MtBump> found = store.bump(id);
    if (found.isEmpty()) {
      return;
    }
    MtBump bump = found.get();
    if (!BumpStatus.REQUESTED.name().equals(bump.status)) {
      return;
    }
    BumpMode mode = BumpMode.of(bump.mode);
    if (mode == BumpMode.AUTOMATION) {
      // Its own engine: carry-over asked again, the per-branch and estate-wide caps, and a payload
      // shaped by the kind's target.
      automations.dispatch(bump);
      return;
    }
    List<Change> changes = changes(bump);
    if (changes.isEmpty()) {
      // NOTHING TO WRITE, AND NO RUN IS STARTED FOR IT: the pins moved between the request and
      // here. Not a failure.
      store.bumpFinished(
          id,
          BumpStatus.NOTHING_TO_DO,
          null,
          "nothing is pending in this group",
          Instant.now());
      return;
    }
    Optional<MtRepository> repository = store.repository(bump.repository);
    if (repository.isEmpty()) {
      store.bumpFinished(
          id, BumpStatus.FAILED, null, "the repository left the inventory", Instant.now());
      return;
    }
    String branch = bump.branch;
    // THE BASE IS CHOSEN AT DISPATCH, NOT FROZEN AT REQUEST (qits-1081). The changes are what the
    // operator saw and must not move; the base is a fact about the repository's releases right now,
    // and a retry after a 503 should cut from the tag that is unmerged THEN. Recorded before the
    // trigger, so a refused payload still says what it would have been cut from.
    BumpBase.Choice base =
        mode.ownsTheBranch()
            ? bases.choose(repository.get(), bump.groupName, branch)
            : BumpBase.Choice.main(repository.get());
    String baseRef = base.baseRef();
    store.bumpBase(id, baseRef, base.replaceHead());
    if (base.rebuild()) {
      LOG.infof(
          "The bump %s rebuilds %s of %s on %s, over %s, which does not carry that release",
          id, branch, bump.repository, baseRef, base.replaceHead());
    }

    // REFUSED HERE RATHER THAN OVER THERE. The step holds every one of these to the same rule, so
    // a payload that fails validation is a red run and a step log somebody has to read. Failing on
    // this side puts the reason on the bump row, written by the component that composed it.
    List<String> problems =
        BumpPayload.problems(bump.groupName, branch, baseRef, base.replaceHead(), changes);
    if (!problems.isEmpty()) {
      store.bumpFinished(
          id, BumpStatus.FAILED, null, String.join("; ", problems), Instant.now());
      LOG.warnf("The bump %s was not sent: %s", id, problems);
      return;
    }

    if (mode.ownsTheBranch()) {
      // The head BEFORE the run, which is what an unmoved branch is compared against afterwards.
      recordBranchHead(repository.get(), bump.groupName, branch);
    }

    CiClient.TriggerResult result =
        ci.trigger(
            id.toString(),
            bump.repository,
            bump.groupName,
            branch,
            baseRef,
            changes,
            // OMITTED, never sent empty, when the branch is continued: the step reads a missing
            // `replaceHead` as "continue", which is every bump before qits-1081.
            base.rebuild() ? Map.of("replaceHead", base.replaceHead()) : Map.of());
    switch (result.outcome()) {
      case ACCEPTED -> {
        store.bumpDispatched(id, result.eventId(), result.runIds());
        LOG.infof("qits-ci accepted the bump %s as run(s) %s", id, result.runIds());
      }
      case RETRY ->
          // Still REQUESTED, changes intact, same event id next time. The poller sends it again.
          store.bumpFinished(id, BumpStatus.REQUESTED, null, result.message(), Instant.now());
      case FAILED -> {
        store.bumpFinished(id, BumpStatus.FAILED, null, result.message(), Instant.now());
        LOG.warnf("The bump %s failed at the trigger: %s", id, result.message());
      }
    }
  }

  /**
   * How long a FAILED run is given for qits-ci's automatic retry of it to appear. qits-ci commits
   * the red row first and records the retry right after — a read of the git host apart — so a poll
   * can land in between. A run that ended longer ago than this with no retry was not retried.
   */
  static final Duration RETRY_GRACE = Duration.ofSeconds(60);

  /** Follows one running bump to its end, or leaves it running. */
  public void poll(UUID id) {
    Optional<MtBump> found = store.bump(id);
    if (found.isEmpty()) {
      return;
    }
    MtBump bump = found.get();
    if (!BumpStatus.RUNNING.name().equals(bump.status)) {
      return;
    }
    List<String> runIds = runIds(bump);
    if (runIds.isEmpty()) {
      store.bumpFinished(
          id, BumpStatus.FAILED, null, "the bump has no ci run to follow", Instant.now());
      return;
    }

    Map<String, CiClient.RunState> states = new LinkedHashMap<>();
    for (String runId : runIds) {
      CiClient.RunState state = ci.run(runId);
      if (state.status() == null) {
        // Unreadable is not terminal. The next poll asks again; a run that is really gone leaves a
        // bump RUNNING, which is an honest gap rather than a fabricated verdict.
        store.bumpRunStatus(id, null);
        LOG.debugf("The bump %s could not read run %s: %s", id, runId, state.error());
        return;
      }
      states.put(runId, state);
    }

    // AN ADOPTED RETRY IS NOT A RUN OF ITS OWN: it answers for the run it re-fires, and is reached
    // from there. Every other run is the head of a chain whose LAST run holds the verdict.
    boolean allPassed = true;
    String lastStatus = null;
    // Why it went red (qits-1116): of several runs, the first red one that says.
    CiClient.Failure failure = null;
    for (String runId : runIds) {
      CiClient.RunState state = states.get(runId);
      if (state.retryOfRunId() != null
          && state.autoRetry()
          && states.containsKey(state.retryOfRunId())) {
        continue;
      }
      CiClient.RunState verdict = followRetries(bump, runId, state, states);
      if (verdict == null) {
        return;
      }
      lastStatus = verdict.status();
      allPassed = allPassed && verdict.passed();
      if (failure == null && !verdict.passed()) {
        failure = verdict.failure();
      }
    }
    finish(bump, allPassed, lastStatus, allPassed ? null : failure);
  }

  /**
   * <b>A run qits-ci re-fired for an infra failure is answered by its retry</b> (qits-760). qits-ci
   * leaves the original row FAILED and records the retry as a NEW run carrying {@code
   * retryOfRunId}, so a bump that read only the ids its trigger was answered with would call a
   * runner disconnecting a red verdict while the retry went on to pass. Followed through every
   * automatic retry of a chain, each one adopted into the row's run ids the first time it is found,
   * so the next poll reads it directly.
   *
   * <p>The link is the retry's own {@code retryOfRunId} with {@code autoRetry}, read from the retry
   * the original's step output names or else from the repository's newest runs; a candidate whose
   * row does not carry it is not followed. The answer is the same whichever order the two runs are
   * seen to end in: a FAILED original whose retry is still going is RUNNING, and one whose retry
   * already ended takes that verdict.
   *
   * <p><b>A {@code TIMED_OUT} run takes the same look</b> (qits-760 follow-up). qits-ci never fires
   * an automatic retry of a deadline — {@code CiRunService#autoRetry}'s own javadoc lists a timeout
   * as outside its infra-failure set — so this always finds none and falls through to the run's own
   * verdict exactly as a FAILED run with no retry does. Checking anyway costs nothing and means this
   * does not have to be revisited the day qits-ci's answer changes.
   *
   * @return the terminal state that decides this chain, or null when the bump stays RUNNING — a run
   *     still going, a run or listing that could not be read, or a FAILED/TIMED_OUT run young enough
   *     that its retry may not have been recorded yet
   */
  private CiClient.RunState followRetries(
      MtBump bump, String runId, CiClient.RunState state, Map<String, CiClient.RunState> states) {
    String current = runId;
    CiClient.RunState at = state;
    Set<String> seen = new HashSet<>();
    while (seen.add(current)) {
      if (!at.terminal()) {
        store.bumpRunStatus(bump.id, at.status());
        return null;
      }
      if (!at.failed() && !at.timedOut()) {
        return at;
      }
      String retryId = null;
      CiClient.RunState retry = null;
      for (Map.Entry<String, CiClient.RunState> held : states.entrySet()) {
        if (held.getValue().automaticRetryOf(current)) {
          retryId = held.getKey();
          retry = held.getValue();
        }
      }
      if (retryId == null && at.retriedAs() != null && !seen.contains(at.retriedAs())) {
        CiClient.RunState named = ci.run(at.retriedAs());
        if (named.status() != null && named.automaticRetryOf(current)) {
          retryId = at.retriedAs();
          retry = named;
        }
      }
      if (retryId == null) {
        CiClient.RetryLookup lookup = ci.automaticRetryOf(at.repoId(), current);
        if (!lookup.readable()) {
          store.bumpRunStatus(bump.id, null);
          LOG.debugf("The bump %s could not look for a retry of run %s", bump.id, current);
          return null;
        }
        retryId = lookup.retryId();
        retry = lookup.retry();
      }
      if (retryId == null) {
        if (at.finishedAt() != null
            && Instant.now().isBefore(at.finishedAt().plus(RETRY_GRACE))) {
          store.bumpRunStatus(bump.id, at.status());
          return null;
        }
        return at;
      }
      if (!states.containsKey(retryId)) {
        store.bumpRunAdopted(bump.id, retryId);
        LOG.infof(
            "The bump %s follows run %s, qits-ci's automatic retry of run %s", bump.id, retryId,
            current);
        // Read again by id: the listing carries no step output, and the step output is where a
        // further retry of this one is named first.
        CiClient.RunState read = ci.run(retryId);
        if (read.status() == null) {
          store.bumpRunStatus(bump.id, null);
          return null;
        }
        retry = read;
        states.put(retryId, retry);
      }
      current = retryId;
      at = retry;
    }
    // A cycle along retryOfRunId cannot be recorded by qits-ci; reaching here means the rows say
    // something impossible, and the run last read is the most that can be said.
    return at;
  }

  /**
   * Three jobs: send every REQUESTED bump again, follow every RUNNING one, and ask qits-projects
   * again for every pushed branch whose release ask has not settled.
   */
  public void sweep() {
    for (MtBump bump : store.activeBumps()) {
      UUID id = bump.id;
      if (BumpStatus.REQUESTED.name().equals(bump.status)) {
        queue.submit("re-dispatch bump " + id, () -> dispatch(id));
      } else {
        queue.submit("poll bump " + id, () -> poll(id));
      }
    }
    for (MtBump bump : store.bumpsOwedARelease()) {
      UUID id = bump.id;
      queue.submit("ask for the release of bump " + id, () -> retryRelease(id));
    }
  }

  /**
   * One more attempt at the release ask, for a bump that pushed a branch and got no answer worth
   * keeping.
   *
   * <p><b>It is bounded by the BRANCH, not by a counter.</b> There is no attempt limit and no backoff
   * schedule, because the thing that ends the retrying is the thing the retrying is for: the branch
   * either gets a release request (the column fills) or vanishes (NONE, from {@code
   * SCMDeleteBranch} — which is also what a landed release leaves behind, since a request's named
   * sources are deleted when it lands). A counter would additionally have to be right about how long
   * qits-projects may be down for, which is not a question this service can answer.
   *
   * <p>So each ending writes the column and the row stops being read, and until one of them happens
   * qits-projects is asked once per poll tick — which is the same tick that follows a running CI run
   * and is a no-op whenever nothing is owed.
   *
   * <p><b>It reaches NOTHING_TO_DO rows as well as SUCCEEDED ones, and that is what heals a branch
   * already stranded.</b> The ending makes the ask now (see {@code finishGroup}), but rows that
   * ended before it did are sitting with a pushed branch and a null column, and nothing would ever
   * look at them again. Their price is one extra pair of head reads here: a NOTHING_TO_DO branch may
   * legitimately be level with main, which is not something a PUSHED branch row can tell you, and
   * asking to release a branch with nothing on it is a request somebody has to close by hand.
   */
  public void retryRelease(UUID id) {
    Optional<MtBump> found = store.bump(id);
    if (found.isEmpty()) {
      return;
    }
    MtBump bump = found.get();
    boolean owed =
        BumpStatus.SUCCEEDED.name().equals(bump.status)
            // NOTHING_TO_DO is owed an ask whenever its branch is ahead of main — see
            // `bumpsOwedARelease` and `finishGroup`. This is the path a row that ended before that
            // was true reaches, which is what makes an already-stranded branch heal rather than
            // needing a hand.
            || BumpStatus.NOTHING_TO_DO.name().equals(bump.status);
    if (!owed || bump.releaseRequestId != null) {
      // Settled between the sweep's read and this task. Idempotent by design: the sweep queues onto
      // one worker thread and a second tick can land behind the first.
      return;
    }
    if (!BumpMode.of(bump.mode).ownsTheBranch()) {
      // An automation is owed no release ask at all — the request its commit belongs to is
      // already open. `bumpsOwedARelease` filters these out, so this is unreachable from the sweep;
      // it is written anyway because "the ask is owed" is decided in two places and the one that
      // MAKES the ask should be the one that cannot be talked into it.
      return;
    }
    Optional<MtBranch> branch = store.branch(bump.repository, bump.groupName);
    String state = branch.map(row -> row.state).orElse(null);
    if (!BranchState.PUSHED.name().equals(state)) {
      // NONE, STALE, or no row at all. In every one of them the branch this bump pushed is no
      // longer this bump's to ask about — it is gone, or somebody owns it by hand.
      store.bumpReleaseAsked(
          id,
          ReleaseRequestClient.CONVERGED,
          note(
              bump,
              bump.branch + " is " + (state == null ? "no longer tracked" : state)
                  + "; no release was asked for again"));
      return;
    }
    // AHEAD OF MAIN, ASKED FRESH — the sweep cannot inherit `finishGroup`'s reading. A PUSHED row
    // only says the branch existed when it was last read, and `recordBranchHead` writes PUSHED for
    // any branch with a head at all, including one that is still exactly main. Since NOTHING_TO_DO
    // rows joined this sweep, some of them are precisely that, and asking qits-projects to release a
    // branch with nothing on it is a request somebody then has to close. One head read per owed row
    // per tick, on a listing that empties after one tick.
    Optional<MtRepository> repository = store.repository(bump.repository);
    String head = repository.map(row -> branchHead(row, bump.branch)).orElse(null);
    String base = repository.map(row -> branchHead(row, mainBranch(row))).orElse(null);
    if (head == null || base == null || head.equals(base)) {
      // head == base is the settled case and writes CONVERGED: there is nothing on the branch, and
      // there never will be under this bump. An UNREADABLE head is not — the git host being quiet
      // for a tick must not close an ask that is genuinely owed, so it is left for the next sweep,
      // which is the same policy an automation's ending applies to a head it could not read.
      if (head != null && base != null) {
        store.bumpReleaseAsked(
            id,
            ReleaseRequestClient.CONVERGED,
            note(bump, bump.branch + " is level with " + mainBranch(repository.get())
                + "; there is nothing on it to release"));
      }
      return;
    }
    askForRelease(bump);
  }

  /**
   * Opens a release request in qits-projects for the branch this bump pushed, and records what came
   * back.
   *
   * <p>Everything about WHY the ask looks like this is in {@link ReleaseRequestClient}; what belongs
   * here is the one refusal that is this service's own: a repository row with no catalog id. The ask
   * is addressed by qits-projects' OWN row id, which {@code CatalogReader} copies onto {@code
   * mt_repository.catalog_id} — a row the catalog listed without one, or one no scan has re-read
   * since that column existed, cannot address the route at all. That is a refusal rather than a
   * retry, because no number of attempts adds an id to it; the next scan does, and the next bump
   * then asks with it.
   */
  private void askForRelease(MtBump bump) {
    Optional<MtRepository> repository = store.repository(bump.repository);
    String repoId = repository.map(row -> row.catalogId).orElse(null);
    if (repoId == null || repoId.isBlank()) {
      store.bumpReleaseAsked(
          bump.id,
          ReleaseRequestClient.REFUSED,
          note(
              bump,
              "the release request cannot be addressed: "
                  + bump.repository
                  + " has no catalog id on its inventory row"));
      LOG.warnf(
          "The bump %s pushed %s but has no catalog id to address qits-projects with",
          bump.id, bump.branch);
      return;
    }
    ReleaseRequestClient.RequestResult result =
        releases.requestRelease(
            repoId,
            bump.branch,
            ReleaseRequestClient.summary(bump.groupName, changes(bump).size()));
    store.bumpReleaseAsked(bump.id, result.requestId(), note(bump, result.message()));
    switch (result.outcome()) {
      case REQUESTED -> {
        // REMEMBERED AS OURS (qits-1133): the dependency-bump automation plans third-party upgrades
        // only in a request this service opened, and a group bump's ask is one.
        store.recordOpenedRequest(
            result.requestId(),
            bump.repository,
            bump.branch,
            eu.wohlben.qits.maintenance.entity.MtReleaseRequest.GROUP_BUMP,
            null,
            java.time.Instant.now());
        LOG.infof(
            "The bump %s asked for %s to be released: request %s",
            bump.id, bump.branch, result.requestId());
      }
      case CONVERGED ->
          LOG.infof("The bump %s has nothing left to ask about %s", bump.id, bump.branch);
      case REFUSED ->
          LOG.warnf("The release request for %s was refused: %s", bump.branch, result.message());
      case RETRY ->
          LOG.warnf(
              "qits-projects did not answer the release request for %s: %s; the next sweep asks"
                  + " again",
              bump.branch, result.message());
    }
  }

  /**
   * The verdict, once every run is terminal — read the way this bump's MODE says to read it.
   *
   * <p>Which of the two follows is the whole of what the mode is for; see the class javadoc and
   * {@link BumpMode}.
   */
  private void finish(
      MtBump bump, boolean passed, String ciRunStatus, CiClient.Failure failure) {
    if (BumpMode.of(bump.mode) == BumpMode.AUTOMATION) {
      automations.finish(bump, passed, ciRunStatus, failure);
      return;
    }
    finishGroup(bump, passed, ciRunStatus);
  }

  /**
   * The verdict on a bump onto THIS SERVICE'S OWN branch, once every run is terminal.
   *
   * <p>The branch head is read again here, and the comparison against what was recorded before the
   * trigger is what separates the three endings. It may be read that way because nothing else
   * commits to {@code maintenance/<group>} — which is precisely what an automation writing a
   * request's own branches cannot assume; see {@code AutomationService.finish}.
   */
  private void finishGroup(MtBump bump, boolean passed, String ciRunStatus) {
    Instant now = Instant.now();
    Optional<MtRepository> repository = store.repository(bump.repository);
    String branch = bump.branch;
    String before = store.branch(bump.repository, bump.groupName).map(row -> row.headSha).orElse(null);
    String after =
        repository
            .map(row -> branchHead(row, branch))
            .orElse(null);
    boolean moved = after != null && !after.equals(before);

    if (passed && moved) {
      store.recordBranch(bump.repository, bump.groupName, branch, BranchState.PUSHED, after, now);
      store.bumpFinished(bump.id, BumpStatus.SUCCEEDED, ciRunStatus, pushedMessage(bump), now);
      // THE ROW IS CLOSED BEFORE THE RELEASE IS ASKED FOR, and the order is the failure policy. The
      // bump succeeded on the strength of the run and the head; a qits-projects that will not answer
      // must not be able to change that verdict, and a process that died between the two lines
      // leaves a SUCCEEDED bump the sweep picks up rather than a bump with no ending at all.
      askForRelease(bump);
      return;
    }
    if (passed) {
      // Green, and the branch is where it was. The step read the files and found the versions
      // already there — the pins moved between the scan and the run, or another bump got there
      // first.
      //
      // THAT IS NOT THE SAME QUESTION AS "IS THERE ANYTHING TO RELEASE", and treating it as one cost
      // qits-mirror-platform-service a day on an unreleased library. `moved` compares two reads of
      // the head around one run, so it answers "did THIS run push"; whether the branch is carrying
      // unreleased commits is a question about the branch against main, and every way the first can
      // say no while the second says yes leaves the branch stranded: the read raced the step's push,
      // an earlier run pushed and ended here for the same reason, a retry re-ran a step that had
      // already done its work. Nothing asks again afterwards, because NOTHING_TO_DO was the one
      // green ending that made no ask — and the dispatcher then HOLDS the repository for ever,
      // waiting on a release nobody will ever request. `GET /maintenance/api/bumps/window` said so
      // out loud ("its branch is pushed and it waits on its own release") while this line said the
      // opposite.
      //
      // So the ASK follows the branch and only the STATUS follows the run. Ahead of main means there
      // is something to release and this asks for it, exactly as the moved case does and by the same
      // convergent ask; the status stays NOTHING_TO_DO because this run really did write nothing,
      // and a reader deserves that distinction rather than a SUCCEEDED that invents a push.
      String base = repository.map(row -> branchHead(row, mainBranch(row))).orElse(null);
      if (after != null && base != null && !after.equals(base)) {
        store.recordBranch(bump.repository, bump.groupName, branch, BranchState.PUSHED, after, now);
        store.bumpFinished(
            bump.id,
            BumpStatus.NOTHING_TO_DO,
            ciRunStatus,
            "the run passed and " + branch + " did not move, but it is ahead of " + mainBranch(repository.get())
                + " and unreleased",
            now);
        // Closed before the ask, for the reason the moved case gives: a qits-projects that will not
        // answer must not be able to change the verdict.
        askForRelease(bump);
        return;
      }
      // No branch, or a branch that is main. Genuinely nothing — the one case the old wording
      // always described correctly. `base == null` joins them deliberately: an unreadable main is
      // not evidence that the branch is ahead, and asking to release a branch on that guess is the
      // expensive direction to be wrong in.
      store.bumpFinished(
          bump.id,
          BumpStatus.NOTHING_TO_DO,
          ciRunStatus,
          "the run passed and " + branch + " did not move",
          now);
      return;
    }
    // Red. A branch that moved anyway is somebody's hand-written commit that the ff-only push
    // refused to overwrite — they own it now.
    BranchState state = moved ? BranchState.STALE : BranchState.FAILED;
    store.recordBranch(bump.repository, bump.groupName, branch, state, after, now);
    store.bumpFinished(
        bump.id,
        BumpStatus.FAILED,
        ciRunStatus,
        state == BranchState.STALE
            ? branch + " was rewritten by hand; nothing will be pushed onto it"
            : "the ci run ended " + ciRunStatus,
        now);
  }

  /**
   * What a SUCCEEDED bump's message says on its own — the sentence the ending writes, before the
   * release ask has anything to add.
   */
  private static String pushedMessage(MtBump bump) {
    return changes(bump).size() + " dependencies on " + bump.branch;
  }

  /**
   * The bump's own sentence with the release ask's beside it.
   *
   * <p><b>Recomposed from the ending rather than appended to whatever is there.</b> The ask is
   * retried once per poll tick until it settles, and an append would grow the column by a line every
   * fifteen seconds for the length of an outage. This is idempotent: the base is derived from the
   * frozen change list and the branch, so N attempts leave one base and the newest note.
   */
  static String note(MtBump bump, String note) {
    return note == null || note.isBlank() ? pushedMessage(bump) : pushedMessage(bump) + " — " + note;
  }

  /** Reads the branch's head and writes it, so the next comparison has something to compare to. */
  private void recordBranchHead(MtRepository repository, String group, String branch) {
    String head = branchHead(repository, branch);
    BranchState state = head == null ? BranchState.NONE : BranchState.PUSHED;
    store.recordBranch(repository.name, group, branch, state, head, Instant.now());
  }

  /** The branch's head sha, or null when it does not exist or could not be read. */
  private String branchHead(MtRepository repository, String branch) {
    TreeLookup lookup = gitHost.head(repository.project, repository.name, branch);
    return lookup.found() ? lookup.headSha() : null;
  }

  /** The stored changes, back as the records they went out as. */
  public static List<Change> changes(MtBump bump) {
    List<Change> changes = new ArrayList<>();
    for (Map<String, Object> row : MaintenanceStore.readObjects(bump.changes)) {
      changes.add(
          new Change(
              string(row, "ecosystem"),
              string(row, "manifestPath"),
              string(row, "name"),
              string(row, "from"),
              string(row, "to"),
              string(row, "location")));
    }
    return changes;
  }

  private static String string(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : value.toString();
  }

  private static List<String> runIds(MtBump bump) {
    if (bump.ciRunId == null || bump.ciRunId.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(bump.ciRunId.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .toList();
  }

  /**
   * The repository's main branch — what "ahead of main" is measured against, whatever the bump's own
   * base was. A branch cut from an unmerged tag is ahead of main by that tag as well, which is right:
   * the fold carries the tag either way.
   */
  private static String mainBranch(MtRepository repository) {
    return BumpBase.mainBranch(repository);
  }

  /** The branch rows of one repository, for the API's group listing. */
  public List<MtBranch> branches(String repository) {
    return store.branches(repository);
  }
}

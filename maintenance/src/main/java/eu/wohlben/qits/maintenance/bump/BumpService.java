package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.automation.AutomationService;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.pending.Change;
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
 * The {@code mt_bump} rows' engine room: send a REQUESTED row to qits-ci, follow a RUNNING one's ci
 * run to its verdict, and hand the verdict to whoever reads it.
 *
 * <p><b>This service decides WHAT changes; a CI step applies them.</b> Nothing here clones,
 * rewrites a file or pushes a ref. The payload names a file, a location and two versions, and the
 * step that reads it is the only thing that touches the repository — which is what keeps this
 * service out of every repository's write path.
 *
 * <h2>Every row it moves is a release-request automation</h2>
 *
 * <p><b>An AUTOMATION row is {@link AutomationService}'s</b> (qits-978): its dispatch, its caps,
 * its payload and its ending are written once there for every kind — {@code dependency-bump}, {@code
 * estate-pins}, {@code screenshot-baselines}, {@code entity-diagram} — and this class only routes:
 * {@link #dispatch} and the poll's ending hand such a row over, and {@link #sweep} reaches it like
 * any other active row. What is shared, and therefore lives here, is reading a CI run to its
 * verdict — through qits-ci's automatic retries (qits-760) — because every kind's run is judged the
 * same way.
 *
 * <h2>Group bumps are retired (qits-1133 R5)</h2>
 *
 * <p>This class used to own the {@code maintenance/<group>} path as well: a group's pending pins
 * frozen onto a row, sent to qits-ci, the branch head read before and after the run, and a release
 * request asked for when the branch was ahead of main. R2 switched that path off and R5 removed it;
 * a repository's pins are written by the {@code dependency-bump} automation inside the release
 * request they belong to, and {@link BumpDispatcher} opens a main-only request for one that has
 * none. {@code V22} closed every GROUP row the old path could still have been waiting on; the rows
 * themselves stay as history. A GROUP row this build nevertheless finds active — which V22 makes
 * impossible short of a hand-written insert — is {@linkplain #retire closed unsent} rather than
 * followed. {@link LegacyGroupBranchSweep} deletes the branches the old path left standing.
 */
@ApplicationScoped
public class BumpService {

  private static final Logger LOG = Logger.getLogger(BumpService.class);

  /**
   * Every maintenance branch is under this prefix — the automations' {@code
   * maintenance/automations/…} and the retired {@code maintenance/<group>} alike.
   */
  public static final String BRANCH_PREFIX = "maintenance/";

  @Inject MaintenanceStore store;

  @Inject CiClient ci;

  @Inject WorkQueue queue;

  /** The engine every release-request automation runs on; AUTOMATION rows are its to read. */
  @Inject AutomationService automations;

  /**
   * Sends one REQUESTED row to qits-ci, through its automation.
   *
   * <p>Idempotent by design: a row that is not REQUESTED any more has already been sent, and the
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
    if (BumpMode.of(bump.mode) != BumpMode.AUTOMATION) {
      retire(bump, "it was still waiting to be sent");
      return;
    }
    // Its own engine: carry-over asked again, the per-branch and estate-wide caps, and a payload
    // shaped by the kind's target.
    automations.dispatch(bump);
  }

  /**
   * Closes a GROUP row this build found active: nothing is sent and nothing is released. Its pins
   * are the {@code dependency-bump} automation's now; the {@code converged} sentinel keeps it out of
   * every listing of what is still on its way.
   */
  private void retire(MtBump bump, String why) {
    String message =
        "group bumps are retired (qits-1133) and " + why + "; the dependency-bump automation plans"
            + " these pins now";
    store.bumpFinished(bump.id, BumpStatus.NOTHING_TO_DO, null, message, Instant.now());
    store.bumpReleaseAsked(bump.id, ReleaseRequestClient.CONVERGED, message);
    LOG.infof("The group bump %s of %s/%s was closed: %s", bump.id, bump.repository,
        bump.groupName, message);
  }

  /** The message a row keeps while the docs store cannot be read; it is sent again. */
  public static final String CHANGELOGS_UNREADABLE =
      "the changelogs could not be read from qits-artifacts yet; the bump will be sent again";

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
    if (BumpMode.of(bump.mode) != BumpMode.AUTOMATION) {
      retire(bump, "its run is not followed");
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

  /** Two jobs: send every REQUESTED row again, and follow every RUNNING one. */
  public void sweep() {
    for (MtBump bump : store.activeBumps()) {
      UUID id = bump.id;
      if (BumpStatus.REQUESTED.name().equals(bump.status)) {
        queue.submit("re-dispatch bump " + id, () -> dispatch(id));
      } else {
        queue.submit("poll bump " + id, () -> poll(id));
      }
    }
  }

  /** The verdict, once every run is terminal: the automation's to read. */
  private void finish(
      MtBump bump, boolean passed, String ciRunStatus, CiClient.Failure failure) {
    automations.finish(bump, passed, ciRunStatus, failure);
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

  /** The branch rows of one repository, for the API's group listing. */
  public List<MtBranch> branches(String repository) {
    return store.branches(repository);
  }
}

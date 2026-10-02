package eu.wohlben.qits.maintenance.dto;

import eu.wohlben.qits.maintenance.pending.Change;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One bump row, with the changes it SENT.
 *
 * <p><b>The changes are what went out, not what is pending now.</b> By the time anyone reads a bump
 * the pins have moved and the latest versions have moved again; a recomputed list would answer a
 * different question from the one the row is being read for.
 *
 * @param id the bump, which is also the ci event's dedupe key
 * @param repository which repository
 * @param group which group, and therefore which branch — or the stated sentinel {@code targeted} on
 *     a TARGETED bump, which belongs to no group and named its own branch
 * @param mode GROUP, TARGETED or BASELINES: whose branch this wrote, and what. GROUP owns {@code branch}, tracks it and
 *     asks for its release; TARGETED wrote onto the caller's branch, tracks nothing and asks for
 *     nothing
 * @param branch the ref the changes go on
 * @param environment which environment's ci ran it
 * @param trigger SCHEDULED or MANUAL
 * @param status REQUESTED, RUNNING, SUCCEEDED, FAILED or NOTHING_TO_DO
 * @param ciEventId the event id the trigger carried
 * @param ciRunId the runs qits-ci named, comma-separated — the column verbatim, and the field the
 *     client reads
 * @param ciRunIds the same ids as a list, because a trigger can match more than one pipeline
 * @param configPath the pipeline file in the wrapper that ran it, which qits-ci records on the run
 * @param ciRunStatus the last ci run status this service read
 * @param startedAt when the row was opened
 * @param finishedAt when it ended, null while it has not
 * @param message the sentence
 * @param resultSha <b>the commit this bump wrote</b> — the branch head as read once the run ended.
 *     It is what a TARGETED caller polls for: it asked for pins to be put in a fold it is about to
 *     gate, and this is which commit holds them. Null while the bump has not ended, null on an
 *     ending that pushed nothing, and null on a green run whose head could not be read afterwards —
 *     the verdict is the run's, so a git host that was briefly away costs the sha and not the
 *     SUCCEEDED. A group bump's own answer to the same question is its branch row's {@code headSha}
 * @param releaseRequestId what came of asking qits-projects to release the branch: the release
 *     request's id — OPEN, not released; the gates settle it and Auto Release tags it afterwards —
 *     or {@code converged} (there was nothing to hold on to), {@code refused} (a refusal a retry
 *     cannot fix — {@code message} says which), or null while the ask is still owed. Null for ever
 *     on a bump that pushed no branch
 * @param releaseState what qits-projects last said about that request — PENDING, READY, RELEASED,
 *     REJECTED, FAILED, CONFLICTED, WITHDRAWN — or null on a bump nothing has asked about. <b>An
 *     observation and not a gate</b>: the dispatcher re-asks and acts on the fresh answer, because a
 *     rejected request is re-armed to PENDING by the next merged sha
 * @param releaseDetail that service's sentence about it — usually the gating run that went red
 * @param releaseStateAt when the two above were read
 * @param changes the payload's changes, verbatim
 */
public record BumpDto(
    UUID id,
    String repository,
    String group,
    String mode,
    String branch,
    String environment,
    String trigger,
    String status,
    String ciEventId,
    String ciRunId,
    List<String> ciRunIds,
    String configPath,
    String ciRunStatus,
    Instant startedAt,
    Instant finishedAt,
    String message,
    String resultSha,
    String releaseRequestId,
    String releaseState,
    String releaseDetail,
    Instant releaseStateAt,
    List<Change> changes) {}

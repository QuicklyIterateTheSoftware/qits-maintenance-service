package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One request to qits-ci to apply one group's pending changes, and what came of it.
 *
 * <p><b>The id is also the CI event's dedupe key.</b> It goes out as {@code eventId}, so a dispatch
 * that reached qits-ci and whose answer this service lost records no second run when it is retried.
 *
 * <p><b>{@link #changes} is what was SENT, stored rather than recomputed.</b> By the time anyone
 * reads this row the pins have moved and the latest versions have moved again; "what did we ask
 * for" is the only question a surprising branch can be investigated with.
 */
@Entity
@Table(name = "mt_bump")
public class MtBump extends PanacheEntityBase {

  @Id public UUID id;

  @Column(nullable = false, length = 255)
  public String repository;

  /**
   * Which group's pending changes this carries — <b>or, for a {@link #mode TARGETED} bump, the
   * stated sentinel {@code BumpService.TARGETED_GROUP}</b>, because a targeted bump has no group at
   * all and the column is not null.
   *
   * <p><b>For a targeted row this is a LABEL and never a key.</b> {@link #mode} is the
   * discriminator: every query that means "the group path" says so by mode, so a repository that
   * really does declare a group spelled like the sentinel collides with nothing. The sentinel is
   * carried anyway rather than left blank because it reaches two places a person reads — the
   * payload's {@code group}, which the step refuses unless it is a plain name, and the commit
   * subject the step writes onto somebody else's branch, where {@code bump(targeted): 3
   * dependencies} says what wrote it.
   */
  @Column(name = "group_name", nullable = false, length = 255)
  public String groupName;

  /**
   * {@code BumpMode}'s names: GROUP or TARGETED — <b>whose branch this is writing</b>, which is what
   * decides how the ending is read.
   *
   * <p>GROUP owns {@link #branch}: the head before and after the run is the measurement, an {@code
   * mt_branch} row tracks it, and a green run that pushed asks for a release. TARGETED does not own
   * it: the branch is the caller's, its head moves for reasons that are not this service's, so the
   * verdict is the CI run's alone, no branch row is written and no release is asked for. See {@code
   * V12__bump_mode.sql} for what each of those would get wrong the other way round.
   */
  @Column(nullable = false, length = 16)
  public String mode;

  /** The ref this bump writes, without {@code refs/heads/}. Stored rather than derived from the
   * group: it is what the payload carried, and a row must stay readable after the naming rule
   * changes. */
  @Column(nullable = false, length = 512)
  public String branch;

  /** Which environment's CI ran it. Platform tier calling a per-environment service, recorded so a
   * second environment is a config entry rather than a schema change. */
  @Column(nullable = false, length = 64)
  public String environment;

  /** {@code BumpTrigger}'s names: SCHEDULED or MANUAL. */
  @Column(name = "trigger", nullable = false, length = 32)
  public String trigger;

  /** The {@code eventId} the trigger carried. */
  @Column(name = "ci_event_id", length = 255)
  public String ciEventId;

  /** The run ids qits-ci answered with, comma-separated. Plural: a trigger can match more than one
   * pipeline, and the poller follows every id it was given. */
  @Column(name = "ci_run_id", columnDefinition = "text")
  public String ciRunId;

  /** The last CI run status read, verbatim, so a RUNNING bump can say what CI is doing. */
  @Column(name = "ci_run_status", length = 32)
  public String ciRunStatus;

  /** {@code BumpStatus}'s names. */
  @Column(nullable = false, length = 32)
  public String status;

  /** The changes as the JSON array the payload carried. */
  @Column(columnDefinition = "text")
  public String changes;

  @Column(name = "started_at", nullable = false)
  public Instant startedAt;

  /** Null while the bump is REQUESTED or RUNNING, and null forever for one whose process died. */
  @Column(name = "finished_at")
  public Instant finishedAt;

  @Column(columnDefinition = "text")
  public String message;

  /**
   * WHAT COMMIT THIS BUMP WROTE — the branch head as read once the run ended.
   *
   * <p><b>It exists because a TARGETED bump has nowhere else to put it.</b> A group bump's answer is
   * {@code mt_branch.head_sha}, which it may write because the branch is its own; a targeted bump
   * writes no branch row, and its caller — which asked for pins to be put in a fold it is about to
   * gate — needs the sha more than any group reader ever did.
   *
   * <p>Null while the bump has not ended, null on an ending that pushed nothing, and null on a green
   * run whose branch head could not be read afterwards: <b>the verdict belongs to the run</b>, so a
   * git host that was briefly away costs the sha and a sentence on {@link #message}, never the
   * SUCCEEDED.
   */
  @Column(name = "result_sha", length = 64)
  public String resultSha;

  /**
   * What came of asking qits-projects to release the branch this bump pushed.
   *
   * <p><b>Three shapes, and NULL is the one that means work is owed.</b> A release request id is the
   * row to follow in qits-projects — <b>open, not released</b>: the gates settle it and Auto Release
   * tags it afterwards, and this service does not watch either. {@code converged} is "there was
   * nothing to hold on to" — no id came back, or the branch was deleted before the ask could be
   * made; {@code refused} is a refusal a retry cannot fix, with the reason on {@link #message}. The
   * sweep re-attempts the ask for exactly the SUCCEEDED rows where this is null and the branch is
   * still PUSHED, so every ending writes something here and stops being read.
   *
   * <p>Null for ever on a bump that is not SUCCEEDED: there is no branch to release.
   */
  /**
   * The work item a {@code BASELINES} bump names in its commit subject, for example {@code
   * qits-112}. Null on every other mode.
   */
  @Column(name = "work_item", length = 64)
  public String workItem;

  @Column(name = "release_request_id", length = 255)
  public String releaseRequestId;

  /**
   * What qits-projects last said about that request — {@code PENDING}, {@code READY}, {@code
   * RELEASED}, {@code REJECTED}, {@code FAILED}, {@code CONFLICTED}, {@code WITHDRAWN}.
   *
   * <p><b>A cached observation and never a verdict.</b> The dispatcher writes it when it asks,
   * because a person reading a bump that has been standing for hours needs to see why; nothing gates
   * on the column, and the gate re-asks. That matters because qits-projects re-arms a rejected
   * request the moment its fold changes, so a stored REJECTED would be a stale reason to keep a
   * repository out of the estate's nights for ever.
   */
  @Column(name = "release_state", length = 32)
  public String releaseState;

  /** That service's sentence about it — the failing gating run, the conflicting paths. */
  @Column(name = "release_detail", columnDefinition = "text")
  public String releaseDetail;

  /** When the state above was last read. Null on a bump nothing has asked about. */
  @Column(name = "release_state_at")
  public Instant releaseStateAt;
}

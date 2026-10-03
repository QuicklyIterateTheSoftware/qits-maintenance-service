package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * The dispatch window, as {@code GET /bumps/window} serves it — <b>and the reason nothing is being
 * dispatched, which is the question this door is actually opened for.</b>
 *
 * <p><b>{@code open} is not a stored column</b> — it is {@code closesAt} against the clock, which is
 * the same comparison the tick makes. A row whose window has expired but which no tick has reached
 * yet is a real state and this reports it honestly: {@code closesAt} in the past, {@code open}
 * false.
 *
 * <p><b>Everything from {@code outcome} down is the tick's own reasoning, computed on the read and
 * changing nothing.</b> Three separate investigations on this ticket had to reconstruct "there are
 * pending bumps and no builds" out of this service's logs, qits-ci's run listing and qits-projects'
 * release requests, and the fourth was a stalled release nothing reported at all. The gate knows
 * why it is not dispatching at the moment it decides not to; this is that answer, said out loud.
 *
 * <p><b>It is answered whether or not a window is open, and that is the point of the fourth field
 * down.</b> This door used to 404 when there was no window row, and {@code GET /bumps} only holds
 * bumps that were <i>dispatched</i> — so "fifteen repositories are owed and nothing has been sent"
 * and "the scheduler is dead" were the same picture from every surface this service has, which is
 * how a dispatch path that never armed itself sat unnoticed for a day. With no window the row
 * fields are null, {@code open} is false, and {@code queue} still carries everything owed.
 *
 * @param openedAt when the debt — or the button — opened it, null when no window is open
 * @param closesAt when it ends, null when no window is open
 * @param open whether anything would be dispatched right now
 * @param outcome which gate answered: {@code DISABLED}, {@code NOTHING_OWED}, {@code ALL_STALLED},
 *     {@code QUIET_HOURS}, {@code CI_UNREADABLE}, {@code NO_SLOTS} (no runner connected and
 *     unquarantined), {@code CI_BUSY} (every slot taken), {@code WAITING_ON_RELEASES} or {@code
 *     DISPATCH}
 * @param summary the same thing as a sentence
 * @param inFlight bumps of this service's that have not ended, null when the gate answered before
 *     asking
 * @param slots qits-ci's capacity — the slots of its connected, unquarantined runners — null when
 *     it was not asked or would not answer
 * @param free how many of those slots this tick may fill: slots minus qits-ci's active runs minus
 *     this service's REQUESTED bumps (asked for, not yet accepted by qits-ci), floored at zero; null
 *     when qits-ci was not asked or would not answer
 * @param ciActive what qits-ci holds, running and queued, null when it was not asked or would not
 *     answer
 * @param owed repositories owed a bump that could still be sent one
 * @param held how many of those are waiting on a release of their own branch
 * @param stalled the ones waiting on a release that has STOPPED — these are not owed any more as
 *     far as the dispatcher is concerned, and each one names the request and what qits-projects said
 * @param queue THE WHOLE OWED SET, in the order it will be handed out, each entry with its reason
 * @param next what would be dispatched first right now, null when nothing would be
 * @param picks everything this tick would dispatch, in order — up to {@code free} READY
 *     repositories, or one cycle break — empty when nothing would be
 */
public record BumpWindowDto(
    Instant openedAt,
    Instant closesAt,
    boolean open,
    String outcome,
    String summary,
    Integer inFlight,
    Integer slots,
    Integer free,
    Integer ciActive,
    int owed,
    int held,
    List<StalledBumpDto> stalled,
    List<OwedBumpDto> queue,
    String next,
    List<String> picks) {

  /**
   * One repository owed a bump, and why it is where it is in the queue.
   *
   * @param repository the repository
   * @param group the group whose branch is owed
   * @param changes how many changes that bump would carry
   * @param reason {@code READY} (the head of the list is what goes next), {@code BLOCKED} (owed
   *     repositories sit below it), {@code HELD} (its branch is pushed and it waits on its own
   *     release), {@code STALLED} (that release has stopped) or {@code REFUSED} (this gate could not
   *     ask for it)
   * @param detail the sentence for that reason — what it waits on, or what said no
   */
  public record OwedBumpDto(
      String repository, String group, int changes, String reason, String detail) {}

  /**
   * One repository owed a bump whose release has stopped.
   *
   * @param repository the repository
   * @param group the group whose branch is waiting
   * @param releaseRequestId the request in qits-projects, null when the ask itself was refused
   * @param state REJECTED, FAILED, CONFLICTED, WITHDRAWN — or REFUSED for the ask
   * @param reason that service's sentence, usually the gating run that went red
   */
  public record StalledBumpDto(
      String repository, String group, String releaseRequestId, String state, String reason) {}
}

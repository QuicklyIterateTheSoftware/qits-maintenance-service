package eu.wohlben.qits.maintenance.dto;

import java.util.List;

/**
 * <b>Where every owed bump stands, and what the dispatcher would do about it right now</b>
 * (qits-1696): {@code GET /maintenance/api/bumps/standing}.
 *
 * <p>The dispatcher used to be readable only through its log, so a repository owed a bump for hours
 * and a dispatcher that was dead were the same picture from every surface — which is how a two-node
 * cycle between {@code qits-system-service} and {@code qits-system-frontend} sat unbroken behind
 * thirty unrelated READY bumps on 2026-10-28 without anybody being able to say why. This is the
 * decision of one tick, made and not acted on, with the whole owed set laid out beside it.
 *
 * <p><b>A snapshot, not a promise.</b> The dispatcher recomputes every tick, so the next one may
 * already disagree — a release lands, a slot frees, a scan moves a pin.
 *
 * @param outcome the gate that answered: {@code DISABLED}, {@code NOTHING_OWED}, {@code
 *     QUIET_HOURS}, {@code CI_UNREADABLE}, {@code NO_SLOTS}, {@code CI_BUSY}, {@code
 *     WAITING_ON_RELEASES} or {@code DISPATCH}
 * @param summary the same sentence the dispatcher would explain itself with
 * @param cap the configured CI cap, null when that gate was not reached or the queue was unreadable
 * @param free how much of the cap a tick may fill now, null likewise
 * @param ciActive qits-ci's running and queued runs, null likewise
 * @param owed how many repositories are owed a bump and could still be sent one — the length of
 *     {@code standing}, counted whichever gate answered
 * @param held how many of those wait on a release request already on its way
 * @param picks what a tick would dispatch now, in order — empty on every outcome but DISPATCH
 * @param standing every owed repository in the order it would be handed out: READY, then BLOCKED by
 *     fewest owed upstreams, then HELD
 * @param refused repositories owed a bump that are set aside after a refused ask, until the refusal
 *     ages out
 */
public record BumpStandingDto(
    String outcome,
    String summary,
    Integer cap,
    Integer free,
    Integer ciActive,
    int owed,
    int held,
    List<StandingPickDto> picks,
    List<StandingEntryDto> standing,
    List<String> refused) {

  /**
   * One repository a tick would dispatch now.
   *
   * @param repository the repository, as the catalog spells it
   * @param cycleBroken whether it is the tick's one cycle break — sent although it waits on owed
   *     upstreams, because they wait back on it
   * @param blockedBy the owed upstreams it is sent despite, sorted; empty unless {@code cycleBroken}
   */
  public record StandingPickDto(String repository, boolean cycleBroken, List<String> blockedBy) {}

  /**
   * One owed repository and why it stands where it does.
   *
   * @param repository the repository, as the catalog spells it
   * @param reason {@code READY} (nothing owed sits below it), {@code BLOCKED} (it waits on other
   *     owed repositories) or {@code HELD} (a release request already carries its bump)
   * @param blockedBy the owed upstreams it waits on, sorted; empty unless BLOCKED
   * @param changes the pins its bump would move
   */
  public record StandingEntryDto(
      String repository, String reason, List<String> blockedBy, List<StandingChangeDto> changes) {}

  /**
   * One pin a bump would move.
   *
   * @param ecosystem the wire name: {@code maven}, {@code npm}, {@code docker}, {@code gitlink}
   * @param name the dependency — for a gitlink, the submodule's repository
   * @param from the version pinned on main now
   * @param to the version it would be moved to
   */
  public record StandingChangeDto(String ecosystem, String name, String from, String to) {}
}

package eu.wohlben.qits.maintenance.dto;

import eu.wohlben.qits.maintenance.automation.UpstreamReplan;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * <b>What this service remembers about one release request</b> (V21, qits-1133) — the answer of
 * {@code GET /release-requests/{requestId}}, one {@code mt_release_request} row read as it is.
 *
 * <p><b>The request is qits-projects'</b>; this is only this service's side of it: whether it
 * opened the request and why, how often an upstream release restarted its pre-run, and whether it
 * withdrew it. A request this service never opened can still have a row — {@code opened} false,
 * with no purpose, branch or opening time — when an upstream release restarted its pre-run, which
 * is the count the starvation guard reads. A request with no row at all is a 404.
 *
 * <p><b>The purpose decides what the dependency bump plans.</b> {@code MAIN_ONLY} — the
 * dispatcher's upstream path opened a main-only {@code LOWEST} request for its pre-run to write the
 * bump — is the ONLY origin in which {@code dependency-bump} plans EXTERNAL upgrades; a {@code
 * GROUP_BUMP} request (a group bump's release ask, {@code maintenance/<group>}) carries internal
 * pins only.
 *
 * @param requestId the release request
 * @param repository the repository it belongs to, as the catalog spells it
 * @param opened whether this service opened the request itself; false on a row an upstream restart
 *     made for a request somebody else opened
 * @param purpose {@code GROUP_BUMP} or {@code MAIN_ONLY}; null when this service did not open it
 * @param branch the branch the request was opened for; null when this service did not open it
 * @param changes the pending changes a {@code MAIN_ONLY} request was opened for, as the dispatcher
 *     stored them; empty when none were stored
 * @param openedAt when this service opened it; null when it did not
 * @param upstreamRestarts upstream-driven restarts of the request's pre-run since its last QA
 *     verdict; a verdict resets it to zero
 * @param upstreamRestartedAt when an upstream release last restarted it, or null
 * @param starved whether the starvation guard holds the request: at {@link
 *     UpstreamReplan#STARVATION_RESTARTS} restarts or more, upstream releases no longer re-plan it
 *     until it has a QA verdict
 * @param withdrawnAt when this service withdrew the request, or null while it has not
 * @param withdrawnReason the sentence it withdrew the request with, or null
 * @param updatedAt when the row last changed
 */
public record ReleaseRequestMemoryDto(
    String requestId,
    String repository,
    boolean opened,
    String purpose,
    String branch,
    List<Map<String, Object>> changes,
    Instant openedAt,
    int upstreamRestarts,
    Instant upstreamRestartedAt,
    boolean starved,
    Instant withdrawnAt,
    String withdrawnReason,
    Instant updatedAt) {

  /** One remembered row, its {@code changes} column read back as the JSON array it holds. */
  public static ReleaseRequestMemoryDto from(MtReleaseRequest row) {
    return new ReleaseRequestMemoryDto(
        row.requestId,
        row.repository,
        row.opened,
        row.purpose,
        row.branch,
        MaintenanceStore.readObjects(row.changes),
        row.openedAt,
        row.upstreamRestarts,
        row.upstreamRestartedAt,
        row.upstreamRestarts >= UpstreamReplan.STARVATION_RESTARTS,
        row.withdrawnAt,
        row.withdrawnReason,
        row.updatedAt);
  }
}

package eu.wohlben.qits.maintenance.dto;

import java.util.List;

/**
 * The bumps still on their way, newest first: what the top bar's bumps menu lists.
 *
 * <p>An object rather than a bare array, so a consumer's pact can bind the list under a name: a
 * pact-jvm provider check does not apply a consumer's rules at the root of a body.
 *
 * @param bumps the pending bumps, newest first — see {@code MaintenanceStore.pendingBumps} for what
 *     counts as pending
 */
public record PendingBumpsDto(List<BumpDto> bumps) {}

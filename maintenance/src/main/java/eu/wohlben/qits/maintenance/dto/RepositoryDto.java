package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * One repository, as the LISTING serves it — no pins, because a list of repositories is read to
 * choose one.
 *
 * @param name the catalog name
 * @param project the project the git host serves it under
 * @param archetype what kind of thing it is, as qits-projects classifies it — SERVICE, DAEMON,
 *     LIBRARY, FRONTEND, CLI, IMAGE, PROJECT, SERVICE_TEMPLATE, FORK. <b>Served verbatim</b>, so a
 *     value this platform has not heard of reaches the browser as itself; null when no scan has
 *     been told one. A client renders it as a label and must not branch on the set being closed —
 *     the vocabulary is another service's and grows there.
 * @param lastScanAt when the last scan finished, null when it has never been scanned
 * @param headSha the commit the pins were read at
 * @param status OK, ABSENT, UNREACHABLE, CONFIG_ERROR or UNDECLARED
 * @param message why the status is not OK
 * @param pending how many changes are pending across every group
 * @param groups the groups, in declaration order
 */
public record RepositoryDto(
    String name,
    String project,
    String archetype,
    Instant lastScanAt,
    String headSha,
    String status,
    String message,
    int pending,
    List<GroupDto> groups) {}

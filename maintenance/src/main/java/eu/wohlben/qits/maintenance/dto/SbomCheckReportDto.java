package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * One run of the daily SBOM check: what it counted, what it could not attribute, and the tickets
 * it holds open. Stored whole as {@code mt_sbom_check_run.report} and served by {@code GET
 * /maintenance/api/sbom-check}.
 *
 * @param ranAt when the run started
 * @param filed whether {@code qits.maintenance.sbom.check.file-tickets} was on — false means the
 *     run was REPORT-ONLY and called nothing in qits-projects
 * @param entries every counted row: released, still in qits-artifacts, no usable SBOM
 * @param warnings rows counted on every other test but carrying no project, and so ticketed
 *     nowhere; plus any ticket call that failed, by group
 * @param tickets the ticket rows still open after the run, each with the versions reported on it
 */
public record SbomCheckReportDto(
    Instant ranAt,
    boolean filed,
    List<EntryDto> entries,
    List<String> warnings,
    List<TicketDto> tickets) {

  /**
   * One released version without a usable SBOM.
   *
   * @param project the qits-projects project the release named
   * @param repository the repository that released it
   * @param ecosystem maven, npm, docker or daemon
   * @param name the artifact's name
   * @param version the released version
   * @param reason MISSING, FAILED or PENDING
   */
  public record EntryDto(
      String project,
      String repository,
      String ecosystem,
      String name,
      String version,
      String reason) {}

  /**
   * One open ticket.
   *
   * @param ticketSlug the qualified id ({@code qits-703}), or the slug when none was answered
   * @param versions the versions reported on it, oldest report first
   */
  public record TicketDto(
      String project, String ecosystem, String name, String ticketSlug, List<String> versions) {}
}

package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One run of the daily SBOM check (V14): when it ran, whether it filed tickets, and the report it
 * computed.
 *
 * <p>The report is JSON TEXT, the convention every other JSON column in this schema follows; it is
 * written once and read back whole by {@code GET /maintenance/api/sbom-check}, and nothing queries
 * into it.
 */
@Entity
@Table(name = "mt_sbom_check_run")
public class MtSbomCheckRun extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "ran_at", nullable = false)
  public Instant ranAt;

  /** Whether {@code qits.maintenance.sbom.check.file-tickets} was on for this run. */
  @Column(nullable = false)
  public boolean filed;

  /** {@code SbomCheckReportDto}, serialized. */
  @Column(nullable = false, columnDefinition = "text")
  public String report;
}

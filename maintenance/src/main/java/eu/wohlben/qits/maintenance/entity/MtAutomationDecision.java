package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One pre-run decision that needs no run: today only "this kind does not apply at this fold"
 * (qits-1133). One row per (request, fold, kind), written once.
 */
@Entity
@Table(name = "mt_automation_decision")
public class MtAutomationDecision extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "release_request_id", nullable = false, length = 255)
  public String releaseRequestId;

  @Column(name = "fold_sha", nullable = false, length = 64)
  public String foldSha;

  @Column(name = "automation_kind", nullable = false, length = 64)
  public String automationKind;

  @Column(nullable = false, length = 255)
  public String repository;

  /** {@code NOT_APPLICABLE}. */
  @Column(nullable = false, length = 32)
  public String state;

  /** Why, in a sentence. */
  @Column(columnDefinition = "text")
  public String reason;

  @Column(name = "decided_at", nullable = false)
  public Instant decidedAt;
}

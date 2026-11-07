package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One kind QA checks that a trigger answered DEFERRED at one fold (V30, qits-1920): no run, no
 * {@link MtBump} row — only the fact that the kind was deferred there, so the read door lists it.
 *
 * <p>Unique on {@code (request_id, fold_sha, automation_kind)} and written {@code on conflict do
 * nothing}, because qits-projects asks about the same fold again until its answer is final. A
 * {@link MtBump} row of the same kind at the same fold — the re-run door's — is what the fold reads
 * as instead.
 */
@Entity
@Table(name = "mt_automation_deferral")
public class MtAutomationDeferral extends PanacheEntityBase {

  @Id public UUID id;

  /** The release request, as {@link MtBump#releaseRequestId} holds it. */
  @Column(name = "request_id", nullable = false, length = 255)
  public String requestId;

  /** The fold the kind was deferred at. */
  @Column(name = "fold_sha", nullable = false, length = 64)
  public String foldSha;

  /** The kind's wire name, as {@link MtBump#automationKind} holds it. */
  @Column(name = "automation_kind", nullable = false, length = 64)
  public String automationKind;

  /** The sentence the trigger answered with. */
  @Column(columnDefinition = "text")
  public String detail;

  /** When the first trigger deferred it. */
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}

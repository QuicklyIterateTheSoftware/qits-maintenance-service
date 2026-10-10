package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What this service remembers about one release request (V21, qits-1133): whether it opened it,
 * why, and how often an upstream release restarted its pre-run. See {@code
 * V21__release_request_memory.sql} for why each column exists.
 */
@Entity
@Table(name = "mt_release_request")
public class MtReleaseRequest extends PanacheEntityBase {

  /** A group bump's release ask opened it ({@code maintenance/<group>}). Internal pins only. */
  public static final String GROUP_BUMP = "GROUP_BUMP";

  /**
   * The dispatcher's upstream path opened it (switch on): main-only and LOWEST, for its pre-run to
   * write the bump. The ONLY origin in which {@code dependency-bump} plans EXTERNAL upgrades.
   */
  public static final String MAIN_ONLY = "MAIN_ONLY";

  @Id
  @Column(name = "request_id", length = 64)
  public String requestId;

  @Column(nullable = false, length = 255)
  public String repository;

  /** Whether this service opened the request. */
  @Column(nullable = false)
  public boolean opened;

  /** {@link #GROUP_BUMP} or {@link #MAIN_ONLY}; null on a request this service did not open. */
  @Column(length = 32)
  public String purpose;

  /** The branch the request was opened for. */
  @Column(length = 255)
  public String branch;

  /** The pending changes a MAIN_ONLY request was opened for, a JSON array. */
  @Column(columnDefinition = "text")
  public String changes;

  @Column(name = "opened_at")
  public Instant openedAt;

  /** Upstream-driven restarts since the request's last QA verdict. */
  @Column(name = "upstream_restarts", nullable = false)
  public int upstreamRestarts;

  @Column(name = "upstream_restarted_at")
  public Instant upstreamRestartedAt;

  /** When this service withdrew it, or null. */
  @Column(name = "withdrawn_at")
  public Instant withdrawnAt;

  @Column(name = "withdrawn_reason", columnDefinition = "text")
  public String withdrawnReason;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}

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

  /** A group bump's release ask opened it. */
  public static final String GROUP_BUMP = "GROUP_BUMP";

  /** The upstream hook opened it, main-only and LOWEST, for its pre-run to write the bump. */
  public static final String UPSTREAM = "UPSTREAM";

  @Id
  @Column(name = "request_id", length = 64)
  public String requestId;

  @Column(nullable = false, length = 255)
  public String repository;

  /** Whether this service opened the request. */
  @Column(nullable = false)
  public boolean opened;

  /** {@link #GROUP_BUMP} or {@link #UPSTREAM}; null on a request this service did not open. */
  @Column(length = 32)
  public String purpose;

  /** The branch the request was opened for. */
  @Column(length = 255)
  public String branch;

  /** The pending changes an UPSTREAM request was opened for, a JSON array. */
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

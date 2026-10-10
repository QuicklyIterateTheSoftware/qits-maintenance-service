package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One release of one repository, as the tree it was cut from declares itself.
 *
 * <p><b>The second evidence kind of an adoption, beside {@link MtArtifact}.</b> An artifact row and
 * its components say what a release CONTAINS; this row and its {@link MtReleasePin}s say what it
 * DECLARED. Both are about a release rather than a working tree — an SBOM because a published
 * version is immutable, this because a tag is — and neither answers the other's question: a
 * document sees transitives no manifest names, and a manifest names the submodule and the frontend
 * bundle that no service's document ever lists.
 *
 * <p><b>{@link #repository} is the CATALOG NAME, always.</b> Both writers are held to it — {@code
 * bus/ScmEventListener} takes {@code SCMRelease.repositoryName} and {@code
 * work/ReleaseLedgerBackfill} takes a gitlink {@code mt_latest.name}, which is a repository name by
 * construction — so a reader compares this column with a closure entry directly, with none of the
 * id↔name translation {@code mt_artifact.repository} needs.
 *
 * <p><b>{@link #sha} is what makes the gitlink hop provable.</b> An embedder pins a submodule at a
 * commit and never at a version, so the only way to say which release of a frontend a service is
 * carrying is to look the pinned commit up among the frontend's own releases — which is this
 * column, compared through {@code latest/GitlinkSha.same}.
 */
@Entity
@Table(name = "mt_release")
public class MtRelease extends PanacheEntityBase {

  @Id public UUID id;

  /** The catalog name of the repository that released. Never another context's spelling. */
  @Column(nullable = false, length = 255)
  public String repository;

  /** The released version — the calver the tag {@code refs/tags/<version>} is named after. */
  @Column(nullable = false, length = 255)
  public String version;

  /** The commit that tag resolved to, full 40-hex as everything here writes it. */
  @Column(nullable = false, length = 64)
  public String sha;

  /**
   * When the release happened — the publisher's moment where there is a frame to take one from,
   * and the {@code mt_latest.checked_at} of the row being filled in where the backfill has none.
   */
  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  /**
   * Whether this release's commit is on main — an ancestor of, or equal to, main's head at a main
   * scan. Until then the GC keeps what the release declared (V24). Set by a main scan, never unset.
   */
  @Column(name = "on_main", nullable = false)
  public boolean onMain;
}

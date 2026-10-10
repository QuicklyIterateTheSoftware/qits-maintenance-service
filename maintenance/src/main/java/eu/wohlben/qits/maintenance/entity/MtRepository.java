package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One repository of the catalog, as the last scan found it.
 *
 * <p>Panache active-record with public fields, the platform's entity idiom.
 *
 * <p><b>The name is the key.</b> Every read this service makes is name-addressed — the catalog
 * answers names, the git host is asked by name, the CI payload carries a name — so a surrogate id
 * would only be a second identity to keep in step.
 */
@Entity
@Table(name = "mt_repository")
public class MtRepository extends PanacheEntityBase {

  @Id
  @Column(nullable = false, length = 255)
  public String name;

  /** The project the git host serves it under — half of the clone coordinate. */
  @Column(length = 255)
  public String project;

  /**
   * The catalog row's own id, as qits-projects answers it. <b>Not an identity here</b> — the name
   * above is that, and every read is addressed by it — but the column that speaks ANOTHER context's
   * spelling of this repository. Two things need it:
   *
   * <ul>
   *   <li>qits-ci's {@code SoftwareRelease} names the repository by this id, so {@code
   *       mt_artifact.repository} would otherwise hold a uuid where the whole read side joins on a
   *       name;
   *   <li><b>the release ask is addressed with it.</b> {@code POST
   *       /projects/api/repositories/<repoId>/release-requests} resolves its path parameter against
   *       qits-projects' own repository table, so this — not the name, not the project — is what
   *       opens a release request. See {@code bump/ReleaseRequestClient}.
   * </ul>
   *
   * <p>Null for a row the catalog listed without one, and for every row not yet re-scanned since V5.
   * A bump on such a row records a refusal rather than retrying: the next scan fills the column, and
   * the next bump asks with it.
   */
  @Column(name = "catalog_id", length = 64)
  public String catalogId;

  /**
   * What kind of thing this repository is, as qits-projects classifies it — SERVICE, DAEMON,
   * LIBRARY, FRONTEND, CLI, IMAGE, PROJECT, SERVICE_TEMPLATE, FORK. <b>Cached here so the release
   * trains never ask per repository</b>: placement is decided over the whole inventory at once, and
   * a remote read per row would be fifty HTTP calls on a path that already walks fifty rows.
   *
   * <p><b>The raw string the catalog answered, unvalidated.</b> The column has no check constraint
   * and this field is not typed as {@link eu.wohlben.qits.maintenance.model.RepositoryArchetype},
   * because the vocabulary belongs to qits-projects and grows when they grow: a word this service
   * has not heard of must cost a repository its train placement, never its inventory row. {@code
   * RepositoryArchetype.of} is the lenient parse, and it belongs where the decision is taken.
   *
   * <p><b>Rewritten by every scan, including back to null</b> — unlike {@link #catalogId}, which is
   * never cleared. The two differ because they are different KINDS of fact: the id is a translation
   * that other rows still need after the catalog stops answering it, while the archetype is a live
   * classification whose current value is the only one worth holding. A repository re-classified
   * from LIBRARY to SERVICE over there must not stay a LIBRARY here, and there is nothing in this
   * database that a stale archetype would rescue.
   */
  @Column(columnDefinition = "text")
  public String archetype;

  /** The branch a scan reads and a bump branches from — the payload's {@code baseRef}. */
  @Column(name = "main_branch", length = 255)
  public String mainBranch;

  /** When the last scan finished, whatever it found. Null means never scanned. */
  @Column(name = "last_scan_at")
  public Instant lastScanAt;

  /**
   * The commit every pin below was read at. ONE per scan: a repository's manifests are read at one
   * revision, so the inventory is a snapshot of a tree rather than a mixture of moments.
   */
  @Column(name = "head_sha", length = 64)
  public String headSha;

  /** {@code RepositoryStatus}'s names: OK, ABSENT, UNREACHABLE or CONFIG_ERROR. */
  @Column(nullable = false, length = 32)
  public String status;

  /** Why the status is not OK, for the UI to show. Null when it is. */
  @Column(columnDefinition = "text")
  public String message;

  /** {@link #mainBranch}, or {@code main} for a row that carries none. */
  public String mainBranchOrDefault() {
    return mainBranch == null || mainBranch.isBlank() ? "main" : mainBranch;
  }
}

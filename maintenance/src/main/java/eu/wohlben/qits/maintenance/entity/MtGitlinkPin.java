package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * <b>An INTERNAL npm pin a repository reaches through one of its gitlinks</b> — the lockfile of the
 * submodule at the commit the gitlink records, which is what that repository's build will {@code npm
 * ci}.
 *
 * <p><b>Not an {@link MtPin}, and never merged into one.</b> {@code mt_pin} says what a bump EDITS,
 * and the carrying repository has no line for this: the line is in the submodule's tree, and what
 * moves it is the gitlink bump. These rows exist for one reader — {@code GET /pins}, the artifact
 * GC's keep-set — and are served there with a {@code via} of {@code gitlink:<path>@<sha>}. See
 * {@code V17__gitlink_npm_pins.sql}.
 *
 * <p>Replaced per repository in the transaction that replaces its {@code mt_pin} rows.
 */
@Entity
@Table(name = "mt_gitlink_pin")
public class MtGitlinkPin extends PanacheEntityBase {

  @Id public UUID id;

  /** The repository CARRYING the gitlink. */
  @Column(nullable = false, length = 255)
  public String repository;

  /** Where the gitlink sits in that repository. */
  @Column(name = "gitlink_path", nullable = false, length = 1024)
  public String gitlinkPath;

  /** The submodule's repository name. */
  @Column(nullable = false, length = 255)
  public String submodule;

  /**
   * The commit these rows were READ at — the gitlink's commit, except where the tree at a newer one
   * could not be read and the previous rows were kept.
   */
  @Column(nullable = false, length = 64)
  public String sha;

  /** {@code Ecosystem}'s wire name; {@code npm}. */
  @Column(nullable = false, length = 32)
  public String ecosystem;

  @Column(nullable = false, length = 512)
  public String name;

  /** The lock's resolved version, which is what an install fetches. */
  @Column(nullable = false, length = 255)
  public String version;

  /** The submodule's manifest, prefixed by the gitlink path. */
  @Column(name = "manifest_path", nullable = false, length = 1024)
  public String manifestPath;
}

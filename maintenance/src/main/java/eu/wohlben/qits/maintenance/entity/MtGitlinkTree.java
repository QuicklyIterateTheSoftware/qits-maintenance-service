package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>One submodule tree, read once.</b> The npm pins the root lock of {@link #submodule} resolved
 * at {@link #sha} — written the first time a scan meets a gitlink at that commit, and never again:
 * a commit's tree does not change, so a gitlink that has not moved costs no read. See {@code
 * V17__gitlink_npm_pins.sql}.
 *
 * <p><b>Every npm pin, not only the internal ones.</b> Which are internal is configuration, applied
 * when a carrying repository's {@link MtGitlinkPin} rows are written, so this cache never has to be
 * invalidated when the scope list changes. A row with {@code []} is a real answer: the tree pins
 * nothing, and is not read again either.
 */
@Entity
@Table(name = "mt_gitlink_tree")
public class MtGitlinkTree extends PanacheEntityBase {

  @Id public UUID id;

  /** The submodule's repository name — a gitlink pin's {@code name}, and a catalog name. */
  @Column(nullable = false, length = 255)
  public String submodule;

  /** The commit the tree was read at. */
  @Column(nullable = false, length = 64)
  public String sha;

  /** The npm pins as a json array of {@code {name, version, manifestPath}}, read whole. */
  @Column(nullable = false, columnDefinition = "text")
  public String pins;

  @Column(name = "read_at", nullable = false)
  public Instant readAt;
}

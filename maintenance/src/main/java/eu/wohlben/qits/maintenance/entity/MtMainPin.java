package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One pin a repository's MAIN declared at its last main scan, as {@code GET /pins} serves it.
 *
 * <p>Apart from {@link MtPin} because a release scan replaces that table with the TAG's pins, and a
 * tag can run ahead of main and of the deployment. See V24, and V33 for the EXTERNAL docker pins
 * kept beside the INTERNAL ones.
 */
@Entity
@Table(name = "mt_main_pin")
public class MtMainPin extends PanacheEntityBase {

  @Id public UUID id;

  @Column(nullable = false, length = 255)
  public String repository;

  /** The main commit the pin was read at. */
  @Column(nullable = false, length = 64)
  public String sha;

  @Column(nullable = false, length = 32)
  public String ecosystem;

  @Column(nullable = false, length = 512)
  public String name;

  @Column(nullable = false, length = 255)
  public String version;

  @Column(name = "manifest_path", nullable = false, length = 1024)
  public String manifestPath;

  @Column(length = 1024)
  public String via;

  /**
   * {@code PinKind}'s names: INTERNAL, or EXTERNAL for a {@code docker} pin (V33, qits-2253). {@code
   * GET /pins} serves the INTERNAL rows and {@code GET /pins/images} the EXTERNAL ones.
   */
  @Column(nullable = false, length = 32)
  public String kind;
}

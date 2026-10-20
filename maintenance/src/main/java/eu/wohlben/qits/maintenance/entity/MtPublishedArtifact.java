package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One artifact a {@code SoftwareRelease} announced, and whether it has been ADOPTED into {@code
 * mt_latest} yet (V27, qits-1472).
 *
 * <p>Published is not usable: an artifact is adopted only once a {@link MtFinalizedRelease} of the
 * same {@link #repository} at or above its {@link #version} exists. See {@code
 * MaintenanceStore.recordPublished} and {@code MaintenanceStore.adoptFinalized}, the two halves of
 * that join, and {@code latest/AdoptionGate}, which reads these rows as the package → repository
 * map the registry poll is gated through.
 */
@Entity
@Table(name = "mt_published_artifact")
public class MtPublishedArtifact extends PanacheEntityBase {

  @Id public UUID id;

  /** qits-projects' repository row id, verbatim off the frame. Never a catalog name. */
  @Column(nullable = false, length = 255)
  public String repository;

  /** {@code Ecosystem.wireName}: maven, npm or docker. */
  @Column(nullable = false, length = 32)
  public String ecosystem;

  /** {@code mt_pin}'s spelling of the package. */
  @Column(nullable = false, length = 512)
  public String name;

  @Column(nullable = false, length = 255)
  public String version;

  /** The publisher's moment of the announcement. */
  @Column(name = "published_at", nullable = false)
  public Instant publishedAt;

  /** When it was handed to the forward-only latest writer; null while nothing finalized it. */
  @Column(name = "adopted_at")
  public Instant adoptedAt;
}

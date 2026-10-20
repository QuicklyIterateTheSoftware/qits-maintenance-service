package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One release request that FINALIZED, as qits-projects announced it (V27, qits-1472): the green
 * light for the rest of the estate to use what that release published.
 *
 * <p><b>{@link #repository} is qits-projects' repository ROW ID, verbatim</b> — {@code
 * ReleaseRequestStateChanged.repoId}, the same uuid {@code SoftwareRelease.repository} carries —
 * and never a catalog name, so it joins {@link MtPublishedArtifact#repository} without a
 * translation that could differ between the two arrivals.
 *
 * <p>Unique on {@code (repository, version)} and written {@code on conflict do nothing}, because
 * FINALIZED is often announced twice under two event ids.
 */
@Entity
@Table(name = "mt_finalized_release")
public class MtFinalizedRelease extends PanacheEntityBase {

  @Id public UUID id;

  /** qits-projects' repository row id. */
  @Column(nullable = false, length = 255)
  public String repository;

  /** The version the request released and finalized. */
  @Column(nullable = false, length = 255)
  public String version;

  /** The publisher's moment. */
  @Column(name = "finalized_at", nullable = false)
  public Instant finalizedAt;
}

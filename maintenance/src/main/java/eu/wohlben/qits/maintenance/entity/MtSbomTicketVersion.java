package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One version reported on an {@link MtSbomTicket} (V14) — which is what makes a version reported
 * at most once.
 */
@Entity
@Table(name = "mt_sbom_ticket_version")
@IdClass(MtSbomTicketVersion.Key.class)
public class MtSbomTicketVersion extends PanacheEntityBase {

  @Id
  @Column(name = "ticket_row", nullable = false)
  public UUID ticketRow;

  @Id
  @Column(nullable = false, length = 255)
  public String version;

  /** MISSING, FAILED or PENDING — what it was reported for. */
  @Column(nullable = false, length = 16)
  public String reason;

  @Column(name = "reported_at", nullable = false)
  public Instant reportedAt;

  /** The composite key: a ticket row and a version. */
  public static class Key implements Serializable {

    public UUID ticketRow;
    public String version;

    public Key() {}

    public Key(UUID ticketRow, String version) {
      this.ticketRow = ticketRow;
      this.version = version;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(ticketRow, key.ticketRow)
          && Objects.equals(version, key.version);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ticketRow, version);
    }
  }
}

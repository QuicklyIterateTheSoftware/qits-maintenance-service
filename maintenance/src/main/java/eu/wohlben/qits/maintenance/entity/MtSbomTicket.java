package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The CURRENT SBOM-check ticket of one {@code (project, ecosystem, name)} (V14).
 *
 * <p><b>The version is not in the key</b>: one artifact with several affected versions is one
 * ticket, the later versions added as comments ({@link MtSbomTicketVersion}). When a closed ticket
 * is replaced the row is overwritten in place — a new {@link #ticketId}, {@link #closedAt} nulled,
 * its versions deleted — so the unique key always names the one ticket the check is talking on.
 *
 * <p>{@link #closedAt} is set when the check is done with the ticket: it dropped it itself, found it
 * already DONE, DROPPED or gone, or found it retyped away from MAINTENANCE — in which case the
 * ticket is told and left open in qits-projects, and closed HERE so it is never commented on twice.
 */
@Entity
@Table(name = "mt_sbom_ticket")
public class MtSbomTicket extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "project_id", nullable = false, length = 64)
  public String projectId;

  @Column(nullable = false, length = 32)
  public String ecosystem;

  @Column(nullable = false, length = 512)
  public String name;

  /** qits-projects' entity id of the ticket. */
  @Column(name = "ticket_id", nullable = false)
  public UUID ticketId;

  /** The ticket's qualified id ({@code <project-slug>-<n>}), or its slug when none was answered. */
  @Column(name = "ticket_slug", length = 64)
  public String ticketSlug;

  @Column(name = "opened_at", nullable = false)
  public Instant openedAt;

  @Column(name = "closed_at")
  public Instant closedAt;
}

package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One RELEASED artifact whose bill of materials this service holds, or is waiting for.
 *
 * <p><b>An artifact is not a pin and this table is not an inventory.</b> {@code mt_pin} records
 * what a manifest EDITS — a line, a property, a location — and this one records what a released
 * package CONTAINS. They meet on {@code (ecosystem, name)} at read time and nowhere else: an SBOM
 * cannot name a pom property, and a pin cannot see a transitive.
 *
 * <p><b>The row is the outbox.</b> A {@code SoftwareRelease} frame writes it PENDING and returns;
 * the document is fetched afterwards, on the worker thread, outside the transaction that claimed
 * the event.
 *
 * <p><b>CORRECTION to {@code V3__sbom_graph.sql}, whose {@code ecosystem} comment says a {@code
 * daemon} release is "never a row here — nothing in any manifest pins them".</b> That was true when
 * it was written and is not true now: the qits CLI's version is a pom pin — qits-ci pins {@code
 * eu.wohlben.qits:qits-platform-access-cli-binary}, whose version IS the {@code daemons}-store
 * coordinate of the binary the same release published — so a daemon release DOES leave a row, and
 * the GC's keep-set is derived from it (see {@code control/CarriedDaemons}). The column is {@code
 * varchar(32)} with no check constraint and has always been able to hold the word; the correction
 * is carried here rather than in the migration because an applied migration's checksum is the one
 * thing that must never move — editing a comment in one refuses boot and rolls the deploy back.
 * {@code docs} remains as the comment describes it: nothing pins an api-docs bundle.
 *
 * <p><b>A daemon row is an outbox like any other</b>: written PENDING, its document fetched from
 * the {@code daemon} segment of the SBOM route, and its components joined into the graph, so a
 * library's dependents include the binaries that carry it. It used to be written terminal FAILED
 * because the route was then addressed through an {@code Ecosystem}; {@code SbomClient} addresses
 * a row by this stored word now, and V13 re-queued the rows that rule wrote.
 */
@Entity
@Table(name = "mt_artifact")
public class MtArtifact extends PanacheEntityBase {

  @Id public UUID id;

  /**
   * {@code Ecosystem}'s wire name — the same vocabulary {@code mt_pin} uses, because it joins — or
   * {@code Ecosystem.DAEMON_WIRE_NAME}, which is a released artifact word and not an ecosystem. It
   * is also the SBOM route's type segment for every one of the four, which is how a row is fetched.
   * Read it through {@code Ecosystem.of} where a pin or a latest is the subject — it answers empty
   * for a daemon — and compare the string where a daemon is the subject.
   */
  @Column(nullable = false, length = 32)
  public String ecosystem;

  /** The unqualified package name, spelled as {@code mt_pin} spells it. */
  @Column(nullable = false, length = 512)
  public String name;

  @Column(nullable = false, length = 255)
  public String version;

  /**
   * The repository that produced it, as {@code SoftwareRelease.repository} spells it. A string with
   * no foreign key — it is another context's fact, and it may name a repository this inventory has
   * never scanned.
   */
  @Column(length = 255)
  public String repository;

  /** The publisher's moment, off the event frame rather than this service's clock. */
  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  /** {@code SbomStatus}'s names. */
  @Column(name = "sbom_status", nullable = false, length = 32)
  public String sbomStatus;

  /** Why the status is FAILED. Null otherwise. */
  @Column(name = "sbom_error", columnDefinition = "text")
  public String sbomError;

  /** When the document was last read successfully. Null until one was. */
  @Column(name = "ingested_at")
  public Instant ingestedAt;
}

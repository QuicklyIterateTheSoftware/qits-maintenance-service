package eu.wohlben.qits.maintenance.sbom;

import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * A released artifact's bill of materials: fetched, read, and stored as the graph.
 *
 * <p><b>The row is the outbox and the fetch never happens inside the claim.</b> A {@code
 * SoftwareRelease} frame writes an {@code mt_artifact} row PENDING and returns — the durable
 * consumer's transaction closes with a row and no HTTP call in it. The document is fetched
 * afterwards, on the one worker thread, exactly the way a bump is dispatched. A listener that
 * fetched inline would hold a bus claim open across another service's call, and a qits-artifacts
 * that was slow would turn one release into an event redelivered for ever.
 *
 * <p><b>404 is MISSING and there is NO retry loop.</b> The SBOM route is newer than most of what
 * this platform has released, so most coordinates have no document — and a released version is
 * immutable, so asking again tomorrow asks about the same bytes. What supplies the answer is the
 * NEXT release of that artifact, which brings its own row; and a person who knows a document has
 * since been stored asks for one by hand through {@code POST /artifacts/ingest}.
 *
 * <p><b>A failure is FAILED with the sentence, not a throw.</b> One artifact's unreadable document
 * costs that artifact's row and nothing else, the same rule every other outbound read here follows.
 *
 * <p><b>NOTHING IS EVALUATED BEHIND A SUCCESSFUL INGEST ANY MORE, and that is a deliberate
 * subtraction.</b> A stored graph used to be pushed straight into the release-train evaluator, one
 * line after {@code replaceGraph} committed, because a train was a set of rows somebody had to move.
 * The trains are gone: {@code adoption/AdoptionEvaluator} reads these same rows at query time, so a
 * document that lands is simply a document that lands, and every question asked afterwards gets the
 * better answer for nothing. It also takes a whole failure mode away — there is no longer a
 * swallowed evaluation behind a write that succeeded.
 */
@ApplicationScoped
public class SbomIngestService {

  private static final Logger LOG = Logger.getLogger(SbomIngestService.class);

  @Inject MaintenanceStore store;

  @Inject SbomClient client;

  @Inject WorkQueue queue;

  /**
   * What an announced release leaves behind: a PENDING row, and a nudge to the queue.
   *
   * <p><b>Idempotent by construction.</b> {@code upsertArtifact} leaves a coordinate it already
   * knows exactly as it was — status, error and graph — so a redelivered frame is a read and a
   * return, and {@link #ingest} then finds a row that is not PENDING and does nothing.
   *
   * @return the artifact row's id
   */
  public UUID announced(
      Ecosystem ecosystem, String name, String version, String repository, Instant occurredAt) {
    UUID id = store.upsertArtifact(ecosystem, name, version, repository, occurredAt);
    // Outside the transaction above, which has committed. The queue is what takes the call.
    queue.submit("ingest the sbom of " + name + " " + version, () -> ingest(id));
    return id;
  }

  /**
   * <b>What an announced DAEMON release leaves behind: the same PENDING row and the same queued
   * fetch as any other artifact.</b>
   *
   * <p>A daemon binary is not an {@link Ecosystem} and never will be — {@code
   * Ecosystem.DAEMON_WIRE_NAME} says why — so the row carries the literal word rather than an enum
   * value. It is written for two reasons now. The first is the GC: the qits CLI's version is a pom
   * pin, and the keep-set is derived from exactly these rows (see {@code control/CarriedDaemons}).
   * The second is the document: qits-artifacts stores a bill of materials for every released
   * daemon binary under the {@code daemon} segment of the SBOM route, and {@link SbomClient}
   * addresses a row by its stored type, so a daemon's components and edges join the graph like any
   * other artifact's — a library's dependents include the binaries that carry it.
   *
   * <p><b>This used to write the row terminal FAILED and queue nothing</b>, because the route was
   * addressed through an {@link Ecosystem} and a daemon could not be named. V13 re-queues every row
   * that rule wrote.
   *
   * @return the artifact row's id
   */
  public UUID announcedDaemon(
      String name, String version, String repository, Instant occurredAt) {
    UUID id = store.upsertDaemonArtifact(name, version, repository, occurredAt);
    queue.submit("ingest the sbom of " + name + " " + version, () -> ingest(id));
    return id;
  }

  /**
   * The manual backfill: create the row or put it back to PENDING, whatever it said before, and
   * queue it.
   *
   * <p>This is the only thing that moves a MISSING or FAILED row, and it exists because both are
   * terminal by design.
   */
  public UUID requeue(
      Ecosystem ecosystem, String name, String version, String repository, Instant now) {
    return requeue(ecosystem.wireName(), name, version, repository, now);
  }

  /**
   * The same, keyed by the artifact's wire type — which is how a {@code daemon} row, not an {@link
   * Ecosystem}, is re-read by hand.
   *
   * @param type one of {@link SbomClient#TYPES}; the caller refuses anything else
   */
  public UUID requeue(
      String type, String name, String version, String repository, Instant now) {
    UUID id = store.requeueArtifact(type, name, version, repository, now);
    queue.submit("re-ingest the sbom of " + name + " " + version, () -> ingest(id));
    return id;
  }

  /**
   * One artifact, start to finish, on the worker thread.
   *
   * <p><b>A row that is not PENDING is already answered.</b> That is what makes a re-queued sweep
   * and a redelivered frame harmless: the second attempt reads the row, finds it INGESTED or
   * MISSING, and returns.
   */
  public void ingest(UUID artifactId) {
    Optional<MtArtifact> found = store.artifact(artifactId);
    if (found.isEmpty()) {
      return;
    }
    MtArtifact artifact = found.get();
    if (SbomStatus.of(artifact.sbomStatus) != SbomStatus.PENDING) {
      return;
    }
    // RESOLVED BY THE STORED WIRE TYPE, not by Ecosystem.of: a daemon row is not an ecosystem and is
    // addressed all the same, because the route is keyed by the released artifact's type.
    if (!SbomClient.addressable(artifact.ecosystem)) {
      // A row written by a build that knew another artifact type. It is recorded and not asked
      // about, which is what every unknown word in this schema gets.
      store.markArtifactFailed(
          artifactId, "'" + artifact.ecosystem + "' is not an ecosystem this build can address");
      return;
    }

    SbomClient.SbomAnswer answer;
    try {
      answer = client.fetch(artifact.ecosystem, artifact.name, artifact.version);
    } catch (RuntimeException e) {
      // The client answers rather than throws; this is the belt, so one surprise cannot leave a row
      // PENDING for ever with nothing saying why.
      store.markArtifactFailed(artifactId, "the sbom could not be read: " + e);
      return;
    }
    switch (answer.outcome()) {
      case MISSING -> {
        store.markArtifactMissing(artifactId);
        LOG.debugf(
            "qits-artifacts holds no sbom for %s %s %s; nothing retries it",
            artifact.ecosystem, artifact.name, artifact.version);
      }
      case FAILED -> {
        store.markArtifactFailed(artifactId, answer.reason());
        LOG.warnf(
            "The sbom of %s %s could not be read: %s",
            artifact.name, artifact.version, answer.reason());
      }
      case FOUND -> {
        ParsedSbom parsed = CycloneDxParser.parse(answer.document());
        // The whole graph in one transaction, and the row is INGESTED by the same write.
        store.replaceGraph(artifactId, parsed.components(), parsed.edges(), Instant.now());
        long direct = parsed.components().stream().filter(ParsedSbom.Component::direct).count();
        LOG.infof(
            "Ingested the sbom of %s %s: %d components (%d direct), %d edges%s",
            artifact.name,
            artifact.version,
            parsed.components().size(),
            direct,
            parsed.edges().size(),
            parsed.problems().isEmpty() ? "" : " — " + String.join("; ", parsed.problems()));
      }
    }
  }

  /**
   * Re-queues every row still PENDING.
   *
   * <p><b>The restart recovery, and it is a re-queue rather than a repair.</b> A PENDING row is a
   * fetch that was queued and never ran — a process that died between the frame and the call, or a
   * queue that was shut down mid-work. Unlike a scan, there is nothing in-process to lose: the work
   * is one idempotent read of an immutable document, so a successor simply does it. Unlike a bump,
   * nothing is running elsewhere that has to be followed.
   */
  public void sweep() {
    List<MtArtifact> pending = store.pendingArtifacts();
    for (MtArtifact artifact : pending) {
      UUID id = artifact.id;
      queue.submit("ingest the sbom of " + artifact.name + " " + artifact.version, () -> ingest(id));
    }
    if (!pending.isEmpty()) {
      LOG.infof("Queued %d artifact(s) whose sbom has not been read yet.", pending.size());
    }
  }
}

package eu.wohlben.qits.maintenance.sbomcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;

/**
 * The SBOM check's four calls into qits-projects' {@code /work} API (epic qits-965, qits-974) —
 * file a ticket, read one, comment on one, drop one — on {@link PeerTarget#PROJECTS}, the address
 * the catalog read and the release ask already use.
 *
 * <p>The routes and body shapes are qits-projects' own ({@code service/…/entities/api/WorkEntityDoors}
 * and its {@code /work} controllers, which replaced the old archetype-addressed {@code /entities}
 * family qits-974 moved this client off): {@code POST /projects/api/work} with the archetype plus
 * the ticket create schema (answers 201 with the entity in the merged shape — {@code id}, {@code
 * qualifiedId}, {@code slug}); {@code GET /projects/api/work/{qualifiedId}} ({@code status}, {@code
 * ticketType}); {@code POST …/{qualifiedId}/comments} {@code {"body": …}}; {@code POST
 * …/{qualifiedId}/status} {@code {"target": "DROPPED"}}. qits-projects names the path segment
 * {@code qualifiedId} but resolves either a qualified id ({@code qits-703}) or a UUID there.
 *
 * <p><b>Addressed by qualified id where one is known, a UUID otherwise — no schema migration.</b>
 * {@code mt_sbom_ticket.ticket_id} stays the entity UUID {@link #file} answers ({@code NOT NULL},
 * and dropping it would be a migration); {@code ticket_slug} already held the qualified id ({@link
 * Filed#slug}) whenever qits-projects answered one, with no column to add for it. So {@link
 * SbomCheckService} resolves whichever of the two a row holds — preferring the slug — into a single
 * reference string, and {@link #read}, {@link #comment} and {@link #drop} take that reference
 * rather than requiring the UUID they used to: either addresses the same {@code /work} door.
 *
 * <p><b>Every failure THROWS here, unlike the rest of this service's peer reads.</b> The caller is
 * one ticket decision of the SBOM check, which catches per group and reports the sentence: a write
 * that may or may not have happened must not be mistaken for one that did, and nothing about a
 * ticket call is retried in place.
 *
 * <p><b>ROLLOUT NOTE, measured against qits-projects main on 2026-10-02:</b> all four entities doors
 * ({@code POST /projects/api/entities}, {@code GET …/{id}}, {@code POST …/{id}/comments}, {@code
 * POST …/{id}/status}) admitted {@code qits:system}, which this service presents on every call —
 * settled before {@code qits.maintenance.sbom.check.file-tickets} shipped on. Their {@code /work}
 * replacements (qits-974) carry the same role list ({@code qits:admin}, {@code qits:agent}, {@code
 * qits:system}) per qits-projects' openapi document.
 */
@ApplicationScoped
public class TicketClient {

  static final String WORK = "/projects/api/work";

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject PeerClient peers;

  /** A ticket as filed: its entity id, and the qualified id ({@code qits-703}) or slug to show. */
  public record Filed(UUID id, String slug) {}

  /** What one ticket is now: its lifecycle status and its type. */
  public record TicketState(String status, String ticketType) {

    /** DONE or DROPPED — somebody has finished with it. */
    public boolean closed() {
      return "DONE".equals(status) || "DROPPED".equals(status);
    }

    /** Still the platform's to close: typed MAINTENANCE. */
    public boolean maintenance() {
      return "MAINTENANCE".equals(ticketType);
    }
  }

  /** A ticket call that did not do what was asked. */
  public static class TicketCallFailed extends RuntimeException {
    public TicketCallFailed(String message) {
      super(message);
    }
  }

  /** Files a MAINTENANCE ticket in one project. */
  public Filed file(String projectId, String title, String impetus, String description) {
    ObjectNode body = JSON.createObjectNode();
    body.put("archetype", "TICKET");
    body.put("project", projectId);
    body.put("ticketType", "MAINTENANCE");
    body.put("title", title);
    body.put("impetus", impetus);
    body.put("description", description);
    PeerExchange exchange = peers.post(PeerTarget.PROJECTS, WORK, body.toString());
    PeerAnswer answer = exchange.answer();
    if (!answer.ok()) {
      throw failed("file a ticket in project " + projectId, exchange);
    }
    JsonNode entity = answer.json();
    String id = text(entity, "id");
    if (id == null) {
      throw new TicketCallFailed(
          "qits-projects filed a ticket in " + projectId + " and answered no id: "
              + brief(answer.body()));
    }
    String slug = text(entity, "qualifiedId");
    if (slug == null) {
      slug = text(entity, "slug");
    }
    return new Filed(UUID.fromString(id), slug == null ? null : cap(slug, 64));
  }

  /**
   * One ticket's status and type; EMPTY when qits-projects has no such entity any more.
   *
   * @param ticketRef the ticket's qualified id or its entity UUID, as text — either resolves
   */
  public Optional<TicketState> read(String ticketRef) {
    PeerExchange exchange = peers.get(PeerTarget.PROJECTS, WORK + "/" + ticketRef);
    PeerAnswer answer = exchange.answer();
    if (answer.notFound()) {
      return Optional.empty();
    }
    if (!answer.ok()) {
      throw failed("read ticket " + ticketRef, exchange);
    }
    return Optional.of(
        new TicketState(text(answer.json(), "status"), text(answer.json(), "ticketType")));
  }

  /** Adds a comment to a ticket's thread. {@code ticketRef}: see {@link #read}. */
  public void comment(String ticketRef, String text) {
    ObjectNode body = JSON.createObjectNode();
    body.put("body", text);
    PeerExchange exchange =
        peers.post(PeerTarget.PROJECTS, WORK + "/" + ticketRef + "/comments", body.toString());
    if (!exchange.answer().ok()) {
      throw failed("comment on ticket " + ticketRef, exchange);
    }
  }

  /** Moves a ticket to DROPPED. {@code ticketRef}: see {@link #read}. */
  public void drop(String ticketRef) {
    ObjectNode body = JSON.createObjectNode();
    body.put("target", "DROPPED");
    PeerExchange exchange =
        peers.post(PeerTarget.PROJECTS, WORK + "/" + ticketRef + "/status", body.toString());
    if (!exchange.answer().ok()) {
      throw failed("drop ticket " + ticketRef, exchange);
    }
  }

  private static TicketCallFailed failed(String what, PeerExchange exchange) {
    PeerAnswer answer = exchange.answer();
    return new TicketCallFailed(
        "could not "
            + what
            + ": "
            + exchange.call().method()
            + " "
            + exchange.call().url()
            + " answered "
            + answer.failure()
            + (answer.body() == null || answer.body().isBlank() ? "" : " " + brief(answer.body())));
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
  }

  private static String brief(String body) {
    if (body == null) {
      return "";
    }
    String flat = body.replaceAll("\\s+", " ").trim();
    return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
  }

  private static String cap(String value, int limit) {
    return value.length() <= limit ? value : value.substring(0, limit);
  }
}

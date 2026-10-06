package eu.wohlben.qits.maintenance.sbomcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerCall;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The four calls into qits-projects' {@code /work} API, as the bodies and paths its controllers
 * take (qits-974, epic qits-965). */
class TicketClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final class Stub extends PeerClient {
    final List<PeerCall> calls = new ArrayList<>();
    PeerAnswer next = new PeerAnswer(200, "{}", JSON.createObjectNode(), Map.of(), null);

    @Override
    public String url(PeerTarget target, String path) {
      return "http://qits-projects:8080" + path;
    }

    @Override
    public PeerExchange get(PeerTarget target, String path) {
      PeerCall call = new PeerCall("GET", url(target, path), null);
      calls.add(call);
      return new PeerExchange(call, next);
    }

    @Override
    public PeerExchange post(PeerTarget target, String path, String body) {
      PeerCall call = new PeerCall("POST", url(target, path), body);
      calls.add(call);
      return new PeerExchange(call, next);
    }

    void answer(int status, String body) throws Exception {
      next = new PeerAnswer(status, body, JSON.readTree(body), Map.of(), null);
    }
  }

  @Test
  void aTicketIsFiledAsAMaintenanceTicketAndItsQualifiedIdIsKept() throws Exception {
    Stub peers = new Stub();
    TicketClient client = new TicketClient();
    client.peers = peers;
    UUID id = UUID.randomUUID();
    peers.answer(201, "{\"id\":\"" + id + "\",\"qualifiedId\":\"qits-901\",\"slug\":\"sbom\"}");

    TicketClient.Filed filed = client.file("qits", "SBOM missing for x (maven)", "why", "body");

    assertEquals(new TicketClient.Filed(id, "qits-901"), filed);
    PeerCall call = peers.calls.get(0);
    assertEquals("http://qits-projects:8080/projects/api/work", call.url());
    JsonNode body = JSON.readTree(call.body());
    assertEquals("TICKET", body.get("archetype").asText());
    assertEquals("qits", body.get("project").asText());
    assertEquals("MAINTENANCE", body.get("ticketType").asText());
    assertEquals("SBOM missing for x (maven)", body.get("title").asText());
    assertEquals("why", body.get("impetus").asText());
    assertEquals("body", body.get("description").asText());
  }

  @Test
  void readCommentAndDropUseTheWorkDoorsByQualifiedId() throws Exception {
    Stub peers = new Stub();
    TicketClient client = new TicketClient();
    client.peers = peers;
    String ref = "qits-901";

    peers.answer(200, "{\"status\":\"REFINED\",\"ticketType\":\"MAINTENANCE\"}");
    TicketClient.TicketState state = client.read(ref).orElseThrow();
    assertEquals("REFINED", state.status());
    assertTrue(state.maintenance());

    peers.answer(200, "{}");
    client.comment(ref, "hello");
    client.drop(ref);
    assertEquals(
        "http://qits-projects:8080/projects/api/work/" + ref, peers.calls.get(0).url());
    assertEquals(
        "http://qits-projects:8080/projects/api/work/" + ref + "/comments",
        peers.calls.get(1).url());
    assertEquals("hello", JSON.readTree(peers.calls.get(1).body()).get("body").asText());
    assertEquals(
        "http://qits-projects:8080/projects/api/work/" + ref + "/status",
        peers.calls.get(2).url());
    assertEquals("DROPPED", JSON.readTree(peers.calls.get(2).body()).get("target").asText());

    peers.answer(404, "{}");
    assertTrue(client.read(ref).isEmpty(), "a ticket that is gone reads as empty");
    peers.answer(403, "{\"message\":\"no\"}");
    assertThrows(TicketClient.TicketCallFailed.class, () -> client.comment(ref, "x"));
  }

  /** A row that never got a qualified id back still resolves — a bare UUID works on {@code /work}
   * too (qits-965). */
  @Test
  void readCommentAndDropAlsoWorkByBareUuid() throws Exception {
    Stub peers = new Stub();
    TicketClient client = new TicketClient();
    client.peers = peers;
    UUID id = UUID.randomUUID();

    peers.answer(200, "{\"status\":\"REPORTED\",\"ticketType\":\"MAINTENANCE\"}");
    client.read(id.toString());
    assertEquals(
        "http://qits-projects:8080/projects/api/work/" + id, peers.calls.get(0).url());
  }
}

package eu.wohlben.qits.maintenance.sbomcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters;
import eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters.Trigger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>What qits-maintenance asks qits-projects, and why</b> (epic qits-965, qits-974) — the one
 * table both {@code ProjectsConsumerPactTest} (each row against a pact mock server) and {@code
 * ProjectsPactFileTest} (the committed {@code
 * pacts/qits-maintenance-service_qits-projects-service.json}) are built from, so the file and the
 * verified behaviour cannot drift apart. Modeled on qits-workspaces-service's class of the same
 * name and purpose.
 *
 * <p><b>One row per (trigger, call).</b> {@link TicketClient}'s four doors are every one the daily
 * SBOM check ({@link SbomCheckService}) presses, traced down to the two methods that press them —
 * which is this consumer's own trigger vocabulary, there being no REST {@code operationId} on the
 * cron and the manual {@code POST /sbom-check/runs} door answering to the identical code path:
 *
 * <ul>
 *   <li>{@code SbomCheckService.file} — a GROUP with a usable SBOM still missing: files a new
 *       ticket ({@link #FILE}, {@code createWork}), or — a still-open ticket already carrying the
 *       group — reads it to confirm it is still open ({@link #READ}, {@code getWork}) and adds the
 *       new version as a comment ({@link #COMMENT}, {@code addWorkComment});
 *   <li>{@code SbomCheckService.closeIfResolved} — a group with nothing left to report: reads the
 *       ticket ({@link #READ} again, {@code getWork}), drops it ({@link #DROP}, {@code
 *       setWorkStatus}) and comments why ({@link #CLOSE_COMMENT}, {@code addWorkComment}).
 * </ul>
 *
 * <p><b>Every request body is this consumer's OWN expectation</b>, built from exactly what {@link
 * TicketClient} sends for the literal arguments each {@code Call} passes it — never copied from the
 * golden master's recorded request, which is qits-projects' own test fixture and not a promise
 * about what a caller must send. Only the recorded RESPONSE is read off the golden master (see
 * {@link GoldenMasters#interaction}).
 *
 * <p><b>{@code setWorkStatus}'s row targets DROPPED even though no golden master records that
 * target</b> (every recorded {@code setWorkStatus} moves to some OTHER status — REFINED,
 * IMPLEMENTING, DONE, and so on). That is fine: {@link TicketClient#drop} never reads the response
 * body, only whether the call answered 2xx, so any recorded {@code setWorkStatus} 200 — here, "a
 * reported ticket"'s move to REFINED — proves the shape this client actually depends on, and the
 * request body it sends is this row's own, not a copy of what that state recorded.
 */
final class ProjectsContract {

  static final String A_PROJECT_WITH_NO_WORK = "a project with no work";
  static final String A_MAINTENANCE_TICKET_IN_DETAIL = "a maintenance ticket in detail";
  static final String A_TICKET_WITH_A_COMMENT = "a ticket with a comment";
  static final String A_REPORTED_TICKET = "a reported ticket";

  static final String CREATE_WORK = "createWork";
  static final String GET_WORK = "getWork";
  static final String ADD_WORK_COMMENT = "addWorkComment";
  static final String SET_WORK_STATUS = "setWorkStatus";

  private static final String TITLE = "SBOM missing for left-pad (npm)";
  private static final String IMPETUS =
      "left-pad 1.0.0 is in qits-artifacts with no usable SBOM (MISSING).";
  private static final String DESCRIPTION =
      "qits-maintenance's daily SBOM check found a software artifact in qits-artifacts without a"
          + " usable SBOM.";
  private static final String COMMENT_TEXT =
      "Also without a usable SBOM: `1.0.1` (published 2026-10-01T00:00:00Z) — MISSING.";
  private static final String CLOSING_TEXT =
      "Resolved: every version listed here now has an ingested SBOM or is no longer in"
          + " qits-artifacts (1.0.0: INGESTED). Closed by qits-maintenance's SBOM check.";

  /** What the consumer does with the client for one row, asserting what that code path reads. */
  @FunctionalInterface
  interface Call {
    void run(TicketClient client, Map<String, String> params);
  }

  /**
   * One (trigger, call), plus the request body this row's call sends (null for a GET), plus — for
   * the rare response field that is not the golden master's to assert because it just echoes back
   * what THIS request carried ({@code responseOverrides}, empty for every row but {@code
   * createWork}'s; see {@link GoldenMasters#interaction(PactBuilder, String, String, Trigger,
   * DslPart, Map)}).
   */
  record Case(
      Trigger trigger,
      String state,
      String operationId,
      Supplier<DslPart> requestBody,
      Call call,
      Map<String, JsonNode> responseOverrides) {

    Case(Trigger trigger, String state, String operationId, Supplier<DslPart> requestBody, Call call) {
      this(trigger, state, operationId, requestBody, call, Map.of());
    }

    String description() {
      return GoldenMasters.description(operationId, trigger);
    }
  }

  /** {@code POST /work}: a brand-new MAINTENANCE ticket. */
  private static final Call FILE =
      (client, params) -> {
        TicketClient.Filed filed =
            client.file(params.get("projectId"), TITLE, IMPETUS, DESCRIPTION);
        JsonNode recorded = GoldenMasters.json(A_PROJECT_WITH_NO_WORK, CREATE_WORK);
        assertEquals(UUID.fromString(recorded.path("id").asText()), filed.id());
        assertEquals(recorded.path("qualifiedId").asText(), filed.slug());
      };

  /** {@code GET /work/{qualifiedId}}: the status and type the group's open ticket now carries. */
  private static final Call READ =
      (client, params) -> {
        TicketClient.TicketState state = client.read(params.get("qualifiedId")).orElseThrow();
        JsonNode recorded = GoldenMasters.json(A_MAINTENANCE_TICKET_IN_DETAIL, GET_WORK);
        assertEquals(recorded.path("status").asText(), state.status());
        assertEquals(recorded.path("ticketType").asText(), state.ticketType());
        assertTrue(state.maintenance(), "a MAINTENANCE ticket is still this check's to close");
      };

  /** {@code POST /work/{qualifiedId}/comments}: a later version added to an open ticket. */
  private static final Call COMMENT =
      (client, params) -> client.comment(params.get("qualifiedId"), COMMENT_TEXT);

  /** {@code POST /work/{qualifiedId}/comments}: the sentence left when a ticket is dropped. */
  private static final Call CLOSE_COMMENT =
      (client, params) -> client.comment(params.get("qualifiedId"), CLOSING_TEXT);

  /** {@code POST /work/{qualifiedId}/status}: every version resolved, so the ticket closes. */
  private static final Call DROP = (client, params) -> client.drop(params.get("qualifiedId"));

  private static final Supplier<DslPart> CREATE_WORK_REQUEST =
      () ->
          new PactDslJsonBody()
              .stringValue("archetype", "TICKET")
              .stringType("project", "00000000-0000-4000-8000-000000000001")
              .stringValue("ticketType", "MAINTENANCE")
              .stringValue("title", TITLE)
              .stringValue("impetus", IMPETUS)
              .stringValue("description", DESCRIPTION);

  private static final Supplier<DslPart> COMMENT_REQUEST =
      () -> new PactDslJsonBody().stringValue("body", COMMENT_TEXT);

  private static final Supplier<DslPart> CLOSE_COMMENT_REQUEST =
      () -> new PactDslJsonBody().stringValue("body", CLOSING_TEXT);

  private static final Supplier<DslPart> DROP_REQUEST =
      () -> new PactDslJsonBody().stringValue("target", "DROPPED");

  static final List<Case> CASES =
      List.of(
          new Case(
              Trigger.schedule("SbomCheckService.file"),
              A_PROJECT_WITH_NO_WORK,
              CREATE_WORK,
              CREATE_WORK_REQUEST,
              FILE,
              // qits-projects' recorder never sent a description filing this state, so the golden
              // master's own answer holds null there; TicketClient.file always sends one and
              // qits-projects echoes it back verbatim (TicketApiTest's own
              // "ticket.description" assertion), so this response field is this consumer's own
              // expectation, the same way the request body above is.
              Map.of("description", new TextNode(DESCRIPTION))),
          new Case(
              Trigger.schedule("SbomCheckService.file"),
              A_MAINTENANCE_TICKET_IN_DETAIL,
              GET_WORK,
              null,
              READ),
          new Case(
              Trigger.schedule("SbomCheckService.file"),
              A_TICKET_WITH_A_COMMENT,
              ADD_WORK_COMMENT,
              COMMENT_REQUEST,
              COMMENT),
          new Case(
              Trigger.schedule("SbomCheckService.closeIfResolved"),
              A_MAINTENANCE_TICKET_IN_DETAIL,
              GET_WORK,
              null,
              READ),
          new Case(
              Trigger.schedule("SbomCheckService.closeIfResolved"),
              A_REPORTED_TICKET,
              SET_WORK_STATUS,
              DROP_REQUEST,
              DROP),
          new Case(
              Trigger.schedule("SbomCheckService.closeIfResolved"),
              A_TICKET_WITH_A_COMMENT,
              ADD_WORK_COMMENT,
              CLOSE_COMMENT_REQUEST,
              CLOSE_COMMENT));

  private ProjectsContract() {}

  /** The whole contract as one V4 pact, interactions in table order (the file test sorts). */
  static V4Pact pact() {
    return pact(CASES);
  }

  /** A pact holding only {@code cases} — one mock server per row, see the pact test. */
  static V4Pact pact(List<Case> cases) {
    PactBuilder builder =
        new PactBuilder(GoldenMasters.CONSUMER, GoldenMasters.PROVIDER, PactSpecVersion.V4);
    for (Case c : cases) {
      DslPart requestBody = c.requestBody() == null ? null : c.requestBody().get();
      GoldenMasters.interaction(
          builder, c.state(), c.operationId(), c.trigger(), requestBody, c.responseOverrides());
    }
    return builder.toPact();
  }
}

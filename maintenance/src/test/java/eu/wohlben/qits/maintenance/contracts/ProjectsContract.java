package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.sbomcheck.TicketClient;
import eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters;
import eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters.Trigger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>What qits-maintenance asks qits-projects, and why</b> (epics qits-965, qits-546; tickets
 * qits-974, qits-1149) — the one table both {@code ProjectsConsumerPactTest} (each row against a
 * pact mock server) and {@code ProjectsPactFileTest} (the committed {@code
 * pacts/qits-maintenance-service_qits-projects-service.json}) are built from, so the file and the
 * verified behaviour cannot drift apart.
 *
 * <p><b>One row per (trigger, call).</b> The trigger names the scheduled method (or the bus event)
 * whose path makes the call — this consumer's own trigger vocabulary:
 *
 * <ul>
 *   <li>{@code SbomCheckService.file} / {@code .closeIfResolved} — the daily SBOM check's ticket
 *       doors ({@link TicketClient}): file, read, comment, drop;
 *   <li>{@code BumpService.sweep} — a finished bump: {@link ReleaseRequestClient#state} (has the
 *       fold moved?) and {@link ReleaseRequestClient#withdraw} (nothing left to bump);
 *   <li>{@code BumpDispatcher.tick} — {@link ReleaseRequestClient#openRequests}, to find a request
 *       to join;
 *   <li>the {@code triggerReleaseRequestAutomations} and {@code runReleaseRequestAutomation} doors —
 *       {@link ReleaseRequestClient#state}, for the branch the request is folded onto (qits-1158);
 *       the re-run also for its state and fold;
 *   <li>{@code AutomationBranchSweep.sweep} — {@link ReleaseRequestClient#state}, to tell a closed
 *       request's automation branch from a live one;
 *   <li>the {@code SoftwareRelease} and {@code SCMRelease} events — {@code UpstreamReplan}'s
 *       {@link ReleaseRequestClient#openRequests}.
 * </ul>
 *
 * <p><b>Each row binds only what its client reads</b> ({@code consumes}, see {@link
 * GoldenMasters#interaction(PactBuilder, String, String, Trigger, DslPart, List)}); a row whose
 * client reads only the status consumes nothing. <b>Every request body is this consumer's OWN
 * expectation</b>, built from what the client sends for the literal arguments the row passes it —
 * never the golden master's recorded request.
 *
 * <p><b>{@code setWorkStatus}'s row targets DROPPED although no golden master records that
 * target.</b> {@link TicketClient#drop} reads only the status, so any recorded {@code
 * setWorkStatus} 200 proves what it depends on.
 *
 * <p><b>{@code request.conflict} is not bound.</b> {@link ReleaseRequestClient#state} reads it, as
 * the object qits-projects answers, only when {@code detail} is null; no recorded state answers a
 * conflict without a {@code detail}, so no row reaches that read.
 *
 * <p>The calls qits-maintenance makes with no golden master to bind yet are in {@link
 * PendingContract}.
 */
final class ProjectsContract {

  static final String A_PROJECT_WITH_NO_WORK = "a project with no work";
  static final String A_MAINTENANCE_TICKET_IN_DETAIL = "a maintenance ticket in detail";
  static final String A_TICKET_WITH_A_COMMENT = "a ticket with a comment";
  static final String A_REPORTED_TICKET = "a reported ticket";
  static final String A_RELEASE_REQUEST_AWAITING_APPROVAL = "a release request awaiting approval";
  static final String A_WITHDRAWN_RELEASE_REQUEST = "a withdrawn release request";

  static final String CREATE_WORK = "createWork";
  static final String GET_WORK = "getWork";
  static final String ADD_WORK_COMMENT = "addWorkComment";
  static final String SET_WORK_STATUS = "setWorkStatus";
  static final String GET_RELEASE_REQUEST = "getReleaseRequest";
  static final String LIST_REPOSITORY_RELEASE_REQUESTS = "listRepositoryReleaseRequests";
  static final String WITHDRAW_RELEASE_REQUEST = "withdrawReleaseRequest";

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
  private static final String WITHDRAW_REASON =
      "nothing is left to bump: every pin the request carried is current";

  /** {@code createWork}: the new ticket's id and qualified id. ({@code slug} is read only when
   * {@code qualifiedId} is missing, which qits-projects never answers.) */
  static final List<String> FILED = List.of("id", "qualifiedId");

  /** {@code getWork}: whether the ticket is still open, and still a MAINTENANCE one. */
  static final List<String> TICKET_STATE = List.of("status", "ticketType");

  /**
   * {@link ReleaseRequestClient#state}: the request's state, why, its fold, the branch it is folded
   * onto, its branches. ({@code qualifiedId} is read too, and is not bound: no pinned golden master
   * records it yet, and an absent one reads as none.)
   */
  static final List<String> RELEASE_STATE =
      List.of(
          "request.state",
          "request.detail",
          "request.mergedSha",
          "request.backingBranch",
          "request.sources[].kind",
          "request.sources[].name");

  /** {@link ReleaseRequestClient#openRequests}: each request's id, state, fold and CI gate. */
  static final List<String> OPEN_REQUESTS =
      List.of(
          "requests[].id",
          "requests[].state",
          "requests[].mergedSha",
          "requests[].gates[kind=CI].kind",
          "requests[].gates[kind=CI].state");

  /** The status alone. */
  static final List<String> STATUS_ONLY = List.of();

  /** What the consumer does with its clients for one row, asserting what that code path reads. */
  @FunctionalInterface
  interface Call {
    void run(Clients clients, Map<String, String> params);
  }

  /** One (trigger, call): the state, the operation, the request body (null for a GET), the body
   * paths the client reads, and the call itself. */
  record Case(
      Trigger trigger,
      String state,
      String operationId,
      Supplier<DslPart> requestBody,
      List<String> consumes,
      Call call) {

    String description() {
      return GoldenMasters.description(operationId, trigger);
    }
  }

  /** {@code POST /work}: a brand-new MAINTENANCE ticket. */
  private static final Call FILE =
      (clients, params) -> {
        TicketClient.Filed filed =
            clients.tickets().file(params.get("projectId"), TITLE, IMPETUS, DESCRIPTION);
        JsonNode recorded = GoldenMasters.json(A_PROJECT_WITH_NO_WORK, CREATE_WORK);
        assertEquals(UUID.fromString(recorded.path("id").asText()), filed.id());
        assertEquals(recorded.path("qualifiedId").asText(), filed.slug());
      };

  /** {@code GET /work/{qualifiedId}}: the status and type the group's open ticket now carries. */
  private static final Call READ =
      (clients, params) -> {
        TicketClient.TicketState state =
            clients.tickets().read(params.get("qualifiedId")).orElseThrow();
        JsonNode recorded = GoldenMasters.json(A_MAINTENANCE_TICKET_IN_DETAIL, GET_WORK);
        assertEquals(recorded.path("status").asText(), state.status());
        assertEquals(recorded.path("ticketType").asText(), state.ticketType());
        assertTrue(state.maintenance(), "a MAINTENANCE ticket is still this check's to close");
      };

  /** {@code POST /work/{qualifiedId}/comments}: a later version added to an open ticket. */
  private static final Call COMMENT =
      (clients, params) -> clients.tickets().comment(params.get("qualifiedId"), COMMENT_TEXT);

  /** {@code POST /work/{qualifiedId}/comments}: the sentence left when a ticket is dropped. */
  private static final Call CLOSE_COMMENT =
      (clients, params) -> clients.tickets().comment(params.get("qualifiedId"), CLOSING_TEXT);

  /** {@code POST /work/{qualifiedId}/status}: every version resolved, so the ticket closes. */
  private static final Call DROP =
      (clients, params) -> clients.tickets().drop(params.get("qualifiedId"));

  /** {@code GET …/release-requests/{requestId}}: the request's state, fold and branch sources. */
  private static Call releaseState(String state) {
    return (clients, params) -> {
      ReleaseRequestClient.ReleaseState read =
          clients.releases().state(params.get("repositoryId"), params.get("requestId"));
      JsonNode recorded = GoldenMasters.json(state, GET_RELEASE_REQUEST).path("request");
      assertTrue(read.readable(), read.sentence());
      assertEquals(recorded.path("state").asText(), read.state());
      assertEquals(textOrNull(recorded.path("mergedSha")), read.mergedSha());
      assertEquals(textOrNull(recorded.path("backingBranch")), read.backingBranch());
      // The mock serves every element of an array as the one merged template, so the names
      // repeat; what the contract proves is that each BRANCH source comes back as a branch name.
      int sources = recorded.path("sources").size();
      assertEquals(sources, read.branches().size());
      read.branches().forEach(name -> assertFalse(name.isBlank()));
    };
  }

  /** {@code GET …/release-requests}: the repository's open requests and their CI gate. */
  private static final Call OPEN =
      (clients, params) -> {
        ReleaseRequestClient.Listing listing =
            clients.releases().openRequests(params.get("repositoryId"));
        assertTrue(listing.readable(), listing.error());
        JsonNode recorded =
            GoldenMasters.json(A_RELEASE_REQUEST_AWAITING_APPROVAL, LIST_REPOSITORY_RELEASE_REQUESTS)
                .path("requests")
                .path(0);
        assertEquals(1, listing.requests().size());
        ReleaseRequestClient.Listed listed = listing.requests().get(0);
        assertEquals(recorded.path("id").asText(), listed.id());
        assertEquals(recorded.path("state").asText(), listed.state());
        assertEquals(recorded.path("mergedSha").asText(), listed.mergedSha());
        assertEquals("PASSED", listed.ciGate());
      };

  /** {@code POST …/release-requests/{requestId}/withdraw}: a bump request with nothing left. */
  private static final Call WITHDRAW =
      (clients, params) -> {
        ReleaseRequestClient.RequestResult result =
            clients
                .releases()
                .withdraw(params.get("repositoryId"), params.get("requestId"), WITHDRAW_REASON);
        assertEquals(ReleaseRequestClient.RequestResult.Outcome.REQUESTED, result.outcome());
      };

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

  private static final Supplier<DslPart> WITHDRAW_REQUEST =
      () -> new PactDslJsonBody().stringValue("reason", WITHDRAW_REASON);

  static final List<Case> CASES =
      List.of(
          new Case(
              Trigger.schedule("SbomCheckService.file"),
              A_PROJECT_WITH_NO_WORK,
              CREATE_WORK,
              CREATE_WORK_REQUEST,
              FILED,
              FILE),
          new Case(
              Trigger.schedule("SbomCheckService.file"),
              A_MAINTENANCE_TICKET_IN_DETAIL,
              GET_WORK,
              null,
              TICKET_STATE,
              READ),
          new Case(
              Trigger.schedule("SbomCheckService.file"),
              A_TICKET_WITH_A_COMMENT,
              ADD_WORK_COMMENT,
              COMMENT_REQUEST,
              STATUS_ONLY,
              COMMENT),
          new Case(
              Trigger.schedule("SbomCheckService.closeIfResolved"),
              A_MAINTENANCE_TICKET_IN_DETAIL,
              GET_WORK,
              null,
              TICKET_STATE,
              READ),
          new Case(
              Trigger.schedule("SbomCheckService.closeIfResolved"),
              A_REPORTED_TICKET,
              SET_WORK_STATUS,
              DROP_REQUEST,
              STATUS_ONLY,
              DROP),
          new Case(
              Trigger.schedule("SbomCheckService.closeIfResolved"),
              A_TICKET_WITH_A_COMMENT,
              ADD_WORK_COMMENT,
              CLOSE_COMMENT_REQUEST,
              STATUS_ONLY,
              CLOSE_COMMENT),
          new Case(
              Trigger.schedule("BumpService.sweep"),
              A_RELEASE_REQUEST_AWAITING_APPROVAL,
              GET_RELEASE_REQUEST,
              null,
              RELEASE_STATE,
              releaseState(A_RELEASE_REQUEST_AWAITING_APPROVAL)),
          new Case(
              Trigger.schedule("BumpService.sweep"),
              A_RELEASE_REQUEST_AWAITING_APPROVAL,
              WITHDRAW_RELEASE_REQUEST,
              WITHDRAW_REQUEST,
              STATUS_ONLY,
              WITHDRAW),
          new Case(
              Trigger.operation("triggerReleaseRequestAutomations"),
              A_RELEASE_REQUEST_AWAITING_APPROVAL,
              GET_RELEASE_REQUEST,
              null,
              RELEASE_STATE,
              releaseState(A_RELEASE_REQUEST_AWAITING_APPROVAL)),
          new Case(
              Trigger.operation("runReleaseRequestAutomation"),
              A_RELEASE_REQUEST_AWAITING_APPROVAL,
              GET_RELEASE_REQUEST,
              null,
              RELEASE_STATE,
              releaseState(A_RELEASE_REQUEST_AWAITING_APPROVAL)),
          new Case(
              Trigger.schedule("AutomationBranchSweep.sweep"),
              A_WITHDRAWN_RELEASE_REQUEST,
              GET_RELEASE_REQUEST,
              null,
              RELEASE_STATE,
              releaseState(A_WITHDRAWN_RELEASE_REQUEST)),
          new Case(
              Trigger.schedule("BumpDispatcher.tick"),
              A_RELEASE_REQUEST_AWAITING_APPROVAL,
              LIST_REPOSITORY_RELEASE_REQUESTS,
              null,
              OPEN_REQUESTS,
              OPEN),
          new Case(
              Trigger.event("SoftwareRelease"),
              A_RELEASE_REQUEST_AWAITING_APPROVAL,
              LIST_REPOSITORY_RELEASE_REQUESTS,
              null,
              OPEN_REQUESTS,
              OPEN),
          new Case(
              Trigger.event("SCMRelease"),
              A_RELEASE_REQUEST_AWAITING_APPROVAL,
              LIST_REPOSITORY_RELEASE_REQUESTS,
              null,
              OPEN_REQUESTS,
              OPEN));

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
          builder, c.state(), c.operationId(), c.trigger(), requestBody, c.consumes());
    }
    return builder.toPact();
  }

  private static String textOrNull(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
  }
}

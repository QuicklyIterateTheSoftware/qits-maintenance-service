package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.automation.AutomationService;
import eu.wohlben.qits.maintenance.automation.SettleGate;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A release request's automations</b> (epic qits-978): the every-fold trigger qits-projects posts,
 * the read its gate sweeps, and the operator's re-run.
 *
 * <p>Served under {@code /maintenance/api/release-requests}. The request is qits-projects' and this
 * service holds nothing about it but the rows its automations wrote, so the noun is the request and
 * the repository travels in the body — the trigger names it, and a re-run may.
 *
 * <p><b>The same three roles as every door here</b>, {@code qits:admin}, {@code qits:system} and
 * {@code qits:agent}. The trigger is a machine's (qits-projects, on every fold) and the re-run a
 * person's or an agent's; what either can start is a regeneration of output the request already
 * owns, gated by the request's own CI and approval like any other commit on it. {@code
 * qits:admin-agent} is admitted too (qits-628 follow-up): an ADMIN workspace's coding agent carries
 * it alongside {@code qits:agent}, and for now it may use everything {@code qits:admin} may use.
 *
 * <p><b>{@code qits:desk} (qits-1596) is admitted only on the READ</b> ({@link
 * #automations(String, String)}), not on the trigger or the re-run. Those two are this service's
 * actual write surface: a {@code REQUESTED} kind dispatches a bump row through {@link
 * eu.wohlben.qits.maintenance.bump.CiClient}, which is what pushes a branch into the repository on
 * qits-ci's side. The desk is admitted everywhere the coordinator is admitted EXCEPT a door that
 * ends in a ref being written, and this is that door — the coordinator's own {@code qits:admin-agent}
 * stays, but {@code qits:desk} does not join it here. {@code qits:desk-admin} (qits-1621) is a
 * platform operator rather than the desk's own binding and reaches every door
 * {@code qits:admin-agent} does, the trigger and the re-run included — see the epic's "Inventory"
 * dossier page, which lists no refusal for this service.
 */
@Path("/release-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReleaseRequestAutomationController {

  @Inject AutomationService automations;

  /** The trigger's gate: one settle per request, off this thread, answered within a budget. */
  @Inject SettleGate settles;

  /**
   * One fold, as qits-projects posts it after every fold of a request.
   *
   * @param repository the repository, as the catalog spells it
   * @param foldSha the request's merged sha — the fold every answer is for
   * @param previousFoldSha the fold before it, or null when this is the first
   * @param changedSincePrevious every path that changed between the two, or null when there was no
   *     previous fold or the diff could not be read. Null never carries an outcome over
   * @param sourceBranches the request's named branches; main and the automations' own are ignored
   * @param workItem the work item a commit subject names, or null
   * @param accepts the answer words the caller understands beyond the old ones (qits-1133): {@code
   *     WAITING} and {@code NOT_APPLICABLE}, and {@code DEFERRED} (qits-1180). Absent, a waiting kind
   *     is answered REQUESTED, a kind that does not apply is left out and a kind QA checks is run,
   *     exactly as before
   * @param backingBranch the branch the request is folded onto (qits-1158): {@code
   *     release/<qualifiedId>} for a newer request. Absent, it is read from qits-projects, and
   *     {@code release/<requestId>} when that answers none
   * @param qualifiedId the request's logical id, {@code <repository>-rr-<n>} (qits-1158): what the
   *     sentences on its rows name. Absent, they name the request id
   */
  public record TriggerRequest(
      String repository,
      String foldSha,
      String previousFoldSha,
      List<String> changedSincePrevious,
      List<String> sourceBranches,
      String workItem,
      List<String> accepts,
      String backingBranch,
      String qualifiedId) {}

  /**
   * A re-run's body. Both optional.
   *
   * @param workItem the work item its commit subject names
   * @param repository the repository the request belongs to — needed only when no automation of
   *     the request has been asked about yet, which is the first-baselines case
   */
  public record RunRequest(String workItem, String repository) {}

  /**
   * <b>The every-fold trigger.</b> Settles every registered kind at this fold — applicability,
   * carry-over, the plan, a row or FRESH — and answers where each stands. Idempotent per (request,
   * fold): a repeat answers the stored rows, and only a kind answered UNKNOWN is decided again.
   *
   * <p>A kind that does not apply is not listed; a repository no kind applies to answers an empty
   * list. A kind that has to run answers REQUESTED, and its run is dispatched behind the scenes;
   * follow it with the read below. A DERIVED kind waits for every SOURCE kind to be FRESH at the
   * fold (qits-1133). With {@code accepts} naming them, a waiting kind answers WAITING and a kind
   * that does not apply is listed NOT_APPLICABLE with its {@code reason}. With DEFERRED named, a
   * kind the repository's QA checks at the fold (its {@code check-outputs:}) is answered DEFERRED and
   * not run (qits-1180, qits-1178); the re-run door still runs it.
   *
   * <p>A repository no scan has read yet — a new one's first request — is still a 404, but it queues
   * a scan of that repository (one at a time), so the next thirty-second ask finds it.
   *
   * <p><b>Answered within a budget, never after it</b> (qits-1258, {@link SettleGate}): the settle
   * runs on a pool of its own, at most one per request, and an ask for a fold being settled joins
   * it. One not done within {@code qits.maintenance.automations.settle.budget} (5 s) is answered at
   * once — the rows stored so far, every other kind UNKNOWN "settling: …" — and keeps running; the
   * next ask joins it or finds it done.
   */
  @POST
  @Path("/{requestId}/automations")
  @Operation(
      operationId = "triggerReleaseRequestAutomations",
      summary = "Settle a release request's automations at one fold")
  @APIResponse(
      responseCode = "200",
      description =
          "Every applicable kind's state at the fold; UNKNOWN \"settling\" for a kind still being"
              + " decided when the settle outlasts its budget")
  @APIResponse(responseCode = "400", description = "Not a request id, not a sha, or not a work item")
  @APIResponse(
      responseCode = "404",
      description = "No such repository in the inventory yet; a scan of it is queued")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
  public ReleaseRequestAutomationsDto trigger(
      @PathParam("requestId") String requestId, TriggerRequest request) {
    if (request == null) {
      throw new BadRequestException("the trigger names the repository and the fold");
    }
    return settles.settle(
        requestId == null ? null : requestId.trim(),
        new AutomationService.Fold(
            request.repository(),
            request.foldSha(),
            request.previousFoldSha(),
            request.changedSincePrevious(),
            request.sourceBranches(),
            request.workItem(),
            request.accepts(),
            request.backingBranch(),
            request.qualifiedId()));
  }

  /**
   * Where a request's automations stand at a fold — the newest fold anything was asked about when
   * {@code foldSha} is not given. A kind with no row at that fold is not listed; for qits-projects'
   * gate an absent kind is a hold and a reason to post the fold again.
   */
  @GET
  @Path("/{requestId}/automations")
  @Operation(
      operationId = "listReleaseRequestAutomations",
      summary = "A release request's automations at one fold")
  @APIResponse(responseCode = "200", description = "Every kind with a row at the fold")
  @APIResponse(responseCode = "400", description = "Not a request id, or not a sha")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk", "qits:system", "qits:agent"})
  public ReleaseRequestAutomationsDto automations(
      @PathParam("requestId") String requestId, @QueryParam("foldSha") String foldSha) {
    return automations.automations(requestId == null ? null : requestId.trim(), foldSha);
  }

  /**
   * <b>The manual re-run.</b> Runs one kind on the request's CURRENT fold now, skipping carry-over
   * and applicability — so a repository's first screenshot references come from here — and does not
   * wait. {@code GET /bumps/{id}} follows it.
   */
  @POST
  @Path("/{requestId}/automations/{kind}/runs")
  @Operation(
      operationId = "runReleaseRequestAutomation",
      summary = "Re-run one automation on a release request's current fold")
  @APIResponse(responseCode = "202", description = "Requested; poll GET /bumps/{id}")
  @APIResponse(responseCode = "400", description = "Not a request id, or not a work item")
  @APIResponse(responseCode = "404", description = "No such kind, or no such repository")
  @APIResponse(
      responseCode = "409",
      description =
          "The request takes no branch or has no fold, one is already active for (request, kind),"
              + " or bumping is disabled")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
  public Response run(
      @PathParam("requestId") String requestId,
      @PathParam("kind") String kind,
      RunRequest request) {
    UUID id =
        automations.run(
            request == null ? null : request.repository(),
            requestId == null ? null : requestId.trim(),
            kind,
            request == null ? null : request.workItem(),
            BumpTrigger.MANUAL);
    return Response.status(Response.Status.ACCEPTED)
        .entity(new RepositoryController.AcceptedResponse(id))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}

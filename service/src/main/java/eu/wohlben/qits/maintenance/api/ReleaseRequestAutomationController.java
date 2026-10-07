package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.automation.AutomationService;
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
 */
@Path("/release-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReleaseRequestAutomationController {

  @Inject AutomationService automations;

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
   */
  public record TriggerRequest(
      String repository,
      String foldSha,
      String previousFoldSha,
      List<String> changedSincePrevious,
      List<String> sourceBranches,
      String workItem) {}

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
   * follow it with the read below.
   */
  @POST
  @Path("/{requestId}/automations")
  @Operation(summary = "Settle a release request's automations at one fold")
  @APIResponse(responseCode = "200", description = "Every applicable kind's state at the fold")
  @APIResponse(responseCode = "400", description = "Not a request id, not a sha, or not a work item")
  @APIResponse(responseCode = "404", description = "No such repository in the inventory")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public ReleaseRequestAutomationsDto trigger(
      @PathParam("requestId") String requestId, TriggerRequest request) {
    if (request == null) {
      throw new BadRequestException("the trigger names the repository and the fold");
    }
    return automations.trigger(
        requestId == null ? null : requestId.trim(),
        new AutomationService.Fold(
            request.repository(),
            request.foldSha(),
            request.previousFoldSha(),
            request.changedSincePrevious(),
            request.sourceBranches(),
            request.workItem()));
  }

  /**
   * Where a request's automations stand at a fold — the newest fold anything was asked about when
   * {@code foldSha} is not given. A kind with no row at that fold is not listed; for qits-projects'
   * gate an absent kind is a hold and a reason to post the fold again.
   */
  @GET
  @Path("/{requestId}/automations")
  @Operation(summary = "A release request's automations at one fold")
  @APIResponse(responseCode = "200", description = "Every kind with a row at the fold")
  @APIResponse(responseCode = "400", description = "Not a request id, or not a sha")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
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
  @Operation(summary = "Re-run one automation on a release request's current fold")
  @APIResponse(responseCode = "202", description = "Requested; poll GET /bumps/{id}")
  @APIResponse(responseCode = "400", description = "Not a request id, or not a work item")
  @APIResponse(responseCode = "404", description = "No such kind, or no such repository")
  @APIResponse(
      responseCode = "409",
      description =
          "The request takes no branch or has no fold, one is already active for (request, kind),"
              + " or bumping is disabled")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
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

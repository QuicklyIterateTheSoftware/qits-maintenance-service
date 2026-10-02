package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.control.Adoption;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.DownstreamDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDependentsDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDetailDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDto;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.pending.Change;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The inventory: repositories, their pins, and the button that turns one group's pending changes
 * into a branch.
 *
 * <p>Served under {@code /maintenance/api/repositories} — the {@code /maintenance/api} prefix is
 * {@code quarkus.rest.path}, not spelled here, so this class carries only its own noun.
 *
 * <p><b>Every route accepts the same three roles</b>, {@code qits:admin} (a person, through the
 * gateway's forward-auth headers), {@code qits:system} (a machine, through a bearer validated
 * against qits-platform-idp) and {@code qits:agent} (a commissioned agent). A bump is asked for by
 * an operator in a browser and could as well be asked for by a machine or by the agent doing the
 * work; a machine-only guard would lock the operator out of the button this service exists to
 * offer, and an operator-only one would make an agent beg for a press that chooses nothing. There
 * is no anonymous route here.
 */
@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RepositoryController {

  @Inject Inventory inventory;

  @Inject BumpService bumps;

  /** What this repository's RELEASES contain, and who contains them. See {@link ArtifactGraph}. */
  @Inject ArtifactGraph graph;

  /** Who is downstream of it, traced to the end. See {@link Adoption}. */
  @Inject Adoption adoption;

  /** What a 202 answers with — the id to poll. */
  public record AcceptedResponse(UUID id) {}

  /**
   * The body of a TARGETED bump: whose branch, and what to write on it.
   *
   * <p><b>Both are stated and neither is derived, which is the whole shape of the mode.</b> The
   * group door beside it is given a group and works the rest out — the branch from the naming rule,
   * the changes from the inventory. There is nothing to work out here: the caller holds a release
   * request on a particular branch and wants a particular set of pins inside that request's fold,
   * and this service recomputing either of them would put a different commit on somebody else's
   * branch than the one that was asked for.
   *
   * <p><b>{@code changes} is the same {@link Change} record the payload carries</b>, not a reduced
   * one. The step edits by {@code manifestPath} and {@code location} and names the dependency in the
   * commit it writes; a body that carried only "this gitlink to that version" would have this
   * service invent the other three fields out of an inventory that may be a scan behind.
   *
   * @param branch the caller's branch, without {@code refs/heads/}
   * @param changes the pins to write; an empty list is accepted and ends NOTHING_TO_DO
   */
  public record TargetedBumpRequest(String branch, List<Change> changes) {}

  /**
   * The body of a screenshot-baselines update: the work item its commit subject names, optional.
   */
  public record BaselinesRequest(String workItem) {}

  @GET
  @Operation(summary = "Every repository in the inventory, with its groups and what is pending")
  @APIResponse(responseCode = "200", description = "The repositories")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  public List<RepositoryDto> repositories() {
    return inventory.repositories();
  }

  /**
   * One repository with every pin it holds, and the verdict on each.
   *
   * <p>404 when the inventory has no such row — which covers both "never scanned" and "not in the
   * catalog", because neither has anything to show.
   */
  @GET
  @jakarta.ws.rs.Path("/{name}")
  @Operation(summary = "One repository with every pin it holds")
  @APIResponse(responseCode = "200", description = "The repository")
  @APIResponse(responseCode = "404", description = "No such repository in the inventory")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  public RepositoryDetailDto repository(@PathParam("name") String name) {
    return inventory.repository(name);
  }

  /**
   * <b>Who depends on what this repository publishes</b> — the blast radius of its next release.
   *
   * <p>The mirror of {@code GET /repositories/{name}}: that one is what this repository depends ON,
   * read from its manifests; this one is who depends on IT, read from the bills of materials of
   * every artifact it has released. Grouped by artifact, because a repository publishing a jar, an
   * npm package and an image out of one reactor is the ordinary shape here and "which of my things
   * are they on" is the actual question.
   *
   * <p><b>No 404, and that is deliberate.</b> A repository that has released nothing this service
   * has a document for answers with an empty list — which is true — and one that is not in the
   * catalog at all answers the same way, because {@code mt_artifact.repository} is a string another
   * service owns and this route asks nothing of the inventory.
   */
  @GET
  @jakarta.ws.rs.Path("/{name}/dependents")
  @Operation(summary = "Who embeds the artifacts this repository publishes")
  @APIResponse(responseCode = "200", description = "The dependents, per published artifact")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  public RepositoryDependentsDto dependents(@PathParam("name") String name) {
    return graph.repositoryDependents(name);
  }

  /**
   * <b>Everything downstream of this repository, traced to the end</b> — the closure a build order
   * is derived from.
   *
   * <p>The transitive sibling of {@code /{name}/dependents}: that one answers who embeds what this
   * repository publishes, one hop, out of bills of materials alone. This one unions the DECLARED
   * side in — every INTERNAL {@code mt_pin} on a coordinate it publishes, <b>including the GITLINK
   * pin on its own name</b>, which is how a frontend reaches the service it is the {@code webui}
   * submodule of — and then keeps walking. The old release train could not: its membership was
   * derived once, one hop deep, so a library's journey stopped at the frontend and never named the
   * service behind it.
   *
   * <p><b>This is a wire contract</b> — qits-projects reads it on its release-request announce path
   * and qits-ci orders its build queue by what comes out. See {@link DownstreamDto}, and
   * {@code qits-maintenance-plan.md} in the qits-qits wrapper, where the shape is pinned.
   *
   * <p><b>{name} takes either spelling</b>, the catalog name or qits-projects' repository row id
   * (this inventory's {@code catalog_id}), because the caller addressing it is holding the latter.
   *
   * <p><b>No 404, and here that is load-bearing rather than tidy.</b> The caller is an announce
   * path: a repository this service has never scanned must cost that announce an empty list, never
   * a refusal it has to classify.
   */
  @GET
  @jakarta.ws.rs.Path("/{name}/downstream")
  @Operation(summary = "Everything downstream of this repository, ordered upstream first")
  @APIResponse(responseCode = "200", description = "The closure, depth ascending then name")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  public DownstreamDto downstream(@PathParam("name") String name) {
    return adoption.downstream(name);
  }

  /**
   * Asks for one group's pending changes to be put on its branch, and does NOT wait.
   *
   * <p><b>202 with the id.</b> A bump is a CI run in somebody else's pipeline — a clone, an edit, a
   * push — and an HTTP request is the wrong place to hold that. The id is what {@code GET
   * /bumps/{id}} takes.
   *
   * <p><b>409 while a bump of that (repository, group) is active</b>, and again when bumping is
   * switched off. Two runs writing one branch would make the second a non-ff rejection at best.
   *
   * <p><b>A group with nothing pending still answers 202</b> and the row ends NOTHING_TO_DO. The
   * inventory can be seconds out of date, so refusing here would be refusing on the strength of a
   * cache — and the honest answer is a row that says what the run found.
   *
   * <p><b>An agent presses it too, and that is not a loosening.</b> The caller names a group and
   * nothing else: what lands on the branch is whatever is already pending there, every change
   * resolving a version somebody has already released — so what this door grants is <i>when</i>, not
   * <i>what</i>, and the 409s above are still the whole of what keeps two runs off one branch.
   */
  @POST
  @jakarta.ws.rs.Path("/{name}/groups/{group}/bumps")
  @Operation(summary = "Put this group's pending changes on its maintenance branch")
  @APIResponse(responseCode = "202", description = "Requested; poll GET /bumps/{id}")
  @APIResponse(responseCode = "404", description = "No such repository, or no such group")
  @APIResponse(responseCode = "409", description = "One is already active, or bumping is disabled")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  public Response bump(@PathParam("name") String name, @PathParam("group") String group) {
    UUID id = bumps.request(name, group, BumpTrigger.MANUAL);
    return Response.status(Response.Status.ACCEPTED)
        .entity(new AcceptedResponse(id))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }

  /**
   * Asks for the named changes to be written onto a branch <b>the caller owns</b>, and does NOT
   * wait.
   *
   * <p><b>Its own noun rather than a variant of the group door.</b> {@code
   * /groups/{group}/bumps} is addressed by a thing this service knows about — a group it read out of
   * a repository's own configuration — and everything else about the bump follows from it. This one
   * is addressed by a branch this service has never heard of and will never hear of again: a
   * workspace branch qits-projects owns, carrying a release request whose fold wants its gitlink
   * pins inside it rather than banked by the release afterwards. Hanging that off the group path
   * would make the group segment a lie in half the requests.
   *
   * <p><b>202 with the id, for the reason the group door gives</b>: a bump is a clone, an edit and a
   * push in somebody else's pipeline. {@code GET /bumps/{id}} takes the id, and for this caller the
   * field that matters there is {@code resultSha} — <b>which commit now holds the pins</b>, so that
   * the request it is arming can be checked against a commit rather than against a hope.
   *
   * <p><b>409 while a bump onto THAT BRANCH is active, and never merely onto that repository.</b> One
   * repository may have several release requests open at once, each on its own branch, each entitled
   * to its own pins; the thing that cannot happen twice at once is two runs pushing one ref. Also
   * 409 when bumping is switched off, which stops this door exactly as it stops the schedule.
   *
   * <p><b>400 when the branch or a change is not something the bump step would accept</b> — the same
   * rules {@code BumpPayload} holds a group bump to, refused here rather than as a red run somebody
   * has to go and read a step log for. This door refuses synchronously where the group path records
   * the reason on the row, because there is a caller on the other end of this one.
   *
   * <p><b>{@code qits:agent} opens it, beside the operator and the machine</b>, because the caller
   * on the other end is usually an agent arming its own release request and the changes it names
   * choose nothing new — each one pins a version that is already released, onto a branch the caller
   * already owns and that CI still gates and a person still approves. The 409 above is unchanged,
   * and it is what stops two callers writing one ref.
   */
  @POST
  @jakarta.ws.rs.Path("/{name}/branches/bumps")
  @Operation(summary = "Write the named changes onto a branch the caller owns")
  @APIResponse(responseCode = "202", description = "Requested; poll GET /bumps/{id} for resultSha")
  @APIResponse(responseCode = "400", description = "The branch or a change is not a valid payload")
  @APIResponse(responseCode = "404", description = "No such repository in the inventory")
  @APIResponse(
      responseCode = "409",
      description = "One is already active on that branch, or bumping is disabled")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  public Response bumpBranch(@PathParam("name") String name, TargetedBumpRequest request) {
    if (request == null || request.branch() == null || request.branch().isBlank()) {
      throw new BadRequestException("a targeted bump names the branch it writes onto");
    }
    UUID id =
        bumps.requestTargeted(
            name, request.branch().trim(), request.changes(), BumpTrigger.MANUAL);
    return Response.status(Response.Status.ACCEPTED)
        .entity(new AcceptedResponse(id))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }

  /**
   * Renders one release request's screenshot tests in the CI image and joins the reference images
   * that changed to that request. Does NOT wait.
   *
   * <p>The run starts from the request's fold, writes every reference that is missing or differs
   * ({@code UPDATE_SNAPSHOT=all npm run test:browser}), commits only {@code __screenshots__/}
   * files onto {@code maintenance/baselines/<request>} and joins that branch to the request. Nothing
   * reaches {@code main} except through the request's own gates and approval. {@code GET
   * /bumps/{id}} follows it: SUCCEEDED (joined), NOTHING_TO_DO (unchanged) or FAILED with the reason.
   *
   * <p>The same three roles as every route here.
   */
  @POST
  @jakarta.ws.rs.Path("/{name}/release-requests/{requestId}/screenshot-baselines")
  @Operation(summary = "Update a release request's screenshot baselines in the CI image")
  @APIResponse(responseCode = "202", description = "Requested; poll GET /bumps/{id}")
  @APIResponse(responseCode = "400", description = "Not a request id, or not a work item")
  @APIResponse(responseCode = "404", description = "No such repository in the inventory")
  @APIResponse(
      responseCode = "409",
      description =
          "The request takes no branch, one is already running for it, or bumping is disabled")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  public Response updateBaselines(
      @PathParam("name") String name,
      @PathParam("requestId") String requestId,
      BaselinesRequest request) {
    UUID id =
        bumps.requestBaselines(
            name,
            requestId == null ? null : requestId.trim(),
            request == null ? null : request.workItem(),
            BumpTrigger.MANUAL);
    return Response.status(Response.Status.ACCEPTED)
        .entity(new AcceptedResponse(id))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}

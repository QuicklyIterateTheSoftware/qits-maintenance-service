package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.control.DeployedPactInteractions;
import eu.wohlben.qits.maintenance.control.DeployedPacts;
import eu.wohlben.qits.maintenance.dto.DeployedPactInteractionsDto;
import eu.wohlben.qits.maintenance.dto.DeployedPactsDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The consumer pacts a provider's release gate verifies</b> (qits-1765): the pact version every
 * deployed or rollback version of every consumer was built against. The computation and the
 * refusals are {@code DeployedPacts}'s; this does routing, roles and status codes.
 *
 * <p><b>{@code qits:ci-run} is admitted here and nowhere else in this service</b>: the gate is a CI
 * step, and a CI step carries the token qits-ci commissions for its run. That token is opaque; the
 * edge introspects it and forwards the JWT it stands for — audience {@code qits-platform}, groups
 * {@code [qits:ci-run]} — so this service's own OIDC tenant validates it like any other bearer and
 * the role arrives as an ordinary one. It is a read, and it says nothing a pact jar does not.
 *
 * <p><b>503 when qits-deployments cannot answer</b>, as {@code GET /pins} does: a gate told "no
 * consumer serves" when the truth is "nobody could ask" would skip the verification it exists for.
 *
 * <p><b>{@code /interactions}</b> (qits-2702) is the same rows with what each pact jar asks of the
 * provider, leaving out a consumer that no longer declares the pact; the computation and its 502 are
 * {@code DeployedPactInteractions}'s. Same roles: it says nothing the jars do not.
 */
@Path("/deployed-pacts")
@Produces(MediaType.APPLICATION_JSON)
public class DeployedPactsController {

  @Inject DeployedPacts pacts;

  @Inject DeployedPactInteractions interactions;

  @GET
  @Operation(
      operationId = "listDeployedPacts",
      summary = "The consumer pact versions a provider's deployed consumers were built against")
  @APIResponse(responseCode = "200", description = "The pacts; empty for an unknown provider")
  @APIResponse(responseCode = "400", description = "No provider named")
  @APIResponse(
      responseCode = "503",
      description = "The deployed versions could not be read from qits-deployments")
  @RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:desk",
    "qits:system",
    "qits:agent",
    "qits:ci-run"
  })
  public DeployedPactsDto deployedPacts(@QueryParam("provider") String provider) {
    return pacts.of(provider);
  }

  @GET
  @Path("/interactions")
  @Operation(
      operationId = "listDeployedPactInteractions",
      summary =
          "The interactions each deployed consumer's pact holds against a provider, consumers that"
              + " no longer declare the pact left out")
  @APIResponse(responseCode = "200", description = "The pacts; empty for an unknown provider")
  @APIResponse(responseCode = "400", description = "No provider named")
  @APIResponse(responseCode = "502", description = "A deployed consumer's pact jar could not be read")
  @APIResponse(
      responseCode = "503",
      description = "The deployed versions could not be read from qits-deployments")
  @RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:desk",
    "qits:system",
    "qits:agent",
    "qits:ci-run"
  })
  public DeployedPactInteractionsDto deployedPactInteractions(
      @QueryParam("provider") String provider) {
    return interactions.of(provider);
  }
}

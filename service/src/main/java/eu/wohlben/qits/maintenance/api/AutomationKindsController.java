package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.automation.AutomationService;
import eu.wohlben.qits.maintenance.dto.AutomationKindsDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The automation kinds catalogue</b> (epic qits-1038): every {@code ReleaseRequestAutomation}
 * this build registers and offers, with its label, the paths a run of it may commit and the roles
 * that may approve a fold it writes into.
 *
 * <p>qits-projects' {@code AutomationContentSource} reads this to open one approval requirement per
 * automation kind whose {@code committablePaths} a fold touches, admitting the roles the kind itself
 * declares — never a kind table qits-projects would otherwise have to keep in step by hand. It caches
 * the answer for five minutes; configured but unreachable with no cache fails closed on that side.
 *
 * <p><b>The same role pair as every other read door here.</b> It is a MACHINE route in practice —
 * qits-projects reads it with a bearer — and an operator asking the same question is no reason to
 * lock out.
 */
@Path("/automations")
@Produces(MediaType.APPLICATION_JSON)
public class AutomationKindsController {

  @Inject AutomationService automations;

  @GET
  @Path("/kinds")
  @Operation(
      operationId = "listAutomationKinds",
      summary = "Every release-request automation kind this build offers")
  @APIResponse(responseCode = "200", description = "Every registered, switched-on kind, by id")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk", "qits:system", "qits:agent"})
  public AutomationKindsDto kinds() {
    return automations.kindsDto();
  }
}

package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.PinSourceDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The dependency pins, as the artifact GC's third pin source.</b>
 *
 * <p>qits-artifacts collects the registry against a handful of sources read once per run — what the
 * running services deploy, what the images name, and what the platform's manifests still pin. Only
 * this service can answer the third: every repository knows its own manifests and nothing else knows
 * them all. The route serves the store as it stands and computes nothing; the shape, the filter and
 * the refusal are {@code Inventory.pins}'s, because a controller here does routing, roles and status
 * codes and nothing else.
 *
 * <p><b>It is a MACHINE route in practice and takes the same role pair as every other one here.</b>
 * qits-platform-orchestrator reads it with a bearer; an operator asking what the GC will be told is
 * the same question and there is no reason to lock them out of it.
 *
 * <p><b>503 when the inventory has never been filled</b>, and it is the one status worth stating on
 * a read route. See {@code error/EmptyInventoryException}: the consumer treats an unanswered source
 * as fail-closed and a successful answer as authoritative, so a store with no rows must refuse
 * instead of saying that nothing on the platform is referenced.
 */
@Path("/pins")
@Produces(MediaType.APPLICATION_JSON)
public class PinsController {

  @Inject Inventory inventory;

  @GET
  @Operation(summary = "Every internal artifact version the platform's manifests still pin")
  @APIResponse(responseCode = "200", description = "The pins, with the inventory's freshness")
  @APIResponse(responseCode = "503", description = "The inventory holds no repository at all")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public PinSourceDto pins() {
    return inventory.pins();
  }
}

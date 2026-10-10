package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.ScanDto;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.error.NoSuchScanException;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
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
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Re-read the manifests and refresh the latest versions — the button beside the two crons.
 *
 * <p><b>202 and no waiting.</b> A scan of the whole catalog is one git-host read per repository
 * plus a registry lookup per dependency; it is minutes, and an HTTP request is the wrong place to
 * hold it.
 *
 * <p><b>The id is a ROW, and {@code GET /scans/{id}} is how a client follows it.</b> A scan of the
 * whole catalog is one git-host read per repository and a registry lookup per dependency; a person
 * who pressed the button has to see that something is happening rather than guess from a listing
 * that has not changed yet.
 *
 * <p><b>No scan bumps, manual or scheduled.</b> Pressing Scan asks what is out of date; pressing
 * Bump asks for a branch, and the clock's own standing instruction lives in {@code BumpSchedule}.
 */
@Path("/scans")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScanController {

  @Inject ScanService scans;

  @Inject Inventory inventory;

  /**
   * The request body of a scan.
   *
   * @param scope INTERNAL, EXTERNAL or ALL; absent means ALL
   * @param repository one repository's name, or absent for the whole catalog
   */
  public record StartScanRequest(String scope, String repository) {

    /** What a 202 answers with. */
    public record Response(UUID id) {}
  }

  @POST
  @Operation(
      operationId = "requestScan",
      summary = "Re-scan the manifests and refresh the latest versions")
  @APIResponse(responseCode = "202", description = "Queued")
  @APIResponse(responseCode = "400", description = "Unknown scope")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system"})
  public Response scan(StartScanRequest request) {
    String requested = request == null ? null : request.scope();
    ScanScope scope =
        requested == null || requested.isBlank()
            ? ScanScope.ALL
            : ScanScope.of(requested)
                .orElseThrow(
                    () ->
                        new BadRequestException(
                            "scope must be INTERNAL, EXTERNAL or ALL, not '" + requested + "'"));
    String repository =
        request == null || request.repository() == null || request.repository().isBlank()
            ? null
            : request.repository().trim();
    UUID id = scans.request(scope, repository, ScanTrigger.MANUAL);
    return Response.status(Response.Status.ACCEPTED)
        .entity(new StartScanRequest.Response(id))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }

  /**
   * One scan: what it covers and how far it has got.
   *
   * <p>An id that is not a uuid is a 404 like any other unknown scan — a malformed id and an absent
   * one are the same question from the caller's side.
   */
  @GET
  @Path("/{id}")
  @Operation(operationId = "getScan", summary = "One scan and its status")
  @APIResponse(responseCode = "200", description = "The scan")
  @APIResponse(responseCode = "404", description = "No such scan")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public ScanDto scan(@PathParam("id") String id) {
    UUID scanId;
    try {
      scanId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new NoSuchScanException(id);
    }
    return inventory.scan(scanId);
  }
}

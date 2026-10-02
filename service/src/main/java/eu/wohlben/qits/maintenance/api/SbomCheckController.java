package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.dto.SbomCheckReportDto;
import eu.wohlben.qits.maintenance.sbomcheck.SbomCheckService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The daily SBOM check's report, and the button beside its cron (qits-621 / qits-668).
 *
 * <p><b>{@code GET} is 404 until a check has run</b>, never an empty report: an empty list of
 * findings reads as "every artifact has an SBOM", which nobody has checked yet.
 *
 * <p><b>{@code POST /runs} RUNS the check and answers 202 once it has finished</b>, with the report
 * it stored. Unlike a scan it is bounded — one listing read per artifact name in qits-artifacts and
 * a few rows — and the person pressing it wants the answer, not a row to poll. 202 rather than 200
 * because the run is the side effect, and the report is also what {@code GET} serves from then on.
 * A run already going (the cron's) is waited for, then this one runs. A probe qits-artifacts could
 * not answer is a 502 with the sentence, and nothing is stored.
 */
@Path("/sbom-check")
@Produces(MediaType.APPLICATION_JSON)
public class SbomCheckController {

  @Inject SbomCheckService check;

  @GET
  @Operation(summary = "The last SBOM check's report")
  @APIResponse(responseCode = "200", description = "The report")
  @APIResponse(responseCode = "404", description = "No check has run yet")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  public SbomCheckReportDto report() {
    return check.lastReport();
  }

  @POST
  @Path("/runs")
  @Operation(summary = "Run the SBOM check now")
  @APIResponse(responseCode = "202", description = "Ran; the body is the report it stored")
  @APIResponse(responseCode = "502", description = "qits-artifacts could not be asked; nothing stored")
  @RolesAllowed({"qits:admin", "qits:system"})
  public Response run() {
    return Response.status(Response.Status.ACCEPTED)
        .entity(check.run(Instant.now()))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}

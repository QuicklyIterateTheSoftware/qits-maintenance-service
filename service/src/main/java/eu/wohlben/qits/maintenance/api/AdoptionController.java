package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.control.Adoption;
import eu.wohlben.qits.maintenance.dto.AdoptionJourneyDto;
import eu.wohlben.qits.maintenance.error.BadRequestException;
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
 * How far one release got — the journey, folded on this side and derived on every read.
 *
 * <p>Served under {@code /maintenance/api/adoption}; the {@code /maintenance/api} prefix is
 * {@code quarkus.rest.path} and is never spelled in a controller.
 *
 * <p><b>Read-only, and there is nothing else under this noun.</b> Nothing here queues work and
 * nothing here writes: the answer is a query over {@code mt_pin} and the SBOM graph, both of which
 * are filled by the scan and the bus. What a person can do about a journey that has stalled is act
 * on the repository that owes it, which is somebody else's route.
 *
 * <p>Every route takes the same pair of roles as the rest of this API — {@code qits:admin} for a
 * person through the gateway's forward-auth headers, {@code qits:system} for a machine — and the
 * annotation is on every METHOD rather than on the class, because a method-level {@code
 * @RolesAllowed} REPLACES a class-level one and a mixture is how one route ends up open. Like every
 * read in this API, it also takes {@code qits:agent}. {@code qits:admin-agent} is admitted too
 * (qits-628 follow-up): an ADMIN workspace's coding agent carries it alongside {@code qits:agent},
 * and for now it may use everything {@code qits:admin} may use.
 */
@Path("/adoption")
@Produces(MediaType.APPLICATION_JSON)
public class AdoptionController {

  @Inject Adoption adoption;

  /**
   * The journey of one released {@code (repository, version)} — the cross-link a release request
   * follows.
   *
   * <p><b>TWO QUERY PARAMETERS RATHER THAN PATH SEGMENTS.</b> A version is not a safe path segment
   * on its own — it can carry a slash in another ecosystem's spelling — and the repository half may
   * be a name or a uuid. Two query parameters have neither problem and no ordering rule to lose.
   *
   * <p><b>Both are required, and half a key is a 400 rather than a guess.</b> Answering "the newest
   * release of that repository" would be this service deciding which release was meant.
   *
   * <p><b>There is NO 404 here, and its absence is the change.</b> The retired {@code
   * /trains/by-release} answered 404 for a release that had opened no station, which was a fact
   * about the log rather than about the release. Nothing is logged now: an unknown release published
   * no coordinate anybody could be carrying, so it answers 200 with empty {@code packages} and a
   * real closure in which every row is PENDING.
   */
  @GET
  @Path("/by-release")
  @Operation(
      operationId = "getAdoptionByRelease",
      summary = "How far one released repository and version got")
  @APIResponse(responseCode = "200", description = "The journey")
  @APIResponse(responseCode = "400", description = "Both repository and version are required")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public AdoptionJourneyDto byRelease(
      @QueryParam("repository") String repository, @QueryParam("version") String version) {
    String named = trimmed(repository);
    String released = trimmed(version);
    if (named == null || released == null) {
      throw new BadRequestException("a by-release lookup names both a repository and a version");
    }
    return adoption.byRelease(named, released);
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}

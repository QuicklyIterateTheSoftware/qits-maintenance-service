package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.dto.ArtifactDto;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.sbom.SbomClient;
import eu.wohlben.qits.maintenance.sbom.SbomIngestService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * What this platform PUBLISHES, and the bills of materials behind it.
 *
 * <p>The listing is the internal-libs page: one row per artifact this platform has released, with
 * how many of our own things ship a copy of it and how many of those are behind. It is the inverse
 * of the repository page — that one asks what a repository depends ON, this one asks what depends
 * on IT.
 *
 * <p>Every route takes the same pair of roles as the rest of this API.
 */
@Path("/artifacts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ArtifactController {

  @Inject ArtifactGraph graph;

  @Inject SbomIngestService sboms;

  /**
   * The manual backfill's body.
   *
   * @param ecosystem the artifact type: maven, npm, docker or daemon
   * @param name the artifact in {@code mt_pin}'s spelling
   * @param version the released version whose document to read
   */
  public record IngestRequest(String ecosystem, String name, String version) {

    /** What a 202 answers with — the artifact row to look at. */
    public record Accepted(UUID id) {}
  }

  @GET
  @Operation(summary = "Every artifact this platform publishes, with its reach")
  @APIResponse(responseCode = "200", description = "The artifacts")
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  public List<ArtifactDto> artifacts() {
    return graph.artifacts();
  }

  /**
   * Reads (or re-reads) one released artifact's bill of materials.
   *
   * <p><b>This is the only thing that moves a MISSING or a FAILED row, and it exists because both
   * are terminal by design.</b> A 404 from qits-artifacts is the ordinary permanent answer for
   * anything released before the SBOM route existed, and a released version is immutable — so
   * nothing retries either on a schedule. What this route is for is the case a schedule cannot
   * know about: somebody stored a document for an old release, or fixed the producer, and wants the
   * graph now rather than at the next release.
   *
   * <p><b>202 and no waiting</b>, like every other write here: the fetch and the parse happen on the
   * one worker thread. Creating the row is the whole of the request.
   */
  @POST
  @Path("/ingest")
  @Operation(summary = "Read one released artifact's sbom now")
  @APIResponse(responseCode = "202", description = "Queued; the id is the artifact row")
  @APIResponse(responseCode = "400", description = "Unknown ecosystem, or no name or version")
  @RolesAllowed({"qits:admin", "qits:system"})
  public Response ingest(IngestRequest request) {
    if (request == null) {
      throw new BadRequestException("an ingest names an ecosystem, a name and a version");
    }
    // The released artifact's TYPE, which is wider than an Ecosystem by one word: a daemon binary's
    // document is addressed by `daemon` exactly as a jar's is by `maven`.
    if (!SbomClient.addressable(request.ecosystem())) {
      throw new BadRequestException(
          "ecosystem must be maven, npm, docker or daemon, not '" + request.ecosystem() + "'");
    }
    String type = request.ecosystem().trim().toLowerCase(Locale.ROOT);
    String name = trimmed(request.name());
    String version = trimmed(request.version());
    if (name == null || version == null) {
      throw new BadRequestException("an ingest names both a name and a version");
    }
    // The repository is left null: nothing announced this one, and guessing which repository
    // produced a release would put a name on the row that no event ever said.
    UUID id = sboms.requeue(type, name, version, null, Instant.now());
    return Response.status(Response.Status.ACCEPTED)
        .entity(new IngestRequest.Accepted(id))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}

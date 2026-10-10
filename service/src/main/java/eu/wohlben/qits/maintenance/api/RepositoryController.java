package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.control.Adoption;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.DownstreamDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDependentsDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDetailDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The inventory: repositories, their pins, and what is pending on them. Read-only since qits-1133
 * R5 retired the group door ({@code POST /{name}/groups/{group}/bumps}): pending pins are written by
 * the {@code dependency-bump} release-request automation.
 *
 * <p>Served under {@code /maintenance/api/repositories} — the {@code /maintenance/api} prefix is
 * {@code quarkus.rest.path}, not spelled here, so this class carries only its own noun.
 *
 * <p><b>Every route accepts the same three roles</b>, {@code qits:admin} (a person, through the
 * gateway's forward-auth headers), {@code qits:system} (a machine, through a bearer validated
 * against qits-idp) and {@code qits:agent} (a commissioned agent). There is no anonymous route
 * here. {@code qits:admin-agent} is admitted too (qits-628 follow-up): an
 * ADMIN workspace's coding agent carries it alongside {@code qits:agent}, and for now it may use
 * everything {@code qits:admin} may use.
 */
@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RepositoryController {

  @Inject Inventory inventory;

  /** What this repository's RELEASES contain, and who contains them. See {@link ArtifactGraph}. */
  @Inject ArtifactGraph graph;

  /** Who is downstream of it, traced to the end. See {@link Adoption}. */
  @Inject Adoption adoption;

  /** What a 202 answers with — the id to poll. Shared with the automation run door. */
  public record AcceptedResponse(UUID id) {}

  @GET
  @Operation(
      operationId = "listRepositories",
      summary = "Every repository in the inventory, with its groups and what is pending")
  @APIResponse(responseCode = "200", description = "The repositories")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
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
  @Operation(operationId = "getRepository", summary = "One repository with every pin it holds")
  @APIResponse(responseCode = "200", description = "The repository")
  @APIResponse(responseCode = "404", description = "No such repository in the inventory")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
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
  @Operation(
      operationId = "listRepositoryDependents",
      summary = "Who embeds the artifacts this repository publishes")
  @APIResponse(responseCode = "200", description = "The dependents, per published artifact")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
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
  @Operation(
      operationId = "getRepositoryDownstream",
      summary = "Everything downstream of this repository, ordered upstream first")
  @APIResponse(responseCode = "200", description = "The closure, depth ascending then name")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public DownstreamDto downstream(@PathParam("name") String name) {
    return adoption.downstream(name);
  }
}

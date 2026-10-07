package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.DependencyDto;
import eu.wohlben.qits.maintenance.dto.DependentsDto;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The inventory read the other way round: one dependency, everyone who pins it.
 *
 * <p><b>This is the question a release is followed by.</b> "Who is still on eventstream
 * 2026.8.11" has no answer anywhere else on the platform — every repository knows its own pins and
 * nothing knows them all — and it is the whole reason the inventory is a store rather than a page
 * that asks the git host on each load.
 *
 * <p>The glob is the same two-wildcard form a group's {@code deps} entry uses, so an operator can
 * paste one from a repository's configuration and see exactly what it claims.
 */
@Path("/dependencies")
@Produces(MediaType.APPLICATION_JSON)
public class DependencyController {

  @Inject Inventory inventory;

  @Inject ArtifactGraph graph;

  @GET
  @Operation(summary = "Dependencies matching a glob, with every pin of each")
  @APIResponse(responseCode = "200", description = "The dependencies")
  @APIResponse(responseCode = "400", description = "kind is neither INTERNAL nor EXTERNAL")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public List<DependencyDto> dependencies(
      @QueryParam("name") @DefaultValue("*") String name, @QueryParam("kind") String kind) {
    return inventory.dependencies(name, pinKind(kind));
  }

  /**
   * <b>Who ships a copy of this dependency</b> — the same question the route above answers from
   * manifests, answered from what was actually RELEASED.
   *
   * <p>They are two routes because they are two facts. A pin is a line a bump can edit; a dependent
   * is a component inside a published package, transitives included. An artifact can embed
   * something it does not pin, and a repository can pin something none of its artifacts ship. A
   * single route merging the two would be unable to say which of those it was showing.
   *
   * <p><b>The default is the NEWEST released version of each dependent</b>, because forty-nine
   * older releases of one library are answers about versions nobody can change any more.
   * {@code all=true} is the archaeology.
   */
  @GET
  @Path("/dependents")
  @Operation(summary = "Every released artifact of ours that embeds this dependency")
  @APIResponse(responseCode = "200", description = "The dependents")
  @APIResponse(responseCode = "400", description = "Unknown ecosystem, or no name")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
  public DependentsDto dependents(
      @QueryParam("ecosystem") String ecosystem,
      @QueryParam("name") String name,
      @QueryParam("all") @DefaultValue("false") boolean all) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("name is required");
    }
    Ecosystem world =
        Ecosystem.of(ecosystem)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "ecosystem must be maven, npm or docker, not '" + ecosystem + "'"));
    return graph.dependents(world, name.trim(), all);
  }

  /** INTERNAL or EXTERNAL, or null for both. Anything else is a 400 rather than an empty list. */
  private static PinKind pinKind(String kind) {
    if (kind == null || kind.isBlank()) {
      return null;
    }
    String value = kind.trim().toUpperCase(java.util.Locale.ROOT);
    if (!value.equals(PinKind.INTERNAL.name()) && !value.equals(PinKind.EXTERNAL.name())) {
      // REACTOR and UNRESOLVED are deliberately refused too: neither is a half of the split this
      // filter serves, and answering an empty list would look like "there are none of those".
      throw new BadRequestException(
          "kind must be INTERNAL or EXTERNAL, not '" + kind + "'");
    }
    return PinKind.valueOf(value);
  }
}

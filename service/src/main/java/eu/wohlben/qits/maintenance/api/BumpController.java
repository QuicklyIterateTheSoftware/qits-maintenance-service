package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.BumpDto;
import eu.wohlben.qits.maintenance.dto.BumpStandingDto;
import eu.wohlben.qits.maintenance.dto.PendingBumpsDto;
import eu.wohlben.qits.maintenance.error.NoSuchBumpException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The bump log — what was asked of qits-ci, and what came of it — and the dispatcher's standing.
 * Read-only: every row is written by a release-request automation (or is a retired group bump's
 * history, qits-1133), and the doors that opened a group bump or a dispatch window are gone with
 * that path.
 *
 * <p>Its own root rather than a child of {@code /repositories/{name}/…}: a bump id is unique on its
 * own and a bump's address is a thing an operator pastes into a message. Two paths for one row
 * would mean two links for one bump.
 */
@Path("/bumps")
@Produces(MediaType.APPLICATION_JSON)
public class BumpController {

  /** The listing's page size, and its ceiling: a bump row carries its whole change list. */
  static final int DEFAULT_LIMIT = 20;

  static final int MAX_LIMIT = 200;

  @Inject Inventory inventory;

  @Inject BumpDispatcher dispatcher;

  @GET
  @Operation(
      operationId = "listBumps",
      summary = "The newest bumps, of one repository or of all of them")
  @APIResponse(responseCode = "200", description = "The bumps")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk", "qits:system", "qits:agent"})
  public List<BumpDto> bumps(
      @QueryParam("repository") String repository,
      @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
    return inventory.bumps(repository, Math.clamp(limit, 1, MAX_LIMIT));
  }

  /**
   * The newest bumps still on their way, of every repository: asked for, running, or green and not
   * released yet. What the top bar's bumps menu lists.
   */
  @GET
  @jakarta.ws.rs.Path("/pending")
  @Operation(operationId = "listPendingBumps", summary = "The newest bumps still on their way")
  @APIResponse(responseCode = "200", description = "The pending bumps")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk", "qits:system", "qits:agent"})
  public PendingBumpsDto pendingBumps(
      @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
    return inventory.pendingBumps(Math.clamp(limit, 1, MAX_LIMIT));
  }

  /**
   * <b>Where every owed bump stands, and what the dispatcher would send right now</b> (qits-1696):
   * the tick's decision made and not acted on, the whole owed set in hand-out order with why each
   * repository is where it is, and the repositories set aside after a refused ask.
   *
   * <p>It is the only way to tell "owed and waiting for a reason" from "owed and forgotten" without
   * reading this service's log — the difference a cycle that sat unbroken for hours on 2026-10-28
   * made plain. It opens nothing and records nothing; see {@code BumpDispatcher}'s class doc for
   * what reading it shares with the next tick.
   *
   * <p>A literal segment, so JAX-RS matches it ahead of {@code /{id}} below — as {@code /pending}
   * already is — rather than handing {@code standing} to the uuid parse as an unknown bump.
   */
  @GET
  @jakarta.ws.rs.Path("/standing")
  @Operation(
      operationId = "getBumpStanding",
      summary = "Where every owed bump stands, and what the dispatcher would send now")
  @APIResponse(responseCode = "200", description = "The dispatcher's decision and the owed set")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk", "qits:system", "qits:agent"})
  public BumpStandingDto standing() {
    return dispatcher.standing(Instant.now());
  }

  /**
   * One bump: the row, the changes it sent, and the ci run it is following.
   *
   * <p>An id that is not a uuid is a 404 like any other unknown bump — a malformed id and an absent
   * one are the same question from the caller's side.
   */
  @GET
  @jakarta.ws.rs.Path("/{id}")
  @Operation(operationId = "getBump", summary = "One bump with the changes it sent")
  @APIResponse(responseCode = "200", description = "The bump")
  @APIResponse(responseCode = "404", description = "No such bump")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk", "qits:system", "qits:agent"})
  public BumpDto bump(@PathParam("id") String id) {
    UUID bumpId;
    try {
      bumpId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new NoSuchBumpException(id);
    }
    return inventory.bump(bumpId);
  }
}

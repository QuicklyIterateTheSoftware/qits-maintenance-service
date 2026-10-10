package eu.wohlben.qits.maintenance.bump.changelog;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * "Which releases of this repository published a changelog" — the one read a bump makes of
 * qits-artifacts' docs store (epic qits-893).
 *
 * <p><b>Every release publishes {@code @changelog/<repository>} at its version</b>, one {@code
 * CHANGELOG.md}, through the docs store. This service never reads a changelog's TEXT: the bump step's
 * {@code qits changelog bump-message} does, because the text cannot ride a payload that reaches the
 * step as one environment string. What is asked here is the listing — the version list — which is
 * all {@link ChangelogRanges} needs to decide the range and prove every changelog in it exists.
 *
 * <p><b>The site name goes into the path LITERALLY</b>, {@code @} and slash included: {@code
 * @changelog/qits-ci-service} is two path segments on the docs route, exactly as the publishing
 * CLI wrote it. Encoding the slash would address a site with a per-cent sign in its name.
 *
 * <p><b>Three answers, and they mean different things.</b>
 *
 * <ul>
 *   <li>a listing → {@link Outcome#PUBLISHED} with its versions, in the store's order — which is
 *       PUBLISH time, not version order, so a caller that wants an order sorts;
 *   <li>404 → {@link Outcome#NONE}: the repository never published a changelog. That is every
 *       repository whose newest release predates the epic, and it is not an error;
 *   <li>anything else — a 5xx, a refused grant, a transport failure, a 200 that is not a listing —
 *       → {@link Outcome#UNREACHABLE}, which says nothing about the changelogs themselves. A bump
 *       that meets it is RETRIED, the same as a trigger qits-ci answered 503.
 * </ul>
 *
 * <p><b>Through {@link PeerClient}</b>, for the reasons {@code SbomClient} gives: one shared client,
 * the shipped timeout, the forward-auth pair, the optional bearer, the response bound — and {@code
 * FakePeers}, which is how the service suite replaces the network.
 */
@ApplicationScoped
public class ChangelogClient {

  /** The docs route's own prefix and the changelog scope. Code, not configuration — see {@code
   * PeerTarget.ARTIFACTS_DOCS}. */
  static final String PREFIX = "/artifacts/docs/docs/@changelog/";

  @Inject PeerClient peers;

  /** What one listing read came to. */
  public enum Outcome {
    /** The store answered a listing; its versions are on the result. */
    PUBLISHED,

    /** The store holds no changelog for the repository at all — it predates changelogs. */
    NONE,

    /** The store could not be read, so nothing is known. Retry. */
    UNREACHABLE
  }

  /**
   * One answer.
   *
   * @param outcome which of the three
   * @param versions the published versions, in the store's (publish-time) order; empty unless
   *     {@link Outcome#PUBLISHED}
   * @param url what was read, so a surprising answer can be reproduced by hand
   * @param reason one line on {@link Outcome#UNREACHABLE}, else null
   */
  public record Result(Outcome outcome, List<String> versions, String url, String reason) {

    public Result {
      versions = versions == null ? List.of() : List.copyOf(versions);
    }

    /** Whether the store could not be read. */
    public boolean transientFailure() {
      return outcome == Outcome.UNREACHABLE;
    }
  }

  /** {@code /artifacts/docs/docs/@changelog/<repository>}, the repository name verbatim. */
  static String path(String repository) {
    return PREFIX + repository.trim();
  }

  /**
   * Every version of {@code repository} that published a changelog.
   *
   * @param repository the catalog repository name — the site's second segment, exactly as the
   *     publishing CLI names it
   */
  public Result versions(String repository) {
    if (repository == null || repository.isBlank()) {
      return new Result(Outcome.NONE, List.of(), null, null);
    }
    PeerExchange exchange = peers.get(PeerTarget.ARTIFACTS_DOCS, path(repository));
    String url = exchange.call().url();
    PeerAnswer answer = exchange.answer();
    if (answer.notFound()) {
      return new Result(Outcome.NONE, List.of(), url, null);
    }
    if (!answer.ok()) {
      return new Result(Outcome.UNREACHABLE, List.of(), url, answer.failure());
    }
    JsonNode listing = answer.json();
    JsonNode entries = listing == null ? null : listing.get("versions");
    if (entries == null || !entries.isArray()) {
      return new Result(
          Outcome.UNREACHABLE, List.of(), url, "the changelog listing did not parse as one");
    }
    List<String> versions = new ArrayList<>();
    for (JsonNode entry : entries) {
      JsonNode version = entry == null ? null : entry.get("version");
      if (version != null && version.isTextual() && !version.asText().isBlank()) {
        versions.add(version.asText().trim());
      }
    }
    return new Result(Outcome.PUBLISHED, versions, url, null);
  }
}

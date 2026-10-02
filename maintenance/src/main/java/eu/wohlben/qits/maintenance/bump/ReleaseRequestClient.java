package eu.wohlben.qits.maintenance.bump;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The release ask: the one call this service makes to qits-projects that is not a read, and the
 * only thing besides the qits-ci trigger that it asks anybody to do.
 *
 * <h2>What the ask is</h2>
 *
 * <p>{@code POST /projects/api/repositories/<repoId>/release-requests} does not merge anything and
 * does not release anything. It OPENS a release request: qits-projects folds the repository's
 * default branch, this branch and every released tag still in flight onto {@code release/<id>},
 * settles the quality gates against that fold, and the Auto Release arm tags it once they pass.
 * <b>The train's job ends here.</b> What became of the request is qits-projects' to finish and
 * {@code SCMRelease} on the bus to announce, and this service does not poll its progress — two
 * mechanisms watching one fact would be two ways to disagree about it.
 *
 * <p><b>{@link #state} is the one exception and it asks a different question.</b> Not "how far along
 * is the release" but "is this request still one my bump can wait for" — the question the dispatch
 * hold rests on, which nothing on the bus answers: a rejection is a state change on a row over
 * there and publishes no event at all. See that method for the failure it was written for.
 *
 * <p><b>It replaces qits-workspaces' release door</b>, which this service called until the door was
 * removed. The door was synchronous in shape — it took an {@code expectedSha}, answered a request id
 * and published the {@code SCMRelease} that released a {@code maintenance/} branch. None of those
 * three survives the move, and each is a deliberate loss:
 *
 * <ul>
 *   <li><b>No {@code expectedSha}.</b> The door armed a request at the instant it was asked, so a
 *       head that had moved in between had to be a refusal. A release request is re-folded and
 *       re-gated on every push to any of its named sources, so a commit that lands after the ask is
 *       gated rather than smuggled in. The pin is replaced by continuous re-gating, which is the
 *       stronger of the two.
 *   <li><b>The ask is addressed by the repository's CATALOG ID</b>, {@code mt_repository.catalog_id}
 *       — qits-projects' own row id, which is what its path parameter resolves. The door took the
 *       {@code (projectId, repositoryName)} pair instead, because it addressed a clone coordinate.
 *   <li><b>Nothing is released when this returns.</b> The answer is a row in another service, in
 *       state {@code PENDING}. See {@code BumpService.askForRelease} for what the bump does with it.
 * </ul>
 *
 * <p><b>The ask is CONVERGENT, and that is what makes a retry of it free.</b> A branch that already
 * participates in an open release request answers that request rather than opening a second one, so
 * asking twice costs one HTTP call and nothing else.
 *
 * <h2>The four answers, and which one is retried</h2>
 *
 * <ul>
 *   <li><b>REQUESTED</b> — 2xx carrying {@code request.id}. Stored on the bump row.
 *   <li><b>CONVERGED</b> — a 2xx with no id to hold on to. Asking again would get the same empty
 *       answer, so it is recorded rather than retried. (The ordinary convergence — the branch
 *       already being on an open request — is a 2xx WITH that request's id, and is REQUESTED.)
 *   <li><b>REFUSED</b> — a 4xx that is not an auth failure: a blank branch or summary, a repository
 *       id qits-projects does not hold, a route a deployed qits-projects does not serve yet. The
 *       same bytes fail identically every time, so nothing retries it; the sentence goes on the
 *       bump's message where a person reads it, and the next nightly bump of that group asks again
 *       from scratch.
 *   <li><b>RETRY</b> — a transport failure, a 5xx, or a 401/403. The first two are somebody's
 *       outage; the third is this service's projects credential not being admitted, which is a
 *       deployment grant rather than a code change — so it is retried, and it heals the moment the
 *       grant lands rather than needing the bump to be re-run.
 * </ul>
 *
 * <p><b>The credential is the one every catalog read already uses</b> — {@link
 * PeerTarget#PROJECTS}, audience {@code qits-projects}. The route admits {@code qits:admin} and
 * {@code qits:system}, and {@link PeerClient} presents {@code qits:system} on every call, so no
 * person's role and no sixth oidc client is needed. That is the whole of what the door cost and this
 * does not.
 */
@ApplicationScoped
public class ReleaseRequestClient {

  /** qits-projects' release-request collection, one repository's. The id half is per call. */
  public static final String REQUESTS_PATH_PREFIX = "/projects/api/repositories/";

  /** …and the tail after the repository id. */
  public static final String REQUESTS_PATH_SUFFIX = "/release-requests";

  /**
   * What the column holds when the ask settled with no request id of its own: qits-projects
   * answered without one, or the branch was released or deleted before the ask could be made.
   * Either way nothing is owed.
   */
  public static final String CONVERGED = "converged";

  /** …and when the ask was refused in a way a retry cannot fix. The sentence is on {@code message}. */
  public static final String REFUSED = "refused";

  /**
   * {@code release_request.summary} is a default-length column over there, so this is the bound the
   * ask is trimmed to. Nothing this service composes comes near it — the cap exists so a group name
   * somebody made very long is a shortened summary rather than a 500 on the other side.
   */
  static final int SUMMARY_LIMIT = 255;

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject PeerClient peers;

  /**
   * What qits-projects said.
   *
   * @param outcome how to treat it
   * @param requestId the release request, or null when there is none to hold on to
   * @param message the sentence for the bump's message column, or null when there is nothing to say
   */
  public record RequestResult(Outcome outcome, String requestId, String message) {

    public enum Outcome {
      /** A release request is open and this bump names it. */
      REQUESTED,

      /** There was nothing to hold on to — no id came back, and asking again would not produce one. */
      CONVERGED,

      /** qits-projects refused, and a retry would be refused identically. */
      REFUSED,

      /** Nobody answered, or the answer is one the next tick might not get. */
      RETRY
    }

    static RequestResult requested(String requestId, String state) {
      return new RequestResult(
          Outcome.REQUESTED,
          requestId,
          "the release request " + requestId + " is " + (state == null ? "open" : state));
    }
  }

  /**
   * Opens (or converges onto) the release request for one branch.
   *
   * @param repoId the repository as QITS-PROJECTS ids it — {@code mt_repository.catalog_id}, the
   *     {@code id} its catalog listing answers. Not the name and not the project: the path parameter
   *     is resolved against that service's own repository table and nothing else addresses it.
   * @param branch the maintenance branch this bump pushed
   * @param summary the release's summary line, which is also the fold's commit message
   */
  public RequestResult requestRelease(String repoId, String branch, String summary) {
    ObjectNode body = JSON.createObjectNode();
    body.put("branch", branch);
    body.put("summary", cap(summary));
    // `priority` is LOWEST on every bump, unconditionally and by decision: a dependency bump is the
    // one release that is never what anybody is waiting for, so it yields the build queue to every
    // release a person asked for. There is no key to turn it up — a bump that needed to go first
    // would be a person's release request, not this one. An older qits-projects that does not know
    // the field ignores the unknown key, so the ask is safe to send before that side ships it.
    body.put("priority", "LOWEST");
    // `requester` is deliberately not sent. It states WHOM a machine peer acts for, and a bump has
    // no such person: a nightly one was asked for by a clock and a manual one records no operator.
    // Omitted, qits-projects attributes the request to this service's own identity, which is the
    // true answer.

    String path = REQUESTS_PATH_PREFIX + encode(repoId) + REQUESTS_PATH_SUFFIX;
    PeerAnswer answer = peers.post(PeerTarget.PROJECTS, path, body.toString()).answer();

    if (answer.ok()) {
      // The controller wraps its answer: {"request": {...}}. Read through the wrapper rather than
      // guessing at a flat body, so a 2xx that is not this shape lands as CONVERGED and is visible,
      // instead of being retried for ever against a service that is answering perfectly well.
      JsonNode request = answer.json() == null ? null : answer.json().get("request");
      String requestId = text(request, "id");
      if (requestId == null) {
        return new RequestResult(
            RequestResult.Outcome.CONVERGED,
            CONVERGED,
            "the release request was answered without an id: " + brief(answer.body()));
      }
      return RequestResult.requested(requestId, text(request, "state"));
    }

    Integer status = answer.httpStatus();
    if (status != null && (status == 401 || status == 403)) {
      return new RequestResult(
          RequestResult.Outcome.RETRY,
          null,
          "qits-projects would not admit this service (HTTP "
              + status
              + "); the release-request route wants qits:admin or qits:system — the branch is"
              + " pushed and the next sweep asks again");
    }
    if (status != null && status >= 400 && status < 500) {
      return new RequestResult(
          RequestResult.Outcome.REFUSED,
          REFUSED,
          "the release request for " + branch + " was refused: HTTP " + status + " "
              + brief(answer.body()));
    }
    return new RequestResult(
        RequestResult.Outcome.RETRY,
        null,
        "qits-projects could not be reached ("
            + answer.failure()
            + "); the branch is pushed and the next sweep asks again");
  }

  /**
   * Adds a branch to an open release request, so its next fold carries it. Safe to repeat: a branch
   * already on the request adds nothing over there.
   *
   * @param repoId the repository as qits-projects ids it ({@code mt_repository.catalog_id})
   * @param requestId the open request
   * @param branch the branch to add
   */
  public RequestResult join(String repoId, String requestId, String branch) {
    ObjectNode body = JSON.createObjectNode();
    body.put("branch", branch);
    String path =
        REQUESTS_PATH_PREFIX + encode(repoId) + REQUESTS_PATH_SUFFIX + "/" + encode(requestId)
            + "/sources";
    PeerAnswer answer = peers.post(PeerTarget.PROJECTS, path, body.toString()).answer();
    if (answer.ok()) {
      JsonNode request = answer.json() == null ? null : answer.json().get("request");
      return RequestResult.requested(requestId, text(request, "state"));
    }
    Integer status = answer.httpStatus();
    if (status != null && status >= 400 && status < 500 && status != 401 && status != 403) {
      return new RequestResult(
          RequestResult.Outcome.REFUSED,
          REFUSED,
          "joining " + branch + " to release request " + requestId + " was refused: HTTP "
              + status + " " + brief(answer.body()));
    }
    return new RequestResult(
        RequestResult.Outcome.RETRY,
        null,
        "qits-projects did not take the join of " + branch + " ("
            + (status == null ? answer.failure() : "HTTP " + status) + ")");
  }

  /**
   * What qits-projects says has become of one release request.
   *
   * @param state the state verbatim — {@code PENDING}, {@code READY}, {@code RELEASED}, {@code
   *     REJECTED}, {@code FAILED}, {@code CONFLICTED}, {@code WITHDRAWN} — or null when it could not
   *     be read
   * @param detail that service's own sentence about why, when it has one ({@code detail}, or the
   *     conflict when the fold is what failed)
   * @param error why it could not be read, or null
   */
  public record ReleaseState(String state, String detail, String error) {

    /** The states in which a release is still on its way, or has already arrived. */
    private static final Set<String> COMING = Set.of("PENDING", "READY", "RELEASED");

    /** Whether qits-projects answered at all. */
    public boolean readable() {
      return state != null;
    }

    /**
     * <b>Whether nothing is coming from this request as it stands.</b> Red gates, a mechanical
     * failure, a fold that will not apply, an explicit withdrawal — none of them moves {@code main},
     * so a bump waiting on one is waiting for something that has stopped happening.
     *
     * <p><b>It is not a verdict for ever, and must never be recorded as one.</b> qits-projects
     * re-arms REJECTED, FAILED and CONFLICTED back to PENDING on the next merged sha — a push to the
     * branch, a sibling's release, a pending tag reaching main — so this question is asked again on
     * every tick and a request that comes back to life is simply held again.
     *
     * <p>An unreadable answer is <b>not</b> stalled: it is a peer that could not be asked, and the
     * safe reading of that is the one the whole gate takes elsewhere — carry on waiting.
     */
    public boolean stalled() {
      return state != null && !COMING.contains(state);
    }

    /** The sentence a person reads: the state, and what that service said about it. */
    public String sentence() {
      if (state == null) {
        return error == null ? "the release request could not be read" : error;
      }
      return detail == null || detail.isBlank() ? state : state + ": " + detail;
    }
  }

  /**
   * What became of one release request — <b>the one read this service makes about a request it
   * opened</b>, and the only thing that can end a hold.
   *
   * <p><b>This is not the polling the class header rules out, and the difference is which question
   * is asked.</b> Watching a request's progress would be a second mechanism racing qits-projects'
   * own; this asks whether the thing a bump is <i>waiting for</i> still exists. A bump that pushed a
   * branch stays owed until the release lands on main, and until 2026-09-10 nothing here could tell
   * "the release is coming" from "the release was rejected four hours ago" — so a repository whose
   * gating build fails was held for ever, held its consumers behind it, and kept the night's window
   * open dispatching nothing. The estate stopped with one red build and said nothing. That is the
   * whole reason this method exists, and the answer is deliberately never stored as a verdict.
   *
   * @param repoId the repository as qits-projects ids it — {@code mt_repository.catalog_id}
   * @param requestId the request this bump recorded
   */
  public ReleaseState state(String repoId, String requestId) {
    String path =
        REQUESTS_PATH_PREFIX + encode(repoId) + REQUESTS_PATH_SUFFIX + "/" + encode(requestId);
    PeerAnswer answer = peers.get(PeerTarget.PROJECTS, path).answer();
    if (!answer.ok()) {
      // A 404 is not "stalled" either. A request qits-projects no longer holds is a fact this
      // service cannot act on, and guessing at it would re-dispatch a branch that may be released.
      return new ReleaseState(
          null, null, "the release request " + requestId + " could not be read: " + answer.failure());
    }
    JsonNode request = answer.json() == null ? null : answer.json().get("request");
    String state = text(request, "state");
    if (state == null) {
      return new ReleaseState(
          null, null, "the release request " + requestId + " answered no state");
    }
    String detail = text(request, "detail");
    return new ReleaseState(state, detail == null ? text(request, "conflict") : detail, null);
  }

  /**
   * The commit subject a bump's own commits carry, reused as the release summary.
   *
   * <p><b>{@code bump(<group>): <n> dependencies}</b> — the literal shape {@code
   * .config/qits/ci-platform-event-maintenance-bump.yml} prints, word for word, the plural never
   * singularised. Reusing it means the release request, the branch and the commits on it all read
   * the same in a listing.
   *
   * <p><b>{@code n} is what was ASKED FOR, and it cannot be what a commit says.</b> The step counts
   * what it APPLIED, and a bump is up to two commits — the maven step and the node/docker step each
   * clone, commit and push — so there is no single number the subject could match. The count this
   * service froze onto the row is the one it can stand behind.
   */
  public static String summary(String group, int changes) {
    return cap("bump(" + group + "): " + changes + " dependencies");
  }

  /** A summary the column over there will hold. */
  private static String cap(String summary) {
    if (summary == null) {
      return "";
    }
    return summary.length() <= SUMMARY_LIMIT ? summary : summary.substring(0, SUMMARY_LIMIT);
  }

  /** Somebody else's body on this service's message column: bounded, and never a wall of html. */
  private static String brief(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String flat = body.replace('\n', ' ').trim();
    return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
  }

  private static String text(JsonNode body, String field) {
    if (body == null || !body.hasNonNull(field) || !body.get(field).isTextual()) {
      return null;
    }
    String value = body.get(field).asText();
    return value.isBlank() ? null : value;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}

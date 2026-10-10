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
import java.util.ArrayList;
import java.util.List;
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
    return join(repoId, requestId, branch, null);
  }

  /**
   * The same join, stating the branch's priority (qits-1133). A release request's effective
   * priority is the max over its named branches, so an automation's own branch joined at {@code
   * LOWEST} never raises a request nobody asked to hurry — while one joined with none would be
   * recorded at qits-projects' {@code MEDIUM} default, and lift a main-only LOWEST request this
   * service opened to MEDIUM the moment its own bump joined it.
   *
   * @param priority {@code LOWEST} … {@code HIGH}, or null to send none
   */
  public RequestResult join(String repoId, String requestId, String branch, String priority) {
    ObjectNode body = JSON.createObjectNode();
    body.put("branch", branch);
    if (priority != null) {
      body.put("priority", priority);
    }
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

  /** The priority every automation join and every main-only request is sent with. */
  public static final String LOWEST = "LOWEST";

  /**
   * Opens the MAIN-ONLY release request the upstream hook asks for (qits-1133): no branch of this
   * service's own, just the repository's main branch at {@code LOWEST}, so the request's pre-run is
   * what writes the bump. The same route and the same four answers as {@link #requestRelease}; a
   * qits-projects that does not accept main as a request's only source yet answers a 4xx, which is
   * REFUSED and is the dispatcher's to report.
   */
  public RequestResult requestMainOnly(String repoId, String mainBranch, String summary) {
    return requestRelease(repoId, mainBranch, summary);
  }

  /**
   * Withdraws a request this service opened (qits-1133): an upstream request whose pre-run found
   * nothing to bump has nothing to release. {@code POST …/release-requests/{id}/withdraw} with a
   * {@code reason}. A 409 is a request already RELEASED or WITHDRAWN — there is nothing left to
   * withdraw, which is CONVERGED rather than refused.
   */
  public RequestResult withdraw(String repoId, String requestId, String reason) {
    ObjectNode body = JSON.createObjectNode();
    if (reason != null) {
      body.put("reason", cap(reason));
    }
    String path =
        REQUESTS_PATH_PREFIX + encode(repoId) + REQUESTS_PATH_SUFFIX + "/" + encode(requestId)
            + "/withdraw";
    PeerAnswer answer = peers.post(PeerTarget.PROJECTS, path, body.toString()).answer();
    if (answer.ok()) {
      return new RequestResult(
          RequestResult.Outcome.REQUESTED, requestId, "withdrew release request " + requestId);
    }
    Integer status = answer.httpStatus();
    if (status != null && status == 409) {
      return new RequestResult(
          RequestResult.Outcome.CONVERGED,
          requestId,
          "release request " + requestId + " was already settled: " + brief(answer.body()));
    }
    if (status != null && status >= 400 && status < 500 && status != 401 && status != 403) {
      return new RequestResult(
          RequestResult.Outcome.REFUSED,
          REFUSED,
          "withdrawing release request " + requestId + " was refused: HTTP " + status + " "
              + brief(answer.body()));
    }
    return new RequestResult(
        RequestResult.Outcome.RETRY,
        null,
        "qits-projects did not take the withdrawal of " + requestId + " ("
            + (status == null ? answer.failure() : "HTTP " + status) + ")");
  }

  /**
   * One request off a repository's listing, as much of it as the upstream hook reads.
   *
   * @param id the request
   * @param state its state word
   * @param mergedSha its current fold, or null
   * @param ciGate the state of its {@code CI} gate at that fold — {@code PENDING}, {@code PASSED},
   *     {@code FAILED}, {@code UNKNOWN} — or null where it lists none
   */
  public record Listed(String id, String state, String mergedSha, String ciGate) {

    /** Whether the request still takes a branch: open, and not past its release. */
    public boolean open() {
      return OPEN_STATES.contains(state);
    }

    /**
     * Whether the request has a QA VERDICT — its CI gate PASSED or FAILED at the current fold, or it
     * left PENDING altogether. What resets the upstream starvation guard.
     */
    public boolean verdict() {
      return "PASSED".equals(ciGate) || "FAILED".equals(ciGate) || !"PENDING".equals(state);
    }
  }

  /** The states in which a request still takes a branch. */
  private static final Set<String> OPEN_STATES =
      Set.of("PENDING", "READY", "REJECTED", "FAILED", "CONFLICTED");

  /**
   * What a repository's listing said.
   *
   * @param requests the requests, newest first — the OPEN ones from {@link #openRequests}, every
   *     listed one from {@link #requests}; empty when there is none or it was unread
   * @param error why it could not be read, or null
   */
  public record Listing(List<Listed> requests, String error) {

    public Listing {
      requests = requests == null ? List.of() : List.copyOf(requests);
    }

    public boolean readable() {
      return error == null;
    }
  }

  /**
   * A repository's OPEN release requests (qits-1133) — {@code GET …/release-requests}, whose default
   * answer is the open ones plus the last ten released; the released are filtered out here. Read by
   * the upstream hook, which re-plans the bump of each open one that is not READY, and by the
   * dispatcher, which opens no main-only request beside one that is open.
   */
  public Listing openRequests(String repoId) {
    Listing listed = requests(repoId);
    if (!listed.readable()) {
      return listed;
    }
    return new Listing(listed.requests().stream().filter(Listed::open).toList(), null);
  }

  /**
   * Every request the repository's default listing answers — the open ones AND the last released,
   * unfiltered. The legacy group-branch sweep (qits-1133 R2) reads it, because a branch named by a
   * RELEASED request is mid-pipeline and its landing deletes it; {@link #openRequests} is the same
   * read with the released filtered out.
   */
  public Listing requests(String repoId) {
    String path = REQUESTS_PATH_PREFIX + encode(repoId) + REQUESTS_PATH_SUFFIX;
    PeerAnswer answer = peers.get(PeerTarget.PROJECTS, path).answer();
    if (!answer.ok()) {
      return new Listing(
          List.of(), "the release requests of " + repoId + " could not be read: " + answer.failure());
    }
    JsonNode body = answer.json();
    if (body == null || !body.hasNonNull("requests") || !body.get("requests").isArray()) {
      return new Listing(
          List.of(), "the release requests of " + repoId + " answered no requests array");
    }
    List<Listed> all = new ArrayList<>();
    for (JsonNode request : body.get("requests")) {
      String id = text(request, "id");
      if (id != null) {
        all.add(
            new Listed(id, text(request, "state"), text(request, "mergedSha"), ciGate(request)));
      }
    }
    return new Listing(all, null);
  }

  /**
   * One request that CUT a version, off the repository's whole history (qits-1156).
   *
   * @param id the request
   * @param version the version its tag is named after
   * @param state its state word
   * @param publish the state of its newest {@code PUBLISH} run ({@code pipeline.phases[]}), or null
   *     where no publish run ever began
   */
  public record Cut(String id, String version, String state, String publish) {

    /** The states in which a cut request will never run its publish again. */
    private static final Set<String> CLOSED = Set.of("FINALIZED", "OBSOLETE", "WITHDRAWN");

    /**
     * <b>Whether this release published nothing, for good.</b> The request is closed, so no retry
     * can still publish it, and no publish run of it finished: its newest run was CANCELLED (a later
     * request superseded it mid-run), or none ever began.
     *
     * <p>A run that finished, green or red, is not this case. It ran, so a changelog missing after
     * it is a publish that broke. A RELEASED request is not this case either, cancelled or not: a
     * retry of its run can still publish.
     */
    public boolean publishedNothing() {
      return state != null
          && CLOSED.contains(state)
          && (publish == null || "CANCELLED".equals(publish));
    }
  }

  /**
   * What a repository's whole history said.
   *
   * @param cuts every request that cut a version, newest first; empty when unread
   * @param error why it could not be read, or null
   */
  public record History(List<Cut> cuts, String error) {

    public History {
      cuts = cuts == null ? List.of() : List.copyOf(cuts);
    }

    public boolean readable() {
      return error == null;
    }
  }

  /** The query that asks for every state, WITHDRAWN and OBSOLETE included. */
  public static final String HISTORY_QUERY = "?state=all";

  /**
   * Every request of the repository that cut a version, with how its publish ended — {@code GET
   * …/release-requests?state=all} (qits-1156). Read by {@code ChangelogRanges} only when a release
   * has no changelog, to tell a release that published nothing from one whose publish broke.
   *
   * <p>A 404 is an empty history and no error: qits-projects knows no such repository, so nothing
   * says the release published nothing, and the caller keeps its problem. Any other failure is
   * unreadable, and the caller retries.
   */
  public History history(String repoId) {
    String path = REQUESTS_PATH_PREFIX + encode(repoId) + REQUESTS_PATH_SUFFIX + HISTORY_QUERY;
    PeerAnswer answer = peers.get(PeerTarget.PROJECTS, path).answer();
    if (answer.notFound()) {
      return new History(List.of(), null);
    }
    if (!answer.ok()) {
      return new History(
          List.of(),
          "the release requests of " + repoId + " could not be read: " + answer.failure());
    }
    List<Cut> cuts = cuts(answer.json());
    if (cuts == null) {
      return new History(
          List.of(), "the release requests of " + repoId + " answered no requests array");
    }
    return new History(cuts, null);
  }

  /** The cut requests off a listing, or null when it is not one. */
  static List<Cut> cuts(JsonNode body) {
    if (body == null || !body.hasNonNull("requests") || !body.get("requests").isArray()) {
      return null;
    }
    List<Cut> cuts = new ArrayList<>();
    for (JsonNode request : body.get("requests")) {
      String id = text(request, "id");
      String version = text(request, "version");
      if (id != null && version != null) {
        cuts.add(new Cut(id, version.trim(), text(request, "state"), publish(request)));
      }
    }
    return cuts;
  }

  /** The state of the {@code PUBLISH} phase off {@code pipeline.phases}, or null where it is absent. */
  private static String publish(JsonNode request) {
    JsonNode phases = request == null ? null : request.path("pipeline").path("phases");
    if (phases == null || !phases.isArray()) {
      return null;
    }
    for (JsonNode phase : phases) {
      if ("PUBLISH".equals(text(phase, "phase"))) {
        return text(phase, "state");
      }
    }
    return null;
  }

  /** The {@code CI} gate's state off {@code gates}, or off {@code qualityGates} where that is all. */
  private static String ciGate(JsonNode request) {
    for (String field : List.of("gates", "qualityGates")) {
      if (request == null || !request.hasNonNull(field) || !request.get(field).isArray()) {
        continue;
      }
      for (JsonNode gate : request.get(field)) {
        if ("CI".equals(text(gate, "kind"))) {
          return text(gate, "state");
        }
      }
    }
    return null;
  }

  /**
   * What qits-projects says has become of one release request.
   *
   * <p>Every state falls on exactly one of three sides, and the side is what a hold acts on:
   *
   * <ul>
   *   <li><b>Waited on</b> — {@link #ON_ITS_WAY} (PENDING, READY: the release is coming) and {@link
   *       #SHIPPED} (RELEASED, FINALIZED, OBSOLETE: a version was cut, and the next scan of main
   *       ends the hold by emptying the pending set).
   *   <li><b>{@link #withdrawn() Withdrawn}</b> — no request at all. Owner decision 2026-10-04,
   *       qits-886: "treat withdrawn the same as non existing", a person's withdrawal included. The
   *       dispatcher clears the bump's request id, and the sweep asks qits-projects for a fresh
   *       request while the branch is still pushed and ahead of main.
   *   <li><b>{@link #stalled() Stalled}</b> — REJECTED, FAILED, CONFLICTED, and anything this
   *       service does not know the name of.
   * </ul>
   *
   * @param state the state verbatim — {@code PENDING}, {@code READY}, {@code RELEASED}, {@code
   *     FINALIZED}, {@code OBSOLETE}, {@code REJECTED}, {@code FAILED}, {@code CONFLICTED}, {@code
   *     WITHDRAWN} — or null when it could not be read
   * @param detail that service's own sentence about why, when it has one ({@code detail}, or the
   *     conflict when the fold is what failed)
   * @param error why it could not be read, or null
   * @param mergedSha the request's current FOLD — the sha its gates settle and the one a
   *     release-request automation compares its own fold against: a row whose fold is not this one
   *     has been SUPERSEDED. Null when unread or not folded yet
   * @param branches the request's named BRANCH sources, in the order the answer lists them — what an
   *     automation that writes onto the request's own branches (estate pins) reads when a re-run
   *     carries no list of its own. Empty when unread
   * @param unknown qits-projects answered 404: it holds no such request. Unreadable like any other
   *     failure, and kept apart only because the automation-branch sweep may act on it — every other
   *     reader treats it as the peer that could not be asked, which is what it was before
   */
  public record ReleaseState(
      String state,
      String detail,
      String error,
      String mergedSha,
      List<String> branches,
      boolean unknown) {

    /** The three-field answer every reader before the automations needed. */
    public ReleaseState(String state, String detail, String error) {
      this(state, detail, error, null, List.of());
    }

    /** Every answer that is not a 404. */
    public ReleaseState(
        String state, String detail, String error, String mergedSha, List<String> branches) {
      this(state, detail, error, mergedSha, branches, false);
    }

    public ReleaseState {
      branches = branches == null ? List.of() : List.copyOf(branches);
    }

    /** The states in which a release is still on its way. */
    private static final Set<String> ON_ITS_WAY = Set.of("PENDING", "READY");

    /**
     * The states in which a version has been cut. <b>FINALIZED and OBSOLETE are here, not among
     * the stalled</b>: FINALIZED is RELEASED with main merged, and qits-projects marks a request
     * OBSOLETE only once it is RELEASED and before it finalized — both shipped. Reading them as
     * stalled reported repositories whose bump had landed as waiting on a release that stopped
     * (qits-events-service, 2026-10-04).
     */
    private static final Set<String> SHIPPED = Set.of("RELEASED", "FINALIZED", "OBSOLETE");

    private static final String WITHDRAWN = "WITHDRAWN";

    /** Whether qits-projects answered at all. */
    public boolean readable() {
      return state != null;
    }

    /**
     * <b>Whether the request is gone</b> — withdrawn, by a person or by qits-projects, which counts
     * as no request at all. Not stalled: a fresh request is asked for instead.
     */
    public boolean withdrawn() {
      return WITHDRAWN.equals(state);
    }

    /**
     * <b>Whether nothing is coming from this request as it stands.</b> Red gates, a mechanical
     * failure, a fold that will not apply — none of them moves {@code main}, so a bump waiting on
     * one is waiting for something that has stopped happening. A withdrawal is not among them (see
     * {@link #withdrawn()}), and neither is a request that shipped.
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
      return state != null
          && !ON_ITS_WAY.contains(state)
          && !SHIPPED.contains(state)
          && !withdrawn();
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
          null,
          null,
          "the release request " + requestId + " could not be read: " + answer.failure(),
          null,
          List.of(),
          answer.notFound());
    }
    JsonNode request = answer.json() == null ? null : answer.json().get("request");
    String state = text(request, "state");
    if (state == null) {
      return new ReleaseState(
          null, null, "the release request " + requestId + " answered no state");
    }
    String detail = text(request, "detail");
    return new ReleaseState(
        state,
        detail == null ? conflict(request) : detail,
        null,
        text(request, "mergedSha"),
        branchSources(request));
  }

  /**
   * The request's named BRANCH sources, read off the same answer. A tag source is no branch anybody
   * commits to, and an entry with no kind is read as a branch — the older shape carried none.
   */
  private static List<String> branchSources(JsonNode request) {
    List<String> branches = new ArrayList<>();
    if (request == null || !request.hasNonNull("sources") || !request.get("sources").isArray()) {
      return branches;
    }
    for (JsonNode source : request.get("sources")) {
      String kind = text(source, "kind");
      String name = text(source, "name");
      if (name != null && (kind == null || "BRANCH".equalsIgnoreCase(kind))) {
        branches.add(name);
      }
    }
    return branches;
  }

  /**
   * The commit subject a bump's own commits carry, reused as the release summary.
   *
   * <p><b>{@code bump(<group>): <n> dependencies}</b> — the literal shape {@code
   * ci/src/main/resources/platform-pipelines/maintenance-bump.yml} (in qits-ci) prints, word for word, the plural never
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

  /**
   * The request's merge conflict as one sentence, or null. qits-projects answers it as an object —
   * {@code target} and {@code conflicts[]} of {@code path} and {@code head} — not as text.
   */
  static String conflict(JsonNode request) {
    JsonNode conflict = request == null ? null : request.get("conflict");
    if (conflict == null || !conflict.isObject()) {
      return null;
    }
    List<String> paths = new ArrayList<>();
    JsonNode conflicts = conflict.get("conflicts");
    if (conflicts != null && conflicts.isArray()) {
      for (JsonNode one : conflicts) {
        String path = text(one, "path");
        if (path != null) {
          String head = text(one, "head");
          paths.add(head == null ? path : path + " (" + head + ")");
        }
      }
    }
    String target = text(conflict, "target");
    StringBuilder sentence = new StringBuilder("the fold conflicted");
    if (target != null) {
      sentence.append(" on ").append(target);
    }
    if (!paths.isEmpty()) {
      sentence.append(": ").append(String.join(", ", paths));
    }
    return sentence.toString();
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

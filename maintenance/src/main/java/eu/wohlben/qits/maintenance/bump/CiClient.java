package eu.wohlben.qits.maintenance.bump;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.maintenance.bump.changelog.ChangelogRange;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.pending.Change;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The calls this service makes to qits-ci: apply a bump, read the run that applies it (and the
 * automatic retry qits-ci re-fired it as), and ask how much room qits-ci has.
 *
 * <p><b>{@code eventId} is the bump's row id and that is the dedupe key.</b> qits-ci records at most
 * one run per (event id, repository, config path), so a dispatch whose ANSWER this service lost —
 * a timeout after qits-ci already accepted it — records no second run when it is retried. Without
 * it a retry would be a second branch commit for changes that were already applied.
 *
 * <p><b>503 is a retry, not a failure.</b> qits-ci answers it when the evaluation reached no
 * readable repository, which is a git host that is briefly away rather than a pipeline that
 * refused. The bump stays REQUESTED with its changes and the poller dispatches it again.
 *
 * <p><b>200 with no run id is NOT SUCCESS.</b> qits-ci records a run only if the repository the
 * payload names was READABLE in that same evaluation, so an empty {@code runIds} means one of two
 * things: the platform pipeline did not match — it is not there, or its {@code event:} does not say
 * {@code MaintenanceBump} — or the repository could not be read this time. Nothing is running
 * either way, so the bump is FAILED and the next scheduled scan asks again. Treating it as success
 * would report a branch that was never written.
 *
 * <p><b>An internal change may carry a {@code changelog}</b> (epic qits-893): {@code {repository,
 * versions}}, naming the changelogs of the releases it pulls in, which {@link
 * eu.wohlben.qits.maintenance.bump.changelog.ChangelogRanges} proved published before the trigger.
 * A change with none carries no such key. The texts never ride here — the payload reaches the step
 * as one environment string — the step's CLI fetches them.
 *
 * <p><b>{@code payload.repository} is the repository's public NAME</b>, which qits-ci resolves
 * against its candidate list — qits-projects' catalog, the same listing this service scans from. An
 * id would name nothing over there.
 */
@ApplicationScoped
public class CiClient {

  /** The event name the bump pipeline selects on. */
  public static final String EVENT_NAME = "MaintenanceBump";

  /**
   * The event qits-ci's one shared release-request-automation core selects on, by the payload's
   * {@code kind} (qits-978). Every kind whose {@code pipeline()} names it is a kind file under
   * {@link #AUTOMATION_CONFIG_DIR} over there.
   */
  public static final String AUTOMATION_EVENT_NAME = "ReleaseRequestAutomation";

  /** Where qits-ci packages the kind files the automation core composes, {@code <kind>.yml}. */
  public static final String AUTOMATION_CONFIG_DIR = "ci/src/main/resources/platform-pipelines/automations/";

  public static final String TRIGGER_PATH = "/ci/api/events/trigger";

  /**
   * qits-ci's queue snapshot: everything it has accepted and not finished, across every
   * repository, AND the runners that execute it — the capacity half the dispatch gate reads.
   */
  public static final String QUEUE_PATH = "/ci/api/runs/queue";

  /** The bump pipeline, packaged into qits-ci under this path. qits-ci records it as a run's {@code
   * configPath}, and it is the same for every bump, so the bump detail carries it as a constant
   * rather than reading it back per run. */
  public static final String CONFIG_PATH = "ci/src/main/resources/platform-pipelines/maintenance-bump.yml";

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject PeerClient peers;

  /**
   * What qits-ci said about a trigger.
   *
   * @param outcome how to treat it
   * @param eventId the id the evaluation ran under
   * @param runIds the runs that now exist
   * @param message the sentence, for a row's message column
   */
  public record TriggerResult(Outcome outcome, String eventId, List<String> runIds, String message) {

    public enum Outcome {
      /** qits-ci accepted the trigger and named at least one run. */
      ACCEPTED,

      /** qits-ci could not evaluate it now. Retry with the same event id. */
      RETRY,

      /** No pipeline answered, or the call was refused. Nothing is running. */
      FAILED
    }
  }

  /**
   * Asks qits-ci to apply one group's changes.
   *
   * @param bumpId the {@code mt_bump} row id, which travels as the event's dedupe key
   */
  public TriggerResult trigger(
      String bumpId, String repository, String group, String branch, String baseRef, List<Change> changes) {
    return trigger(bumpId, repository, group, branch, baseRef, changes, Map.of());
  }

  /** The same trigger with extra top-level payload fields, for the bump pipeline. */
  public TriggerResult trigger(
      String bumpId,
      String repository,
      String group,
      String branch,
      String baseRef,
      List<Change> changes,
      Map<String, String> extra) {
    return trigger(bumpId, repository, group, branch, baseRef, changes, extra, Map.of());
  }

  /**
   * The bump trigger with the changelogs its changes pull in (epic qits-893).
   *
   * @param changelogs the range of every change that has one, as {@link
   *     eu.wohlben.qits.maintenance.bump.changelog.ChangelogRanges} resolved it; a change with none
   *     is sent without a {@code changelog} key
   */
  public TriggerResult trigger(
      String bumpId,
      String repository,
      String group,
      String branch,
      String baseRef,
      List<Change> changes,
      Map<String, String> extra,
      Map<Change, ChangelogRange> changelogs) {
    return trigger(
        EVENT_NAME, bumpId, repository, group, branch, baseRef, changes, extra, changelogs);
  }

  /**
   * The trigger, naming the event and so the qits-ci pipeline that answers it — {@link #EVENT_NAME}
   * for a bump and for an automation that writes a request's own branches. {@code extra} adds
   * top-level payload fields, such as an automation's {@code kind}.
   */
  public TriggerResult trigger(
      String eventName,
      String bumpId,
      String repository,
      String group,
      String branch,
      String baseRef,
      List<Change> changes,
      Map<String, String> extra) {
    return trigger(eventName, bumpId, repository, group, branch, baseRef, changes, extra, Map.of());
  }

  /**
   * The same, with the changelogs: <b>each change that has a range carries {@code "changelog":
   * {"repository": "…", "versions": ["…"]}}</b> beside its six plan fields, and a change without
   * one carries no {@code changelog} key at all — never a null, never an empty list, so a step
   * older than the field reads exactly the payload it always did.
   *
   * <p>The field NAMES the changelogs and carries none of their text: the payload reaches the step
   * as one environment string capped at about 128 KiB, so the step's {@code qits changelog
   * bump-message} fetches them from the docs store itself.
   */
  public TriggerResult trigger(
      String eventName,
      String bumpId,
      String repository,
      String group,
      String branch,
      String baseRef,
      List<Change> changes,
      Map<String, String> extra,
      Map<Change, ChangelogRange> changelogs) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("repository", repository);
    payload.put("group", group);
    payload.put("branch", branch);
    payload.put("baseRef", baseRef);
    payload.set("changes", changes(changes, changelogs));
    extra.forEach(payload::put);
    return trigger(eventName, bumpId, payload);
  }

  /**
   * The {@code changes} array: each change as the plan spells it, plus its changelog if any. Shared
   * by both pipelines that carry changes — the {@code MaintenanceBump} trigger and the dependency
   * bump's {@code ReleaseRequestAutomation} payload — so a changelog is spelled once.
   */
  public static ArrayNode changes(List<Change> changes, Map<Change, ChangelogRange> changelogs) {
    ArrayNode array = JSON.createArrayNode();
    for (Change change : changes) {
      ObjectNode node = JSON.valueToTree(change);
      ChangelogRange range = changelogs == null ? null : changelogs.get(change);
      if (range != null) {
        ObjectNode changelog = node.putObject("changelog");
        changelog.put("repository", range.repository());
        ArrayNode versions = changelog.putArray("versions");
        range.versions().forEach(versions::add);
      }
      array.add(node);
    }
    return array;
  }

  /**
   * One release-request automation's trigger on qits-ci's shared core, {@link
   * #AUTOMATION_EVENT_NAME}: <b>{@code {kind, repository, requestId, foldSha, baseRef, branch,
   * commitPaths, workItem?, …extras}}</b>.
   *
   * <p>The core's prelude holds every one of them to a rule before the kind's script runs — {@code
   * branch} under {@code maintenance/automations/<kind>/}, {@code baseRef} under {@code release/}, a
   * hex {@code foldSha} the fetched fold must still equal ("superseded before start") — and its
   * postlude stages {@code commitPaths} and nothing else. So the paths travel as a JSON ARRAY of git
   * pathspecs, never as one string a shell would split.
   *
   * @param bumpId the {@code mt_bump} row id, the event's dedupe key as for every trigger here
   * @param workItem the commit subject's scope, or null: the prelude then takes it from the fold
   * @param extras the kind's plan's own fields, added at the top level and never over one above
   */
  public TriggerResult triggerAutomation(
      String bumpId,
      String kind,
      String repository,
      String requestId,
      String foldSha,
      String baseRef,
      String branch,
      List<String> commitPaths,
      String workItem,
      Map<String, Object> extras) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("kind", kind);
    payload.put("repository", repository);
    payload.put("requestId", requestId);
    payload.put("foldSha", foldSha);
    payload.put("baseRef", baseRef);
    payload.put("branch", branch);
    payload.set("commitPaths", JSON.valueToTree(commitPaths));
    if (workItem != null) {
      payload.put("workItem", workItem);
    }
    if (extras != null) {
      extras.forEach((key, value) -> {
        if (!payload.has(key)) {
          payload.set(key, JSON.valueToTree(value));
        }
      });
    }
    return trigger(AUTOMATION_EVENT_NAME, bumpId, payload);
  }

  /** The trigger itself, whatever its payload: the post and the reading of its three answers. */
  private TriggerResult trigger(String eventName, String bumpId, ObjectNode payload) {
    ObjectNode body = JSON.createObjectNode();
    body.put("name", eventName);
    body.put("eventId", bumpId);
    body.set("payload", payload);

    PeerExchange exchange = peers.post(PeerTarget.CI, TRIGGER_PATH, body.toString());
    PeerAnswer answer = exchange.answer();

    if (answer.httpStatus() != null && answer.httpStatus() == 503) {
      return new TriggerResult(
          TriggerResult.Outcome.RETRY,
          bumpId,
          List.of(),
          "qits-ci could not evaluate the trigger yet; it will be sent again");
    }
    if (!answer.ok()) {
      return new TriggerResult(
          TriggerResult.Outcome.FAILED,
          bumpId,
          List.of(),
          "qits-ci refused the trigger: " + answer.failure());
    }
    JsonNode result = answer.json();
    List<String> runIds = runIds(result);
    if (runIds.isEmpty()) {
      return new TriggerResult(
          TriggerResult.Outcome.FAILED,
          eventId(result, bumpId),
          List.of(),
          "no run recorded for " + eventName
              + " (repository unreadable or no platform pipeline)");
    }
    return new TriggerResult(TriggerResult.Outcome.ACCEPTED, eventId(result, bumpId), runIds, null);
  }

  /**
   * One run's state.
   *
   * <p><b>The last four fields are what following qits-ci's automatic infra retry needs</b>
   * (qits-760). qits-ci re-fires a run whose step the infrastructure failed (a runner that
   * disconnected, a container that never started) as a NEW run, and leaves the original row {@code
   * FAILED} for good: its {@code BuildFailed} is never announced, and the only link between the two
   * is the retry's {@code retryOfRunId} with {@code autoRetry} true. The original's failing step
   * also ends with the line {@code [infra failure (…) — retried automatically as run <id>]}, which
   * is read into {@code retriedAs} — a candidate only, held to the retry's own link before it is
   * believed.
   *
   * @param status the CI status verbatim, or null when the run could not be read
   * @param error why it could not be read
   * @param repoId the repository qits-ci records the run under, for listing its siblings, or null
   * @param retryOfRunId the run this one re-fires, or null
   * @param autoRetry whether qits-ci fired it itself, for an infra failure of {@code retryOfRunId}
   * @param finishedAt when it ended, or null
   * @param retriedAs the run its step output says it was retried as, or null
   * @param failure the step that failed it and why, or null — on a run that did not fail, on one
   *     read from a listing (which carries no steps), and on one whose steps name no failure
   *     (qits-1116)
   */
  public record RunState(
      String status,
      String error,
      String repoId,
      String retryOfRunId,
      boolean autoRetry,
      Instant finishedAt,
      String retriedAs,
      Failure failure) {

    public RunState(String status, String error) {
      this(status, error, null, null, false, null, null, null);
    }

    public RunState(
        String status,
        String error,
        String repoId,
        String retryOfRunId,
        boolean autoRetry,
        Instant finishedAt,
        String retriedAs) {
      this(status, error, repoId, retryOfRunId, autoRetry, finishedAt, retriedAs, null);
    }

    /**
     * The statuses nothing further happens after — qits-ci's own list ({@code CiRunStatus}):
     * {@code SUCCESS}, {@code FAILED}, {@code CANCELLED}, {@code CONFIG_ERROR} and {@code
     * TIMED_OUT}. A run left out of this list reads as still going, which is what used to happen to
     * a run that ran out of qits-ci's clock: {@code TIMED_OUT} was missing here, so such a run's
     * bump or automation stayed RUNNING forever (qits-760 follow-up).
     */
    public boolean terminal() {
      return status != null
          && List.of("SUCCESS", "FAILED", "CANCELLED", "CONFIG_ERROR", "TIMED_OUT")
              .contains(status);
    }

    /** Only one of the terminal statuses means the step did its work. */
    public boolean passed() {
      return "SUCCESS".equals(status);
    }

    /** The one status qits-ci re-fires an infra failure from. */
    public boolean failed() {
      return "FAILED".equals(status);
    }

    /**
     * A deadline rather than a verdict — qits-ci's own line, {@code CiRunStatus#TIMED_OUT}'s
     * javadoc. qits-ci deliberately never auto-retries one (a timeout is outside its infra-failure
     * set), but {@link BumpService#followRetries} still checks, the same way it does for {@link
     * #failed()}: harmless when qits-ci never answers one, and correct if that ever changes.
     */
    public boolean timedOut() {
      return "TIMED_OUT".equals(status);
    }

    /** Whether this run is qits-ci's automatic retry of that one — the link, and nothing else. */
    public boolean automaticRetryOf(String runId) {
      return autoRetry && runId != null && runId.equals(retryOfRunId);
    }
  }

  /**
   * <b>Why a run went red</b> (qits-1116): the step that failed it, its exit code and a few lines
   * of its log. Without it every red automation read "its step log says why" and a person had to
   * open the run to learn which of a dozen things it was.
   *
   * @param stepIndex the failing step's index in the run
   * @param image the image that step ran, or null when the run did not say
   * @param exitCode its exit code, or null when it has none (a step the infrastructure failed)
   * @param excerpt a few lines of its output that say why — see {@link FailureExcerpt} — or null
   *     when it wrote nothing
   */
  public record Failure(int stepIndex, String image, Integer exitCode, String excerpt) {

    /** The first excerpt line, or null when there is no excerpt. */
    public String firstLine() {
      if (excerpt == null || excerpt.isBlank()) {
        return null;
      }
      int newline = excerpt.indexOf('\n');
      return newline < 0 ? excerpt : excerpt.substring(0, newline);
    }
  }

  /**
   * The failing step of a run body: the lowest-index step with status {@code FAILED}, else the
   * last step that exited non-zero, else null. Package-visible for the test that pins it.
   */
  static Failure failure(JsonNode body) {
    JsonNode steps = body == null ? null : body.get("steps");
    if (steps == null || !steps.isArray()) {
      return null;
    }
    JsonNode failed = null;
    JsonNode nonZero = null;
    for (JsonNode step : steps) {
      if ("FAILED".equals(text(step, "status"))
          && (failed == null || stepIndex(step) < stepIndex(failed))) {
        failed = step;
      }
      Integer exit = exitCode(step);
      if (exit != null && exit != 0) {
        nonZero = step;
      }
    }
    JsonNode step = failed != null ? failed : nonZero;
    if (step == null) {
      return null;
    }
    return new Failure(
        stepIndex(step),
        text(step, "image"),
        exitCode(step),
        FailureExcerpt.of(text(step, "output")));
  }

  private static int stepIndex(JsonNode step) {
    return step.path("stepIndex").asInt(0);
  }

  private static Integer exitCode(JsonNode step) {
    JsonNode value = step.get("exitCode");
    return value == null || !value.canConvertToInt() ? null : value.asInt();
  }

  /** The line qits-ci appends to an infra-failed step once its automatic retry exists. */
  private static final Pattern RETRIED_AS =
      Pattern.compile("retried automatically as run ([0-9A-Za-z][0-9A-Za-z-]{0,63})");

  /**
   * How far back {@link #automaticRetryOf} looks in a repository's run listing. A retry is
   * recorded the moment its original fails, so it is near the top; this bounds the read.
   */
  static final int RETRY_LOOKUP_LIMIT = 100;

  /** Reads one run. */
  public RunState run(String runId) {
    PeerAnswer answer = peers.get(PeerTarget.CI, "/ci/api/runs/" + runId).answer();
    if (!answer.ok()) {
      return new RunState(null, "the run " + runId + " could not be read: " + answer.failure());
    }
    JsonNode body = answer.json();
    if (body == null || !body.hasNonNull("status")) {
      return new RunState(null, "the run " + runId + " answered no status");
    }
    return state(body, retriedAs(body));
  }

  /**
   * What a search for a run's automatic retry found.
   *
   * @param readable whether the listing could be read at all — an unreadable one is not "none"
   * @param retryId the retry, or null when there is none (yet)
   * @param retry the retry's state as the listing carried it, or null
   */
  public record RetryLookup(boolean readable, String retryId, RunState retry) {
    static RetryLookup none() {
      return new RetryLookup(true, null, null);
    }

    static RetryLookup unreadable() {
      return new RetryLookup(false, null, null);
    }
  }

  /**
   * qits-ci's automatic retry of {@code runId}, found by the retry's own {@code retryOfRunId} link
   * in its repository's newest runs. qits-ci names the retry nowhere on the original's row, so this
   * is the one place the link can be read from the original's side.
   *
   * @param repoId the repository the original ran under, from its own row
   */
  public RetryLookup automaticRetryOf(String repoId, String runId) {
    if (repoId == null || repoId.isBlank() || runId == null) {
      return RetryLookup.none();
    }
    String path =
        "/ci/api/runs?repositoryId="
            + URLEncoder.encode(repoId, StandardCharsets.UTF_8)
            + "&limit="
            + RETRY_LOOKUP_LIMIT;
    PeerAnswer answer = peers.get(PeerTarget.CI, path).answer();
    if (!answer.ok()) {
      return RetryLookup.unreadable();
    }
    JsonNode body = answer.json();
    if (body == null || !body.hasNonNull("runs") || !body.get("runs").isArray()) {
      return RetryLookup.unreadable();
    }
    for (JsonNode run : body.get("runs")) {
      if (!run.hasNonNull("id") || !run.hasNonNull("status")) {
        continue;
      }
      RunState state = state(run, null);
      if (state.automaticRetryOf(runId)) {
        return new RetryLookup(true, run.get("id").asText(), state);
      }
    }
    return RetryLookup.none();
  }

  private static RunState state(JsonNode body, String retriedAs) {
    return new RunState(
        body.get("status").asText(),
        null,
        text(body, "repoId"),
        text(body, "retryOfRunId"),
        body.path("autoRetry").asBoolean(false),
        instant(text(body, "finishedAt")),
        retriedAs,
        failure(body));
  }

  /** The run id the newest "retried automatically as run" line of any step names, or null. */
  private static String retriedAs(JsonNode body) {
    String found = null;
    JsonNode steps = body.get("steps");
    if (steps == null || !steps.isArray()) {
      return null;
    }
    for (JsonNode step : steps) {
      String output = text(step, "output");
      if (output == null) {
        continue;
      }
      Matcher matcher = RETRIED_AS.matcher(output);
      while (matcher.find()) {
        found = matcher.group(1);
      }
    }
    return found;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || !value.isValueNode() || value.isNull() ? null : value.asText();
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      try {
        // A numeric timestamp, should the serializer write one: seconds with a fraction.
        return Instant.ofEpochMilli(Math.round(Double.parseDouble(value) * 1000));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
  }

  /**
   * How much room qits-ci has right now.
   *
   * @param slots how many runs its connected, unquarantined runners execute at once, or null when
   *     the snapshot could not be read
   * @param active how many runs it has accepted and not finished, or null when the snapshot could
   *     not be read
   * @param error why it could not be read
   */
  public record QueueState(Integer slots, Integer active, String error) {

    /**
     * Whether the snapshot answered at all. <b>An unreadable queue is not an empty one</b>: a
     * dispatch gate that read "could not ask" as "nothing is going" would fire the whole owed set
     * at the one moment qits-ci is least able to say so.
     */
    public boolean readable() {
      return slots != null && active != null;
    }

    static QueueState unreadable(String error) {
      return new QueueState(null, null, error);
    }
  }

  /**
   * qits-ci's capacity and what is already using it, from one read of {@code GET
   * /ci/api/runs/queue}.
   *
   * <p><b>{@code slots} is qits-ci's own rule, {@code CiQueueForecast.slotCount}</b>: the sum of
   * {@code max(0, slots)} over the runners that are {@code connected} and not {@code quarantined}.
   * A runner that is not connected would claim nothing whatever its row grants, and a quarantined
   * one takes nothing but its health check — counting either would hand qits-ci builds nobody is
   * going to run. A field that is missing reads as the cautious answer: not connected, not
   * quarantined, no slots.
   *
   * <p><b>{@code active} is every entry of {@code running} and {@code queued}, whatever its status
   * says.</b> qits-ci partitions QUEUED from everything else, so the honest reading of "how much is
   * going" is the SIZE of both halves, not a filter over a vocabulary. A third non-terminal status
   * qits-ci invents tomorrow is still work in flight, and a gate matching on a status would quietly
   * stop seeing it.
   *
   * <p>A snapshot missing any of the three arrays is UNREADABLE rather than empty, for the reason
   * {@link QueueState#readable()} gives.
   */
  public QueueState queue() {
    PeerAnswer answer = peers.get(PeerTarget.CI, QUEUE_PATH).answer();
    if (!answer.ok()) {
      return QueueState.unreadable("the queue could not be read: " + answer.failure());
    }
    JsonNode body = answer.json();
    for (String field : List.of("running", "queued", "runners")) {
      if (body == null || !body.hasNonNull(field) || !body.get(field).isArray()) {
        return QueueState.unreadable("the queue snapshot carried no `" + field + "` array");
      }
    }
    int slots = 0;
    for (JsonNode runner : body.get("runners")) {
      boolean connected = runner.path("connected").asBoolean(false);
      boolean quarantined = runner.path("quarantined").asBoolean(false);
      if (connected && !quarantined) {
        slots += Math.max(0, runner.path("slots").asInt(0));
      }
    }
    return new QueueState(slots, body.get("running").size() + body.get("queued").size(), null);
  }

  private static List<String> runIds(JsonNode result) {
    List<String> ids = new ArrayList<>();
    if (result == null || !result.hasNonNull("runIds") || !result.get("runIds").isArray()) {
      return ids;
    }
    for (JsonNode id : result.get("runIds")) {
      if (id.isTextual() && !id.asText().isBlank()) {
        ids.add(id.asText());
      }
    }
    return ids;
  }

  private static String eventId(JsonNode result, String fallback) {
    return Optional.ofNullable(result)
        .map(node -> node.get("eventId"))
        .filter(JsonNode::isTextual)
        .map(JsonNode::asText)
        .orElse(fallback);
  }
}

package eu.wohlben.qits.maintenance.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-maintenance's provider golden masters</b> — {@code golden-masters/} at the repository
 * root, the source of the published golden-master artifact consumers write their pacts against.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), calls the endpoint through REST-assured as the {@code %test} dev user, keeps
 * only the list entries the state created, freezes ids, instants and unique tokens ({@link
 * Freezer}) and renders {@code golden-masters/<state-slug>/<operationId>.json}; then it renders
 * {@code golden-masters/index.json} describing all of them.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
@QuarkusTest
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;
  static final String PROVIDER = "qits-maintenance";

  /**
   * One recorded interaction.
   *
   * @param listFilteredTo the array (a {@code $.a.b} path) reduced to the entries the state created,
   *     or null — the index's {@code frozen.listFilteredTo}
   * @param sortedBy for an array the provider answers in no guaranteed order: {@code
   *     <$.path-to-array>:<field.path in each entry>}, sorted by that field's (seed-fixed) value
   *     before freezing, so ids are numbered in a stable order. Null when the order is the
   *     provider's own.
   * @param query the query, apart from the path; a value that is exactly {@code {param}} is
   *     expanded from the state's params and recorded unexpanded
   * @param requestBody the JSON a write sends, recorded into the index as the operation's {@code
   *     body}; null for a read and for a write whose operation takes no body (the served openapi
   *     says which, and the recording fails on a mismatch). A {@code {param}} in it is expanded from
   *     the state's params, and recorded unexpanded
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      Map<String, String> query,
      int status,
      String listFilteredTo,
      String sortedBy,
      List<String> dropped,
      String requestBody) {

    /** An interaction with no query, nothing dropped and no body. */
    Interaction(
        String state,
        String operationId,
        String method,
        String path,
        int status,
        String listFilteredTo,
        String sortedBy) {
      this(
          state, operationId, method, path, Map.of(), status, listFilteredTo, sortedBy, List.of(),
          null);
    }

    /** A read: no body, nothing dropped. */
    static Interaction read(
        String state, String operationId, String path, Map<String, String> query, String filter) {
      return new Interaction(
          state, operationId, "GET", path, query, 200, filter, null, List.of(), null);
    }

    /** A write. */
    static Interaction write(
        String state, String operationId, String path, int status, String requestBody) {
      return new Interaction(
          state, operationId, "POST", path, Map.of(), status, null, null, List.of(), requestBody);
    }
  }

  private static final String AUTOMATIONS =
      "/maintenance/api/release-requests/{requestId}/automations";

  /**
   * The pending bumps as the bumps menu asks for them: the newest 20, of every repository, filtered
   * to the state's own bumps; one of them in full; the estate reads qits-artifacts,
   * qits-orchestrator and qits-projects make; and a release request's automations as qits-projects
   * and the CLI settle, read and re-run them.
   */
  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.PENDING_BUMPS,
              "listPendingBumps",
              "GET",
              "/maintenance/api/bumps/pending",
              Map.of("limit", "20"),
              200,
              "$.bumps",
              null,
              List.of(),
              null),
          new Interaction(
              ProviderStates.NO_PENDING_BUMPS,
              "listPendingBumps",
              "GET",
              "/maintenance/api/bumps/pending",
              Map.of("limit", "20"),
              200,
              "$.bumps",
              null,
              List.of(),
              null),
          Interaction.read(
              ProviderStates.PENDING_BUMPS,
              "getBump",
              "/maintenance/api/bumps/{releasedBumpId}",
              Map.of(),
              null),
          Interaction.read(
              ProviderStates.MANIFESTS_THAT_PIN_ARTIFACTS,
              "listDependencyPins",
              "/maintenance/api/pins",
              Map.of(),
              null),
          Interaction.read(
              ProviderStates.A_REPOSITORY_WITH_DOWNSTREAM_COMPONENTS,
              "getRepositoryDownstream",
              "/maintenance/api/repositories/{repositoryId}/downstream",
              Map.of(),
              null),
          // What qits-projects posts on every fold (HttpReleaseRequestAutomations.request).
          Interaction.write(
              ProviderStates.A_RELEASE_REQUEST_WITH_AUTOMATIONS,
              "triggerReleaseRequestAutomations",
              AUTOMATIONS,
              200,
              "{\"repository\":\"{repoName}\",\"foldSha\":\"{foldSha}\","
                  + "\"previousFoldSha\":null,\"changedSincePrevious\":null,"
                  + "\"sourceBranches\":[\"{branch}\"],\"workItem\":null}"),
          Interaction.read(
              ProviderStates.A_RELEASE_REQUEST_WITH_AUTOMATIONS,
              "listReleaseRequestAutomations",
              AUTOMATIONS,
              Map.of("foldSha", "{foldSha}"),
              null),
          // qits-projects' re-run names the repository (HttpReleaseRequestAutomations.rerun)…
          Interaction.write(
              ProviderStates.A_RELEASE_REQUEST_WITH_A_FAILED_AUTOMATION,
              "runReleaseRequestAutomation",
              AUTOMATIONS + "/{kind}/runs",
              202,
              "{\"workItem\":null,\"repository\":\"{repoName}\"}"),
          // …the CLI's names nothing: the repository is the one the request's rows name.
          Interaction.write(
              ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN,
              "runReleaseRequestAutomation",
              AUTOMATIONS + "/{kind}/runs",
              202,
              "{}"));

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)}");

  /** A {@code {param}} inside a JSON body: a name in braces, which JSON's own braces never are. */
  private static final Pattern BODY_PARAM = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

  @Inject ProviderStates states;

  @Test
  void goldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    // slug -> recorded state, sorted by slug; operations sorted by operationId below
    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    Set<String> takesBody = operationsTakingABody();
    for (Interaction interaction : INTERACTIONS) {
      if ((interaction.requestBody() != null) != takesBody.contains(interaction.operationId())) {
        failures.add(
            interaction.operationId()
                + (interaction.requestBody() != null
                    ? " takes no request body, but the recording sends one: record null."
                    : " takes a request body, but the recording sends none."));
      }
      Recorded recorded = record(interaction);
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", recorded.params());
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(recorded.params())) {
        failures.add("State '" + interaction.state() + "' froze to different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      if (!interaction.query().isEmpty()) {
        ObjectNode query = operation.putObject("query");
        new TreeMap<>(interaction.query()).forEach(query::put);
      }
      if (interaction.requestBody() != null) {
        operation.set("body", JSON.readTree(interaction.requestBody()));
      }
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      if (interaction.listFilteredTo() == null) {
        frozen.putNull("listFilteredTo");
      } else {
        frozen.put("listFilteredTo", interaction.listFilteredTo());
      }
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /** One interaction's frozen answer, its frozen params and what was frozen where. */
  record Recorded(JsonNode body, ObjectNode params, Freezer freezer) {}

  private Recorded record(Interaction interaction) throws IOException {
    ProviderStates.Setup setup = states.setUp(interaction.state());
    try {
      return recordIn(interaction, setup);
    } finally {
      states.cleanUp();
    }
  }

  private Recorded recordIn(Interaction interaction, ProviderStates.Setup setup) throws IOException {
    Map<String, String> params = setup.params();

    Map<String, String> query = new TreeMap<>();
    interaction.query().forEach((k, v) -> query.put(k, expand(v, params, TEMPLATE_PARAM)));
    var request = given().queryParams(query);
    if (interaction.requestBody() != null) {
      request =
          request
              .contentType("application/json")
              .body(expand(interaction.requestBody(), params, BODY_PARAM));
    } else {
      // As a browser sends a body-less call: RestAssured would otherwise add a form content type.
      request = request.noContentType();
    }
    Response response =
        request.when().request(interaction.method(), expand(interaction.path(), params));
    String raw = response.asString();
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + raw);
    }
    JsonNode body = JSON.readTree(raw);
    if (body.isObject()) {
      interaction.dropped().forEach(((ObjectNode) body)::remove);
    }
    body = recordable(body, interaction, params.values(), setup.uniqueTokens());

    Freezer freezer = new Freezer().seed(params.values()).uniqueTokens(setup.uniqueTokens());
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
    return new Recorded(freezer.freeze(body), frozenParams, freezer);
  }

  /**
   * The answer reduced to what the state controls: the {@code listFilteredTo} array keeps only the
   * entries mentioning an id the state created (its param values), and a {@code sortedBy} array is
   * put in seed order — by the field's value with the state's unique tokens blanked out, since a
   * random token would otherwise decide where its entry sorts. Package-private for the machinery
   * test.
   */
  static JsonNode recordable(
      JsonNode body,
      Interaction interaction,
      Collection<String> createdIds,
      Collection<String> uniqueTokens) {
    JsonNode out = body.deepCopy();
    if (interaction.listFilteredTo() != null) {
      ArrayNode list = array(out, interaction.listFilteredTo());
      ArrayNode kept = JsonNodeFactory.instance.arrayNode();
      for (JsonNode entry : list) {
        String text = entry.toString();
        if (createdIds.stream().anyMatch(text::contains)) {
          kept.add(entry);
        }
      }
      list.removeAll();
      list.addAll(kept);
    }
    if (interaction.sortedBy() != null) {
      String[] parts = interaction.sortedBy().split(":", 2);
      ArrayNode list = array(out, parts[0]);
      List<JsonNode> entries = new ArrayList<>();
      list.forEach(entries::add);
      String[] field = parts[1].split("\\.");
      entries.sort(
          Comparator.comparing(
              entry -> {
                JsonNode node = entry;
                for (String f : field) {
                  node = node.path(f);
                }
                String key = node.asText();
                for (String token : uniqueTokens) {
                  key = key.replace(token, "");
                }
                return key;
              }));
      list.removeAll();
      list.addAll(entries);
    }
    return out;
  }

  /** The array at a {@code $.a.b} path, or the root itself at {@code $}. */
  private static ArrayNode array(JsonNode root, String path) {
    if (path.equals("$")) {
      if (!root.isArray()) {
        throw new IllegalStateException("$ is not an array in " + root);
      }
      return (ArrayNode) root;
    }
    if (!path.startsWith("$.")) {
      throw new IllegalArgumentException("Only $.a.b paths are supported: " + path);
    }
    JsonNode node = root;
    for (String segment : path.substring(2).split("\\.")) {
      node = node.path(segment);
    }
    if (!node.isArray()) {
      throw new IllegalStateException(path + " is not an array in " + root);
    }
    return (ArrayNode) node;
  }

  private static String expand(String template, Map<String, String> params) {
    return expand(template, params, TEMPLATE_PARAM);
  }

  private static String expand(String template, Map<String, String> params, Pattern pattern) {
    Matcher m = pattern.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            "Path " + template + " names {" + m.group(1) + "}, which the state does not return");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** The operationIds whose operation declares a request body, read off the served openapi. */
  private static Set<String> operationsTakingABody() throws IOException {
    JsonNode paths =
        JSON.readTree(
                given()
                    .when()
                    .get("/maintenance/q/openapi?format=json")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString())
            .path("paths");
    Set<String> ids = new TreeSet<>();
    paths.forEach(
        path ->
            path.forEach(
                operation -> {
                  if (operation.has("operationId") && operation.has("requestBody")) {
                    ids.add(operation.get("operationId").asText());
                  }
                }));
    return ids;
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    values.forEach(out::add);
    return out;
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}

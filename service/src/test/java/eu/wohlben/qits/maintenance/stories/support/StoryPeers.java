package eu.wohlben.qits.maintenance.stories.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * <b>The far side of this catalogue: one of the five services qits-platform-maintenance reads or
 * writes, standing on a real port, recording what it was asked and answerable differently in the
 * middle of a story.</b>
 *
 * <p>This service is a <em>reader of other repositories</em>. Everything it knows it learned from
 * qits-projects, qits-githost and three registries, and the two things it makes happen anywhere else
 * are a {@code MaintenanceBump} trigger at qits-ci and a release request opened at qits-projects. A
 * diagram that showed only what a story sent
 * <em>here</em> would document the API and nothing about the service — so the outgoing half is
 * where the evidence is, and this class is what produces it.
 *
 * <h2>Why not {@code FakePeers}</h2>
 *
 * <p>The surefire suite replaces {@link eu.wohlben.qits.maintenance.peer.PeerClient}'s two methods
 * with an {@code @Alternative}, which costs no port and is exactly right there. It cannot serve
 * these stories on three independent counts:
 *
 * <ul>
 *   <li><b>The subject is a launched fast-jar.</b> A CDI alternative lives in the build JVM's
 *       classpath and the packaged process has never heard of it. What these stories are about —
 *       the shipped peer urls, the shipped call timeout, the forward-auth headers the real
 *       {@code HttpClient} attaches — exists only where a real socket is dialled.
 *   <li><b>An {@code @Alternative} records the call, not the answer.</b> {@code FakePeers.calls}
 *       holds what went out; a diagram's label needs the status that came <em>back</em>, which is
 *       the half that makes {@code "GET /projects/api/repositories -> 200"} evidence rather than an
 *       intention.
 *   <li><b>It cannot stop answering mid-story.</b> {@link #reachable(boolean)} closes the exchange
 *       before any status is written, which is what an unreachable git host really looks like to
 *       {@code PeerClient.send} — and the story that proves an unreachable repository KEEPS ITS
 *       PINS has no other way to be told.
 * </ul>
 *
 * <h2>Two processes and three classloaders</h2>
 *
 * <p>The server is dialled by the launched artifact, a different process, so it has to be a real
 * socket on a real port. It is also started from a {@code QuarkusTestProfile}, which Quarkus
 * instantiates in more than one classloader — so a plain static singleton exists twice and the copy
 * a story arms is not the copy the application talks to. Both problems have one answer, the
 * platform's: the address is parked in a <b>system property</b>, the one namespace every
 * classloader in a JVM shares, and every mutation and every read is an HTTP request to it. The
 * second instance is simply a client of the first.
 *
 * <h2>Recording discipline</h2>
 *
 * <ul>
 *   <li><b>A request is recorded BEFORE it is answered</b> — including one that is never answered
 *       at all. A recording written afterwards would miss precisely the case the outage story is
 *       about.
 *   <li><b>The recording is wiped when the server starts and never again.</b> There is no floor and
 *       no reset: {@link eu.wohlben.qits.userflows.NetworkCapture#source} attributes a cumulative
 *       recording with a cursor, and a reset mid-run would re-attribute traffic to whichever story
 *       drained next. One process, one recording, one cursor. It also means <b>boot traffic belongs
 *       to the first story</b>, which is the rule the class ordering exists to honour.
 *   <li><b>The RAW path is recorded, query included.</b> {@code @scope%2fname} is one path segment
 *       to a registry and two to {@code URI.getPath()}; a label built from the decoded form would
 *       show a request this service never sent. Matching is on the decoded form, because that is
 *       what a registry does.
 *   <li><b>The roles header is recorded beside the status.</b> "Outbound, this service is a MACHINE
 *       and nothing else" is a claim in AGENTS.md, and this is where it is checkable: every call
 *       carries {@code X-Qits-Roles: qits:system} and never the human role.
 * </ul>
 */
public final class StoryPeers {

  /** Where a started server parks its address, per name. */
  private static final String ANCHOR_PREFIX = "qits.test.story-peer.";

  /** Everything under this prefix is control traffic and is never recorded and never served. */
  private static final String CONTROL = "/_control/";

  /**
   * The status a recorded line carries when the connection was closed with no response at all — the
   * outage arm. Deliberately a word rather than a number: no status code was on the wire, and
   * writing {@code 000} would put a number in a diagram where none was sent.
   */
  public static final String DROPPED = "dropped";

  /** What a request that carried no roles header records. Nothing this service sends ever does. */
  public static final String NO_ROLES = "-";

  private static final Map<String, StoryPeers> INSTANCES = new ConcurrentHashMap<>();

  private final String name;
  private final String baseUrl;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  // Populated only in the instance that actually started the server; the other one is a client.
  private final Map<String, Answer> answers = new ConcurrentHashMap<>();
  private final List<String> recording = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, List<String>> bodies = new ConcurrentHashMap<>();
  private volatile boolean reachable = true;

  private StoryPeers(String name) {
    this.name = name;
    String anchor = ANCHOR_PREFIX + name;
    String existing = System.getProperty(anchor);
    if (existing != null) {
      this.baseUrl = existing;
      return;
    }
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (Exception unstartable) {
      throw new IllegalStateException("could not start the story peer " + name, unstartable);
    }
    server.createContext("/", this::handle);
    // A pool rather than the caller-runs default: a scan is a sequence on one worker thread, but a
    // story arms an answer from the test thread while that worker is mid-call, and a single-threaded
    // server would deadlock the two against each other.
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.start();
    this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    System.setProperty(anchor, baseUrl);
  }

  /**
   * The one server for {@code name}, started on the first call in this JVM and attached to
   * afterwards. This is what {@link StoryProfile} calls, because the launch command is built out of
   * these addresses.
   *
   * @param name how the network diagram names this peer — {@code qits-githost}, {@code qits-ci} —
   *     which is deliberately the service a deployment really dials rather than a fixture's alias,
   *     so a reader of the diagram sees the dependency the shipped configuration declares
   */
  public static StoryPeers named(String name) {
    return INSTANCES.computeIfAbsent(name, StoryPeers::new);
  }

  /**
   * The already-started server for {@code name} — what a <b>story class</b> calls.
   *
   * <p>The distinction from {@link #named} is a guard rather than style. {@code named} starts a
   * server when it finds no anchor, which is right exactly once and catastrophic afterwards: a story
   * that started a <em>second</em> git host would arm its manifests on a port the launched process
   * has never heard of, every read would 404 against the first server, and the failure would name a
   * missing pom rather than the mistake.
   */
  public static StoryPeers attach(String name) {
    if (System.getProperty(ANCHOR_PREFIX + name) == null && !INSTANCES.containsKey(name)) {
      throw new IllegalStateException(
          "no story peer is running for "
              + name
              + " — StoryProfile starts all six, so a story class reaching this has been run"
              + " without it");
    }
    return named(name);
  }

  /** How the diagram names this peer — also the {@code to} of every edge it produces. */
  public String name() {
    return name;
  }

  /** What the matching {@code qits.maintenance.*-url} key is pointed at. */
  public String baseUrl() {
    return baseUrl;
  }

  /** …with a path prefix, for the three registries that are one service behind three mounts. */
  public String baseUrl(String prefix) {
    return baseUrl + prefix;
  }

  // --- what a story arms -------------------------------------------------------------------------

  /** A JSON document at exactly {@code path}, 200, no extra headers. */
  public StoryPeers json(String path, String body) {
    return answer(path, 200, "application/json", body, Map.of());
  }

  /** A JSON document plus response headers — which is how {@code Git-Commit-Sha} reaches a read. */
  public StoryPeers json(String path, String body, Map<String, String> headers) {
    return answer(path, 200, "application/json", body, headers);
  }

  /** An XML document at exactly {@code path} — a {@code maven-metadata.xml}. */
  public StoryPeers xml(String path, String body) {
    return answer(path, 200, "application/xml", body, Map.of());
  }

  /** Plain text at exactly {@code path} — the git host serves a blob as its raw bytes. */
  public StoryPeers text(String path, String body) {
    return answer(path, 200, "text/plain", body, Map.of());
  }

  /** The same, plus the {@code Git-Commit-Sha} every blob answer carries. */
  public StoryPeers text(String path, String body, Map<String, String> headers) {
    return answer(path, 200, "text/plain", body, headers);
  }

  /**
   * Any status at exactly {@code path}. Re-arming a path REPLACES what was there, which is what a
   * story does when a branch that did not exist has been pushed.
   */
  public StoryPeers answer(
      String path, int status, String contentType, String body, Map<String, String> headers) {
    Map<String, String> control = new LinkedHashMap<>();
    control.put("X-Path", encodeHeader(path));
    control.put("X-Status", Integer.toString(status));
    control.put("X-Content-Type", contentType);
    headers.forEach((header, value) -> control.put("X-Answer-" + header, encodeHeader(value)));
    control("serve", control, body.getBytes(StandardCharsets.UTF_8));
    return this;
  }

  /**
   * A JSON document for ONE METHOD at {@code path}, which wins over a method-less answer there.
   *
   * <p>qits-projects serves a repository's release-request collection on one path for two reads
   * that mean opposite things: the POST that opens a request and the GET that lists every request
   * (qits-1081, a bump's base). A path-only answer would hand the listing the POST's wrapped request
   * — a body with no {@code requests} array — so a story arms the listing by method and leaves the
   * ask where it was.
   */
  public StoryPeers jsonFor(String method, String path, String body) {
    return answerFor(method, path, 200, "application/json", body);
  }

  /** {@link #jsonFor} at any status — a 503 for a listing that cannot be read. */
  public StoryPeers answerFor(
      String method, String path, int status, String contentType, String body) {
    Map<String, String> control = new LinkedHashMap<>();
    control.put("X-Path", encodeHeader(path));
    control.put("X-Method", method);
    control.put("X-Status", Integer.toString(status));
    control.put("X-Content-Type", contentType);
    control("serve", control, body.getBytes(StandardCharsets.UTF_8));
    return this;
  }

  /**
   * Whether this peer answers at all.
   *
   * <p>{@code false} means the connection goes away with no status and no body, which is what an
   * outage looks like to {@code PeerClient.send} and the branch every {@code PeerAnswer.error}
   * exists for. A 500 would be a peer having an opinion; this is a peer that is not there.
   *
   * <p><b>Armable from a story method</b>, which is the whole point: the git host goes dark between
   * two scans and the story watches what the inventory does about it.
   */
  public void reachable(boolean value) {
    control("reachable", Map.of("X-Value", Boolean.toString(value)), new byte[0]);
  }

  // --- what the tap reads ------------------------------------------------------------------------

  /**
   * One recorded exchange: what was asked, what was answered, and which roles the caller asserted.
   *
   * @param method GET or POST
   * @param path the RAW path, query included — what was on the wire
   * @param status the status this peer answered, or {@link #DROPPED}
   * @param roles the {@code X-Qits-Roles} header, or {@link #NO_ROLES}
   */
  public record Request(String method, String path, String status, String roles) {

    /** The label half of an edge: method, path, and the status that came back. */
    public String label() {
      return method + " " + path + " -> " + status;
    }
  }

  /**
   * The <b>whole</b> recording, every time — the contract {@link
   * eu.wohlben.qits.userflows.NetworkCapture#source} states, with the framework's per-source cursor
   * deciding which slice belongs to the story now draining.
   */
  public List<Request> recordedRequests() {
    List<Request> requests = new ArrayList<>();
    for (String line : controlText("recording").split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      // "<method>\t<path>\t<status>\t<roles>" — tabs, because a path may hold anything but one.
      String[] fields = line.split("\t", 4);
      if (fields.length == 4) {
        requests.add(new Request(fields[0], fields[1], fields[2], fields[3]));
      }
    }
    return requests;
  }

  /** How many times this peer was asked for exactly {@code path}, whatever it answered. */
  public long requestsTo(String path) {
    return recordedRequests().stream().filter(request -> path.equals(request.path())).count();
  }

  /**
   * Every body POSTed to {@code path}, in order — the {@code MaintenanceBump} triggers this peer
   * received, which is the one place the payload an operator's button composed can be read as it
   * went out rather than as this service's own row remembers it.
   *
   * <p>Base64 on the wire so a body carrying a newline could never split a line of the answer.
   */
  public List<String> bodiesFor(String path) {
    List<String> decoded = new ArrayList<>();
    for (String line : controlText("bodies", Map.of("X-Path", encodeHeader(path))).split("\n")) {
      if (!line.isBlank()) {
        decoded.add(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8));
      }
    }
    return decoded;
  }

  // --- the server --------------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws java.io.IOException {
    String raw = exchange.getRequestURI().getRawPath();
    String decoded = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getRawQuery();
    // Control first, and before the reachability switch: turning a peer back on has to work while
    // it is off, and no control call is ever traffic a diagram should draw.
    if (decoded.startsWith(CONTROL)) {
      handleControl(exchange, decoded.substring(CONTROL.length()));
      return;
    }
    String method = exchange.getRequestMethod();
    String roles = header(exchange, "X-Qits-Roles");
    String wire = query == null || query.isEmpty() ? raw : raw + "?" + query;
    byte[] body = exchange.getRequestBody().readAllBytes();
    if (body.length > 0) {
      bodies.computeIfAbsent(decoded, key -> Collections.synchronizedList(new ArrayList<>()))
          .add(Base64.getEncoder().encodeToString(body));
    }
    if (!reachable) {
      // Recorded BEFORE the connection goes away — this is the one exchange whose evidence would
      // otherwise not exist, and it is the evidence the outage story is entirely about.
      record(method, wire, DROPPED, roles);
      exchange.close();
      return;
    }
    Answer answer = answers.getOrDefault(method + " " + decoded, answers.get(decoded));
    if (answer == null) {
      // An unregistered route is this peer's genuine "no such thing" — which for the git host is the
      // ordinary case: a repository that carries no .config/qits/maintenance.yml is a 404 and then a
      // second read of the root tree, and that pair is a rule this service has rather than a gap in
      // a fixture.
      record(method, wire, "404", roles);
      respond(exchange, 404, "application/json", "{\"message\":\"not found\"}"
          .getBytes(StandardCharsets.UTF_8), Map.of());
      return;
    }
    record(method, wire, String.valueOf(answer.status), roles);
    respond(exchange, answer.status, answer.contentType, answer.body, answer.headers);
  }

  private void record(String method, String path, String status, String roles) {
    recording.add(method + "\t" + path + "\t" + status + "\t" + roles);
  }

  private void handleControl(HttpExchange exchange, String command) throws java.io.IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    switch (command) {
      case "serve" -> {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange
            .getRequestHeaders()
            .forEach(
                (header, values) -> {
                  if (header.regionMatches(true, 0, "X-Answer-", 0, "X-Answer-".length())) {
                    headers.put(
                        header.substring("X-Answer-".length()), decodeHeader(values.getFirst()));
                  }
                });
        String method = exchange.getRequestHeaders().getFirst("X-Method");
        String path = decodeHeader(exchange.getRequestHeaders().getFirst("X-Path"));
        answers.put(
            method == null || method.isBlank() ? path : method + " " + path,
            new Answer(
                Integer.parseInt(exchange.getRequestHeaders().getFirst("X-Status")),
                exchange.getRequestHeaders().getFirst("X-Content-Type"),
                body,
                headers));
        respond(exchange, 200, "text/plain", new byte[0], Map.of());
      }
      case "reachable" -> {
        reachable = Boolean.parseBoolean(exchange.getRequestHeaders().getFirst("X-Value"));
        respond(exchange, 200, "text/plain", new byte[0], Map.of());
      }
      case "recording" -> {
        StringBuilder lines = new StringBuilder();
        // A copy under the list's own monitor: the launched process may be appending while this
        // renders, and a half-written view would shape half an edge.
        synchronized (recording) {
          for (String line : recording) {
            lines.append(line).append('\n');
          }
        }
        respondText(exchange, lines.toString());
      }
      case "bodies" -> {
        String path = decodeHeader(exchange.getRequestHeaders().getFirst("X-Path"));
        StringBuilder lines = new StringBuilder();
        List<String> recorded = bodies.get(path);
        if (recorded != null) {
          synchronized (recorded) {
            for (String line : recorded) {
              lines.append(line).append('\n');
            }
          }
        }
        respondText(exchange, lines.toString());
      }
      default -> respond(exchange, 404, "text/plain", new byte[0], Map.of());
    }
  }

  private static void respondText(HttpExchange exchange, String body) throws java.io.IOException {
    respond(
        exchange,
        200,
        "text/plain; charset=utf-8",
        body.getBytes(StandardCharsets.UTF_8),
        Map.of());
  }

  private static void respond(
      HttpExchange exchange, int status, String contentType, byte[] body, Map<String, String> headers)
      throws java.io.IOException {
    if (contentType != null) {
      exchange.getResponseHeaders().add("Content-Type", contentType);
    }
    headers.forEach((header, value) -> exchange.getResponseHeaders().add(header, value));
    if (body.length == 0) {
      // -1 is "no body at all", which is what an empty control answer wants.
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
    exchange.close();
  }

  private static String header(HttpExchange exchange, String name) {
    String value = exchange.getRequestHeaders().getFirst(name);
    return value == null || value.isBlank() ? NO_ROLES : value;
  }

  // --- the client half ---------------------------------------------------------------------------

  private void control(String command, Map<String, String> headers, byte[] body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + CONTROL + command))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    headers.forEach(request::header);
    try {
      int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
      if (status != 200) {
        throw new IllegalStateException(name + " control " + command + " answered " + status);
      }
    } catch (Exception unreachable) {
      throw new IllegalStateException(name + " control " + command + " failed", unreachable);
    }
  }

  private String controlText(String command) {
    return controlText(command, Map.of());
  }

  private String controlText(String command, Map<String, String> headers) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + CONTROL + command)).GET();
    headers.forEach(request::header);
    try {
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    } catch (Exception unreachable) {
      throw new IllegalStateException(name + " control " + command + " failed", unreachable);
    }
  }

  /**
   * A header value carrying an arbitrary path. HTTP header values are ISO-8859-1 and reject a
   * newline outright, so the control plane percent-encodes them rather than trusting that a manifest
   * path never grows a character a header cannot hold.
   */
  private static String encodeHeader(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String decodeHeader(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  /** One armed route: what this peer answers at a path. */
  private record Answer(int status, String contentType, byte[] body, Map<String, String> headers) {}
}

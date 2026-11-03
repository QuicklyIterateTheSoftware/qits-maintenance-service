package eu.wohlben.qits.maintenance.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The one way this service touches another.
 *
 * <p><b>The JDK's HttpClient, not a REST client</b>, and the orchestrator's reasons: a peer's
 * answer stays an opaque document rather than a bound record, so nothing here is a second copy of
 * five other repositories' response shapes. It also has to read bodies that are not JSON at all —
 * a {@code maven-metadata.xml} and a raw blob — which a JSON-bound client would make awkward.
 *
 * <p><b>The lazily built client is wrapped through {@link QitsHttpClients}</b> (qits-1198): a CLIENT
 * span per call and {@code X-Qits-Caller}, the same two things a REST client gets from Quarkus and
 * this one otherwise would not.
 *
 * <p><b>One credential on every call, and it is mandatory (owner ruling 2026-10-18, qits-543).</b>
 * The bearer from the named Quarkus OIDC client {@code quarkus.oidc-client.qits} ({@code
 * qits:system}) is the only identity a call carries — never the {@code X-Qits-User}/{@code
 * X-Qits-Roles} pair, and never the pair as a fallback when the client is disabled. A call this
 * service cannot mint a bearer for is refused locally instead of going out unauthenticated — the
 * same pattern qits-projects' {@code HttpGitHostRepositories} uses: fail closed and say which
 * client is missing.
 *
 * <p><b>Nothing throws.</b> A scan reads every repository in the catalog, so one unreachable peer
 * — or one call this process has no bearer for — must cost one row's status and not the run.
 *
 * <p><b>A response is bounded.</b> A packument for a popular package is megabytes of version
 * history and this service reads one field out of it; an unbounded read would let one dependency's
 * release count decide this process's heap.
 */
@ApplicationScoped
public class PeerClient {

  private static final Logger LOG = Logger.getLogger(PeerClient.class);

  /** How much of a peer's answer is read. A packument past this is refused rather than truncated —
   * see {@link #bound}. */
  public static final int RESPONSE_LIMIT_BYTES = 16 * 1024 * 1024;

  private static final ObjectMapper JSON = new ObjectMapper();

  @ConfigProperty(name = "qits.maintenance.call-timeout")
  Duration callTimeout;

  @Inject PeerTokens tokens;

  @Inject QitsHttpClients clients;

  /** One client for the life of the process — a scan is hundreds of calls and a new pool per call
   * is waste. An INSTANCE field, never static: a static HttpClient is a native-image hazard. */
  private volatile HttpClient client;

  /** The absolute url a path resolves to on one target. */
  public String url(PeerTarget target, String path) {
    return trimTrailingSlash(base(target)) + path;
  }

  /**
   * A GET, as the pair a caller records.
   *
   * <p>{@code get} and {@code post} are the seam a test replaces — a fake peer is an
   * {@code @Alternative} subclass overriding these two, so nothing above has to know it is talking
   * to a stub.
   */
  public PeerExchange get(PeerTarget target, String path) {
    PeerCall call = new PeerCall("GET", url(target, path), null);
    return new PeerExchange(call, send(call));
  }

  /** A POST with a JSON body, as the same pair. */
  public PeerExchange post(PeerTarget target, String path, String body) {
    PeerCall call = new PeerCall("POST", url(target, path), body);
    return new PeerExchange(call, send(call));
  }

  /**
   * A DELETE with no body, as the same pair — the one delete this service makes is of its own
   * automation branches on the git host ({@code AutomationBranchSweep}).
   */
  public PeerExchange delete(PeerTarget target, String path) {
    PeerCall call = new PeerCall("DELETE", url(target, path), null);
    return new PeerExchange(call, send(call));
  }

  /**
   * Sends one call and turns everything that can happen into an answer.
   *
   * <p>Takes no {@link PeerTarget} any more: every peer takes the same bearer now (C4), so there is
   * nothing left here for the target to select.
   */
  public PeerAnswer send(PeerCall call) {
    Optional<String> token = tokens.token();
    if (token.isEmpty()) {
      LOG.warnf(
          "no bearer: the qits oidc client is not configured or could not mint one; %s was not"
              + " called",
          call.url());
      return new PeerAnswer(
          null,
          null,
          null,
          Map.of(),
          "no bearer: the qits oidc client (quarkus.oidc-client.qits) is not configured or could"
              + " not mint one; "
              + call.url()
              + " was not called");
    }

    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(call.url()))
            .timeout(callTimeout)
            .header("Authorization", "Bearer " + token.get());
    if ("DELETE".equals(call.method())) {
      request.DELETE();
    } else if (call.body() == null) {
      request.GET();
    } else {
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(call.body(), StandardCharsets.UTF_8));
    }

    try {
      HttpResponse<byte[]> response =
          client().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      String body = bound(response.body());
      Map<String, String> headers = firstValues(response);
      if (body == null) {
        return new PeerAnswer(
            response.statusCode(),
            null,
            null,
            headers,
            call.url() + " answered more than " + RESPONSE_LIMIT_BYTES + " bytes");
      }
      return new PeerAnswer(response.statusCode(), body, parse(body), headers, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new PeerAnswer(null, null, null, Map.of(), call.url() + " was interrupted");
    } catch (Exception e) {
      return new PeerAnswer(
          null, null, null, Map.of(), call.url() + " could not be called: " + e);
    }
  }

  /**
   * The target's base url, from configuration.
   *
   * <p>Read through {@code ConfigProvider} rather than as eleven injected fields: the key is on the
   * target, so one lookup keeps the mapping in one place instead of spelling every peer twice.
   */
  private String base(PeerTarget target) {
    Config config = ConfigProvider.getConfig();
    return config
        .getOptionalValue(target.urlKey(), String.class)
        .orElseThrow(() -> new IllegalStateException(target.urlKey() + " is not configured"));
  }

  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          existing = clients.wrap(HttpClient.newBuilder().connectTimeout(callTimeout).build());
          client = existing;
        }
      }
    }
    return existing;
  }

  /**
   * The body as text, or null when it is bigger than this service reads.
   *
   * <p><b>Refused rather than truncated</b>, unlike the orchestrator's run log. There a truncated
   * body is still a readable record of what a peer said; here every body is PARSED — an xml
   * document, a packument, a manifest — and half of one parses into a wrong answer rather than into
   * an error.
   */
  private static String bound(byte[] body) {
    if (body == null) {
      return null;
    }
    if (body.length > RESPONSE_LIMIT_BYTES) {
      return null;
    }
    return new String(body, StandardCharsets.UTF_8);
  }

  /** The body as a tree, or null. Most answers here are not JSON at all, and that is not an error. */
  private static JsonNode parse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      return null;
    }
  }

  /** The response headers, lower-cased, first value only — {@code Git-Commit-Sha} is what this is
   * for. */
  private static Map<String, String> firstValues(HttpResponse<?> response) {
    Map<String, String> headers = new HashMap<>();
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
              }
            });
    return headers;
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}

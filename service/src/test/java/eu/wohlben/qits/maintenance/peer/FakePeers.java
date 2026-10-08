package eu.wohlben.qits.maintenance.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The six peers, faked at the one seam this service talks through.
 *
 * <p><b>An {@code @Alternative} subclass rather than a stub server</b>: the whole of what this
 * repository does over the wire is {@link PeerClient#get} and {@link PeerClient#post}, so replacing
 * those two is replacing the network. It costs no port, no thread and no WireMock dependency, and
 * the urls in the assertions are the REAL ones — the inherited {@code url()} still resolves them
 * from the shipped target configuration, so a wrong path or a wrong peer fails here.
 *
 * <p><b>The key is the target AND the path.</b> Unlike the orchestrator's four peers, three of the
 * targets here are one service behind three prefixes and two more are a mirror of the same shapes;
 * a path alone would let a maven lookup answer for the mirror's.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FakePeers extends PeerClient {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * One scripted answer.
   *
   * @param status the peer's code, null for a transport failure
   * @param body what it said
   * @param headers response headers, which is how {@code Git-Commit-Sha} reaches a tree read
   * @param transportError a peer that could not be reached at all
   */
  public record Scripted(
      Integer status, String body, Map<String, String> headers, String transportError) {

    public static Scripted ok(String body) {
      return new Scripted(200, body, Map.of(), null);
    }

    public static Scripted ok(String body, Map<String, String> headers) {
      return new Scripted(200, body, headers, null);
    }

    public static Scripted status(int status, String body) {
      return new Scripted(status, body, Map.of(), null);
    }

    /** A peer that cannot be reached at all — no status, a sentence instead. */
    public static Scripted unreachable(String message) {
      return new Scripted(null, null, Map.of(), message);
    }
  }

  private final Map<String, Scripted> script = new ConcurrentHashMap<>();

  /** Every call made, in order, so a test can assert what a body carried. */
  public final List<PeerCall> calls = new CopyOnWriteArrayList<>();

  /** Held before a scripted answer is returned, when a test wants work to stay in flight. */
  private volatile CountDownLatch gate;

  public void reset() {
    script.clear();
    calls.clear();
    gate = null;
  }

  /** Scripts one answer. */
  public void answer(PeerTarget target, String path, Scripted scripted) {
    script.put(key(target, path), scripted);
  }

  /**
   * Scripts one answer to a DELETE. Keyed apart from a GET of the same path, because the git host
   * serves both verbs on {@code …/branches/<name>} and they mean different things.
   */
  public void answerDelete(PeerTarget target, String path, Scripted scripted) {
    script.put(key(target, "DELETE " + path), scripted);
  }

  /** Whether a DELETE went to that target and path. */
  public boolean deleted(PeerTarget target, String path) {
    String url = url(target, path);
    return calls.stream()
        .anyMatch(call -> "DELETE".equals(call.method()) && call.url().equals(url));
  }

  /**
   * How many DELETEs were made. A method rather than a read of {@link #calls}: a test holds the
   * client proxy, whose own copy of that field is always empty.
   */
  public long deleteCount() {
    return calls.stream().filter(call -> "DELETE".equals(call.method())).count();
  }

  /** Blocks every call until {@link #release()}, so a test can catch work mid-flight. */
  public CountDownLatch hold() {
    CountDownLatch latch = new CountDownLatch(1);
    gate = latch;
    return latch;
  }

  public void release() {
    CountDownLatch latch = gate;
    gate = null;
    if (latch != null) {
      latch.countDown();
    }
  }

  /** The bodies of every call whose url ends with that path, in order. */
  public List<String> bodiesFor(String path) {
    List<String> bodies = new ArrayList<>();
    for (PeerCall call : calls) {
      if (call.url().endsWith(path)) {
        bodies.add(call.body());
      }
    }
    return bodies;
  }

  /** Whether any call went to that target and path. */
  public boolean called(PeerTarget target, String path) {
    String url = url(target, path);
    return calls.stream().anyMatch(call -> call.url().equals(url));
  }

  @Override
  public PeerExchange get(PeerTarget target, String path) {
    return exchange(target, new PeerCall("GET", url(target, path), null), path);
  }

  @Override
  public PeerExchange post(PeerTarget target, String path, String body) {
    return exchange(target, new PeerCall("POST", url(target, path), body), path);
  }

  @Override
  public PeerExchange delete(PeerTarget target, String path) {
    return exchange(target, new PeerCall("DELETE", url(target, path), null), "DELETE " + path);
  }

  private PeerExchange exchange(PeerTarget target, PeerCall call, String path) {
    calls.add(call);
    CountDownLatch latch = gate;
    if (latch != null) {
      try {
        // Bounded: a test that forgets to release must fail on its own assertion rather than hang
        // the whole suite on a worker thread nothing will interrupt.
        latch.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    Scripted scripted = script.get(key(target, path));
    if (scripted == null) {
      // An unscripted path is a 404, which is what the git host answers for a file a repository
      // does not carry — the ordinary case in a scan, and it keeps a test's script to what it
      // actually means to say.
      return new PeerExchange(call, new PeerAnswer(404, "", null, Map.of(), null));
    }
    if (scripted.transportError() != null) {
      return new PeerExchange(
          call, new PeerAnswer(null, null, null, Map.of(), scripted.transportError()));
    }
    return new PeerExchange(
        call,
        new PeerAnswer(
            scripted.status(),
            scripted.body(),
            parse(scripted.body()),
            lowerCased(scripted.headers()),
            null));
  }

  private static String key(PeerTarget target, String path) {
    return target.name() + " " + path;
  }

  private static Map<String, String> lowerCased(Map<String, String> headers) {
    Map<String, String> lower = new java.util.HashMap<>();
    headers.forEach((name, value) -> lower.put(name.toLowerCase(java.util.Locale.ROOT), value));
    return lower;
  }

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
}

package eu.wohlben.qits.maintenance.peer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>A {@link PeerClient} that answers nothing and remembers who asked</b> (ticket qits-1149): the
 * call guard's double. Every call is recorded with its target and every frame on the stack that
 * belongs to one of {@code clients} — so a client method that reaches the wire through another of
 * its own methods names both — and answered 404 with no body, the one answer every client turns
 * into a result rather than a throw.
 */
public final class RecordingPeerClient extends PeerClient {

  /** One call: the target and the client methods ({@code Class#method}) it was made from. */
  public record Recorded(PeerTarget target, Set<String> callers) {}

  private final Set<Class<?>> clients;
  private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

  public RecordingPeerClient(Set<Class<?>> clients) {
    this.clients = Set.copyOf(clients);
  }

  @Override
  public String url(PeerTarget target, String path) {
    Set<String> callers = new java.util.TreeSet<>();
    StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .forEach(
            frame -> {
              if (clients.contains(frame.getDeclaringClass())) {
                callers.add(frame.getDeclaringClass().getSimpleName() + "#" + frame.getMethodName());
              }
            });
    recorded.add(new Recorded(target, callers));
    return "http://recorded.invalid" + path;
  }

  @Override
  public PeerAnswer send(PeerCall call) {
    return new PeerAnswer(404, "", null, Map.of(), null);
  }

  /** Every call so far, in order. */
  public List<Recorded> recorded() {
    synchronized (recorded) {
      return List.copyOf(recorded);
    }
  }

  /** Forgets every call so far. */
  public void clear() {
    recorded.clear();
  }
}

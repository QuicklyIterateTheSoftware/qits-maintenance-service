package eu.wohlben.qits.maintenance.bump;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one call this service makes to qits-system: how much CI room the estate's targets have
 * (qits-1769), from one read of {@code GET /system/api/targets}.
 *
 * <p><b>Why here and not qits-ci.</b> Since a CI run is a lease (epic qits-1481), how many run at
 * once is qits-system's {@code SlotAllocator} to grant, against every target's total, reservations
 * and leases in use. qits-ci holds no runner and reports no capacity, so the dispatcher used to
 * fall back to a configured cap of 4 against qits-ci's running-plus-queued count over the WHOLE
 * platform — which was at or above 4 most of the day, so it read "busy" while real slots sat free
 * (measured 2026-10-31: cap 4, 8 active, while target {@code workstation} had 2 of its 6 slots
 * free for CI).
 *
 * <p><b>Nothing throws.</b> An unreachable qits-system, a body that is not an array, or a target
 * missing one of the fields the rule needs is an UNREADABLE {@link Targets}, carrying the sentence
 * — never an empty pool, which would read as "no room" for the wrong reason.
 */
@ApplicationScoped
public class SystemClient {

  /** Every target qits-system knows, with its slots and the leases using them. */
  public static final String TARGETS_PATH = "/system/api/targets";

  /** The slot type a CI run's lease is granted as, the key qits-system spells it with. */
  static final String CI = "ci";

  @Inject PeerClient peers;

  /**
   * One target as the allocator sees it.
   *
   * @param name the target's name, for a sentence
   * @param total {@code slotsTotal}
   * @param reservations slots reserved per type, a negative count read as zero
   * @param used GRANTED, RUNNING and ENDING leases per type, a negative count read as zero
   * @param connected whether its runner is connected
   * @param quarantined whether it is quarantined
   */
  public record Target(
      String name,
      int total,
      Map<String, Integer> reservations,
      Map<String, Integer> used,
      boolean connected,
      boolean quarantined) {

    /** What the allocator grants on at all: connected and not quarantined. */
    public boolean eligible() {
      return connected && !quarantined;
    }

    /**
     * How many more CI leases qits-system would grant here now — the count of successive CI
     * leases {@code SlotAllocator.fits} admits. Adding a CI lease never changes what is reserved
     * for the OTHER types, so {@code fits}' {@code n_total + 1 + Σ_{u≠ci} max(0, r[u] − n[u]) ≤ T}
     * holds exactly {@code T − n_total − Σ_{u≠ci} max(0, r[u] − n[u])} more times; when {@code Σ r
     * > T} the reservations are ignored and only {@code n_total < T} holds. Floored at zero.
     */
    public int freeCi() {
      int usedTotal = sum(used);
      if (reservationsExceedTotal()) {
        return Math.max(0, total - usedTotal);
      }
      int reservedForOthers = 0;
      for (Map.Entry<String, Integer> reservation : reservations.entrySet()) {
        if (!CI.equals(reservation.getKey())) {
          reservedForOthers +=
              Math.max(0, reservation.getValue() - used.getOrDefault(reservation.getKey(), 0));
        }
      }
      return Math.max(0, total - usedTotal - reservedForOthers);
    }

    /**
     * How many CI leases this target could hold at once on an empty target — {@code
     * SlotAllocator.canEverRun}'s question, counted: {@code T − Σ_{u≠ci} r[u]}, or {@code T} when
     * the reservations exceed the total and are ignored. Floored at zero.
     */
    public int ciCapacity() {
      if (reservationsExceedTotal()) {
        return Math.max(0, total);
      }
      int reservedForOthers = 0;
      for (Map.Entry<String, Integer> reservation : reservations.entrySet()) {
        if (!CI.equals(reservation.getKey())) {
          reservedForOthers += reservation.getValue();
        }
      }
      return Math.max(0, total - reservedForOthers);
    }

    /** {@code Σ r > T}: qits-system ignores the reservations then, and so does this. */
    boolean reservationsExceedTotal() {
      return sum(reservations) > total;
    }

    private static int sum(Map<String, Integer> counts) {
      return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
  }

  /**
   * What qits-system said about its targets.
   *
   * @param targets every target it listed, eligible or not; empty when unreadable
   * @param error why the listing could not be read, or null
   */
  public record Targets(List<Target> targets, String error) {

    /** Whether the listing answered. An unreadable one is not an empty pool. */
    public boolean readable() {
      return error == null;
    }

    /** Connected, unquarantined targets: the only ones the allocator grants on. */
    public List<Target> eligible() {
      return targets.stream().filter(Target::eligible).toList();
    }

    /** CI leases the eligible targets would grant now, summed. */
    public int freeCi() {
      return eligible().stream().mapToInt(Target::freeCi).sum();
    }

    /** CI leases the eligible targets could hold at once, summed: zero means none can host CI. */
    public int ciCapacity() {
      return eligible().stream().mapToInt(Target::ciCapacity).sum();
    }

    static Targets unreadable(String error) {
      return new Targets(List.of(), error);
    }
  }

  /**
   * Every target qits-system lists, from one read of {@code GET /system/api/targets}.
   *
   * <p>A target must carry {@code slotsTotal}, {@code reservations}, {@code used}, {@code
   * connected} and {@code quarantined}, or the whole answer is unreadable: guessing a missing
   * {@code used} as zero would hand out room that is taken. A type missing INSIDE {@code
   * reservations} or {@code used} counts zero, as qits-system's own allocator counts it.
   */
  public Targets targets() {
    PeerAnswer answer = peers.get(PeerTarget.SYSTEM, TARGETS_PATH).answer();
    if (!answer.ok()) {
      return Targets.unreadable("qits-system's targets could not be read: " + answer.failure());
    }
    JsonNode body = answer.json();
    if (body == null || !body.isArray()) {
      return Targets.unreadable("qits-system's targets answer was not a JSON array");
    }
    List<Target> targets = new ArrayList<>();
    for (JsonNode node : body) {
      String name = node.path("name").asText("target #" + targets.size());
      if (!node.path("slotsTotal").isInt()
          || !node.path("reservations").isObject()
          || !node.path("used").isObject()
          || !node.path("connected").isBoolean()
          || !node.path("quarantined").isBoolean()) {
        return Targets.unreadable(
            "qits-system's target "
                + name
                + " carried no slotsTotal, reservations, used, connected or quarantined");
      }
      targets.add(
          new Target(
              name,
              node.get("slotsTotal").asInt(),
              counts(node.get("reservations")),
              counts(node.get("used")),
              node.get("connected").asBoolean(),
              node.get("quarantined").asBoolean()));
    }
    return new Targets(List.copyOf(targets), null);
  }

  private static Map<String, Integer> counts(JsonNode object) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    object
        .fields()
        .forEachRemaining(
            entry -> counts.put(entry.getKey(), Math.max(0, entry.getValue().asInt(0))));
    return Map.copyOf(counts);
  }
}

package eu.wohlben.qits.maintenance.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.entity.MtBump;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>qits-maintenance's provider states</b> (epic qits-112): each seeds the bump rows one consumer
 * situation needs and hands back their ids as parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 * Both call {@link #cleanUp()} afterwards: the bump table is shared by every test in the run, and
 * the dispatcher must never see these rows.
 *
 * <p><b>The rows start in the year 2100</b>, so they are the newest bumps whatever else the test
 * database holds, and a list of the newest 20 always contains them. The recorder freezes every id
 * and instant, so neither reaches a golden master as it was seeded.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String PENDING_BUMPS = "pending bumps";
  public static final String NO_PENDING_BUMPS = "no pending bumps";

  /** What a state hands back: its parameters, keys sorted, and its unique tokens (none here). */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  private static final Instant FUTURE = Instant.parse("2100-01-01T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();
  private final List<UUID> created = Collections.synchronizedList(new ArrayList<>());

  public ProviderStates() {
    states.put(PENDING_BUMPS, this::pendingBumps);
    states.put(NO_PENDING_BUMPS, this::noPendingBumps);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** Deletes every bump row a state created since the last clean-up. */
  public void cleanUp() {
    List<UUID> ids;
    synchronized (created) {
      ids = List.copyOf(created);
      created.clear();
    }
    if (!ids.isEmpty()) {
      QuarkusTransaction.requiringNew().run(() -> MtBump.delete("id in ?1", ids));
    }
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * Seven bumps, newest first. Pending: a targeted bump asked for and not yet running, a group bump
   * running, one green whose release is still owed, and one green with its release request open.
   * Over, which {@code listPendingBumps} leaves out: one that converged, one released, one failed.
   */
  private Setup pendingBumps() {
    UUID requested =
        bump(
            "contract-service",
            "TARGETED",
            "maintenance/targeted",
            "REQUESTED",
            FUTURE.plusSeconds(6),
            change("DOCKER", "docker/Dockerfile", "qits/build-images/ci-base", "2026.1.1", "2026.2.1"),
            null,
            null);
    UUID running =
        bump(
            "contract-service",
            "GROUP",
            "maintenance/dependencies",
            "RUNNING",
            FUTURE.plusSeconds(5),
            change("MAVEN", "pom.xml", "eu.wohlben.qits:qits-arch-rules", "2026.1.1", "2026.2.1"),
            null,
            null);
    UUID releaseOwed =
        bump(
            "contract-javalib",
            "GROUP",
            "maintenance/dependencies",
            "SUCCEEDED",
            FUTURE.plusSeconds(4),
            change("MAVEN", "pom.xml", "io.quarkus.platform:quarkus-bom", "3.30.1", "3.31.0"),
            null,
            null);
    UUID awaitingRelease =
        bump(
            "contract-frontend",
            "GROUP",
            "maintenance/dependencies",
            "SUCCEEDED",
            FUTURE.plusSeconds(3),
            change("NPM", "package.json", "@qits/angular", "2026.1.1", "2026.2.1"),
            UUID.randomUUID().toString(),
            "PENDING");
    UUID converged =
        bump(
            "contract-javalib",
            "GROUP",
            "maintenance/dependencies",
            "SUCCEEDED",
            FUTURE.plusSeconds(2),
            change("MAVEN", "pom.xml", "org.junit:junit-bom", "6.0.0", "6.0.1"),
            "converged",
            null);
    UUID released =
        bump(
            "contract-frontend",
            "GROUP",
            "maintenance/dependencies",
            "SUCCEEDED",
            FUTURE.plusSeconds(1),
            change("NPM", "package.json", "@qits/ui-components", "2026.1.1", "2026.2.1"),
            UUID.randomUUID().toString(),
            "RELEASED");
    UUID failed =
        bump(
            "contract-service",
            "GROUP",
            "maintenance/dependencies",
            "FAILED",
            FUTURE,
            change("MAVEN", "pom.xml", "eu.wohlben.qits:qits-db-core", "2026.1.1", "2026.2.1"),
            null,
            null);
    Map<String, String> params = new TreeMap<>();
    params.put("awaitingReleaseBumpId", awaitingRelease.toString());
    params.put("convergedBumpId", converged.toString());
    params.put("failedBumpId", failed.toString());
    params.put("releaseOwedBumpId", releaseOwed.toString());
    params.put("releasedBumpId", released.toString());
    params.put("requestedBumpId", requested.toString());
    params.put("runningBumpId", running.toString());
    return new Setup(params, List.of());
  }

  /** Nothing of this state's own: the list it answers holds no bump a state created. */
  private Setup noPendingBumps() {
    return new Setup(Map.of(), List.of());
  }

  private static Map<String, String> change(
      String ecosystem, String manifestPath, String name, String from, String to) {
    Map<String, String> change = new LinkedHashMap<>();
    change.put("ecosystem", ecosystem);
    change.put("manifestPath", manifestPath);
    change.put("name", name);
    change.put("from", from);
    change.put("to", to);
    change.put("location", null);
    return change;
  }

  private UUID bump(
      String repository,
      String mode,
      String branch,
      String status,
      Instant startedAt,
      Map<String, String> change,
      String releaseRequestId,
      String releaseState) {
    UUID id = UUID.randomUUID();
    String changes;
    try {
      changes = JSON.writeValueAsString(List.of(change));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    boolean ended = !status.equals("REQUESTED") && !status.equals("RUNNING");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MtBump row = new MtBump();
              row.id = id;
              row.repository = repository;
              row.groupName = mode.equals("TARGETED") ? "targeted" : "dependencies";
              row.mode = mode;
              row.branch = branch;
              row.environment = "dev";
              row.trigger = "SCHEDULED";
              row.status = status;
              row.changes = changes;
              row.startedAt = startedAt;
              row.finishedAt = ended ? startedAt.plusSeconds(60) : null;
              row.releaseRequestId = releaseRequestId;
              row.releaseState = releaseState;
              row.releaseStateAt = releaseState == null ? null : startedAt.plusSeconds(120);
              // A green bump pushed a commit, except one that converged: there was nothing to hold.
              row.resultSha =
                  status.equals("SUCCEEDED") && !"converged".equals(releaseRequestId)
                      ? "0123456789abcdef0123456789abcdef01234567"
                      : null;
              row.persist();
            });
    created.add(id);
    return id;
  }
}

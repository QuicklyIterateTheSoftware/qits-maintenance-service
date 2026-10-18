package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.latest.LatestResolver;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.peer.PeerTokens;
import eu.wohlben.qits.maintenance.peer.RecordingPeerClient;
import eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters;
import io.quarkus.oidc.client.OidcClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>The guard: no peer call without a pact row</b> (ticket qits-1149).
 *
 * <p>The calls are derived from the code, not from a list. Every class of this module that holds a
 * {@link PeerClient} field is a peer client, and each of its public instance methods is a call.
 * Every contract row — the qits-projects {@link ProjectsContract} cases and every {@link PeerRow},
 * recorded or pending — is run against a {@link RecordingPeerClient}, which remembers each request's
 * {@link PeerTarget} and every client method on the stack when it was made. Then:
 *
 * <ul>
 *   <li>every client method is on some row's stack, unless {@link #NO_ROW_OF_ITS_OWN} names it with
 *       the reason;
 *   <li>every {@link PeerTarget} is reached by some row;
 *   <li>a row reaches only targets of its own provider ({@link #provider}, an exhaustive switch, so
 *       a new target does not compile until it is given one);
 *   <li>every class that holds an {@link OidcClient} mints through a row of {@link IdpContract}.
 * </ul>
 *
 * <p>A new client method, a new client class or a new target added without a row goes red here.
 * Only this module's classes are scanned: {@code service/} holds no peer client.
 */
class CallCoverageTest {

  /**
   * The client methods with no row of their own, each with the reason — every exclusion is a
   * decision someone can read.
   */
  static final Map<String, String> NO_ROW_OF_ITS_OWN =
      Map.of(
          LatestResolver.class.getSimpleName() + "#resolvable",
          "answers from the pin's ecosystem and kind; asks no peer",
          LatestResolver.class.getSimpleName() + "#published",
          "the maven-metadata.xml read resolve(MAVEN, …) makes, and nothing in this service calls it",
          ReleaseRequestClient.class.getSimpleName() + "#requestMainOnly",
          "requestRelease with the repository's main branch: the same createReleaseRequest call");

  /** The classes whose token mint {@link IdpContract} rows: the one {@code qits} oidc client. */
  static final Set<Class<?>> MINTED_BY_IDP_CONTRACT = Set.of(PeerTokens.class);

  /** The provider repository a target belongs to. */
  static String provider(PeerTarget target) {
    return switch (target) {
      case PROJECTS -> GoldenMasters.PROVIDER;
      case GITHOST -> GitHostContract.GITHOST.repository();
      case CI -> CiContract.CI.repository();
      case DEPLOYMENTS -> DeploymentsContract.DEPLOYMENTS.repository();
      case MAVEN_REGISTRY, NPM_REGISTRY, OCI_REGISTRY, ARTIFACTS_SBOM, ARTIFACTS_DOCS ->
          ArtifactsContract.ARTIFACTS.repository();
      case MAVEN_MIRROR, NPM_MIRROR -> MirrorContract.MIRROR.repository();
    };
  }

  /** One row as the guard runs it: its provider, its name and the call it makes. */
  private record Exercise(String provider, String name, Runnable call) {}

  @Test
  void everyPeerCallHasAPactRow() {
    Set<Class<?>> clients = classesHolding(PeerClient.class);
    assertFalse(clients.isEmpty(), "no class holds a PeerClient: the scan found nothing");
    RecordingPeerClient recording = new RecordingPeerClient(clients);

    Set<String> exercised = new TreeSet<>();
    Set<PeerTarget> reached = EnumSet.noneOf(PeerTarget.class);
    List<String> failures = new ArrayList<>();
    for (Exercise exercise : exercises(recording)) {
      recording.clear();
      try {
        exercise.call().run();
      } catch (Throwable expected) {
        // A 404 everywhere fails most rows' own assertions; the call was made, which is the point.
      }
      List<RecordingPeerClient.Recorded> calls = recording.recorded();
      if (calls.isEmpty()) {
        failures.add(exercise.name() + " made no peer call");
      }
      for (RecordingPeerClient.Recorded call : calls) {
        exercised.addAll(call.callers());
        reached.add(call.target());
        if (!provider(call.target()).equals(exercise.provider())) {
          failures.add(
              exercise.name() + " (a " + exercise.provider() + " row) calls " + call.target()
                  + ", which is " + provider(call.target()));
        }
      }
    }

    for (String method : clientMethods(clients)) {
      if (!exercised.contains(method) && !NO_ROW_OF_ITS_OWN.containsKey(method)) {
        failures.add(method + " calls a peer and no contract row makes that call");
      }
    }
    for (String excluded : NO_ROW_OF_ITS_OWN.keySet()) {
      if (!clientMethods(clients).contains(excluded)) {
        failures.add(excluded + " is excluded, but no client has that method any more");
      }
    }
    for (PeerTarget target : PeerTarget.values()) {
      if (!reached.contains(target)) {
        failures.add(target + " (" + provider(target) + ") is reached by no contract row");
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @Test
  void everyTokenMintHasAPactRow() {
    assertEquals(MINTED_BY_IDP_CONTRACT, classesHolding(OidcClient.class));
    assertEquals("issueToken", IdpContract.MINT.operationId());
    assertEquals(IdpContract.IDP, IdpContract.PACT.masters());
  }

  /** Every contract row, as a call against {@code peers}. */
  private static List<Exercise> exercises(PeerClient peers) {
    List<Exercise> all = new ArrayList<>();
    Clients projects = Clients.against(peers);
    for (ProjectsContract.Case row : ProjectsContract.CASES) {
      all.add(
          new Exercise(
              GoldenMasters.PROVIDER,
              row.description() + " [" + row.state() + "]",
              () -> row.call().run(projects, GoldenMasters.params(row.state()))));
    }
    PeerRow.Peers wired = PeerRow.Peers.over(peers);
    PeerConsumerPactTest.CONTRACTS.forEach(
        (provider, contract) -> {
          for (PeerRow row : contract.rows()) {
            var interaction = row.interaction();
            Map<String, String> params =
                contract.masters().recorded(interaction.state(), interaction.operationId())
                    ? contract.masters().params(interaction.state())
                    : Map.of();
            all.add(
                new Exercise(
                    provider,
                    interaction.description() + " [" + interaction.state() + "]",
                    () -> {
                      try {
                        row.call().run(wired, new PeerRow.Params(params));
                      } catch (RuntimeException | Error e) {
                        throw e;
                      } catch (Exception e) {
                        throw new IllegalStateException(e);
                      }
                    }));
          }
        });
    return all;
  }

  /** {@code Class#method} of every public instance method the client classes declare. */
  private static Set<String> clientMethods(Set<Class<?>> clients) {
    Set<String> methods = new TreeSet<>();
    for (Class<?> client : clients) {
      for (Method method : client.getDeclaredMethods()) {
        int modifiers = method.getModifiers();
        if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !method.isSynthetic()) {
          methods.add(client.getSimpleName() + "#" + method.getName());
        }
      }
    }
    return methods;
  }

  /** Every class of this module's main code that declares a field of {@code type}. */
  private static Set<Class<?>> classesHolding(Class<?> type) {
    Path root;
    try {
      root = Path.of(PeerClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
    Map<String, Class<?>> found = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
        String name = root.relativize(file).toString().replace('/', '.').replace('\\', '.');
        name = name.substring(0, name.length() - ".class".length());
        Class<?> candidate;
        try {
          candidate = Class.forName(name, false, CallCoverageTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
          continue;
        }
        for (Field field : candidate.getDeclaredFields()) {
          if (field.getType().equals(type)) {
            found.put(candidate.getName(), candidate);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Set.copyOf(found.values());
  }
}

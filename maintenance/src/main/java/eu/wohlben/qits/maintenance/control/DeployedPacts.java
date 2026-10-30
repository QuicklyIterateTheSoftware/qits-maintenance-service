package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.maintenance.bump.Calver;
import eu.wohlben.qits.maintenance.dto.DeployedPactsDto;
import eu.wohlben.qits.maintenance.dto.DeployedPactsDto.DeployedPactDto;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <b>WHICH CONSUMER PACTS A PROVIDER'S RELEASE GATE MUST VERIFY</b> (qits-1765): for every consumer
 * repository that publishes a pact against the provider, the pact version each of its deployed or
 * rollback versions was built against.
 *
 * <p>A provider that verifies only the pact its consumers' {@code main} pins can ship a change the
 * consumer that is actually SERVING breaks. The versions that matter are the ones qits-deployments
 * runs and would roll back to — {@link DeployedVersions}, the same fail-closed read {@code GET
 * /pins} makes — mapped to the repository that built them through {@link
 * ArtifactGraph#applicationProducers}, the release's own assertion.
 *
 * <h2>The newest pact at or below the deployed version</h2>
 *
 * <p>A pact is published as {@code eu.wohlben.qits:<consumer>-pacts-<provider>}, and only when it
 * CHANGES, so its versions are sparse: a consumer release with no pact row of its own was built
 * against the newest pact released before it. Both are the consumer's own calver, ordered by {@link
 * Calver} — numerically per segment, never as text. A deployed version with no pact at or below it
 * (a commit sha from before the deployer's V7, or a release older than the first pact) names no
 * pact and is skipped.
 *
 * <p><b>No pact row, no question.</b> A provider nobody publishes a pact against answers an empty
 * list without asking qits-deployments: that answer is complete whatever the deployer says.
 *
 * <p><b>The consumer is the artifact NAME's</b>, not {@code mt_artifact.repository}, which may hold
 * a qits-projects row id on old rows (see {@code ArtifactGraph.RepositoryNames}); the name is the
 * publish convention and spells the repository directly.
 */
@ApplicationScoped
public class DeployedPacts {

  /** The groupId every pact artifact of ours is published under. */
  public static final String GROUP_ID = "eu.wohlben.qits";

  /** Between the consumer and the provider in a pact artifactId. */
  static final String INFIX = "-pacts-";

  @Inject MaintenanceStore store;

  @Inject DeployedVersions deployed;

  @Inject ArtifactGraph graph;

  /**
   * The answer for one provider repository.
   *
   * @throws BadRequestException when no provider is named
   * @throws eu.wohlben.qits.maintenance.error.DeploymentsUnansweredException when qits-deployments
   *     cannot say what serves
   */
  public DeployedPactsDto of(String provider) {
    String name = provider == null ? "" : provider.trim();
    if (name.isEmpty()) {
      throw new BadRequestException("provider names the provider repository, and is required");
    }
    Map<String, NavigableSet<String>> pactVersions = pactVersionsByConsumer(name);
    if (pactVersions.isEmpty()) {
      return new DeployedPactsDto(name, List.of());
    }
    Map<String, Set<String>> deployedByRepository = deployedByRepository();

    // consumer -> pact version -> deployed versions; both keys sorted, which IS the answer's order.
    Map<String, Map<String, Set<String>>> picked = new TreeMap<>();
    pactVersions.forEach(
        (consumer, versions) -> {
          for (String version : deployedByRepository.getOrDefault(consumer, Set.of())) {
            if (!Calver.isCalver(version)) {
              continue;
            }
            String pact = versions.floor(version);
            if (pact == null) {
              continue;
            }
            picked
                .computeIfAbsent(consumer, key -> new TreeMap<>(Calver.ORDER))
                .computeIfAbsent(pact, key -> new TreeSet<>(Calver.ORDER.reversed()))
                .add(version);
          }
        });

    List<DeployedPactDto> pacts = new ArrayList<>();
    picked.forEach(
        (consumer, byPact) ->
            byPact.forEach(
                (pact, versions) ->
                    pacts.add(
                        new DeployedPactDto(
                            consumer,
                            GROUP_ID,
                            consumer + INFIX + name,
                            pact,
                            List.copyOf(versions)))));
    return new DeployedPactsDto(name, List.copyOf(pacts));
  }

  /** Every published calver of every consumer's pact against the provider, by consumer. */
  private Map<String, NavigableSet<String>> pactVersionsByConsumer(String provider) {
    String prefix = GROUP_ID + ":";
    String suffix = INFIX + provider;
    Map<String, NavigableSet<String>> byConsumer = new TreeMap<>();
    for (MtArtifact row : store.artifactsNamedWithSuffix(Ecosystem.MAVEN, suffix)) {
      if (!row.name.startsWith(prefix) || !Calver.isCalver(row.version)) {
        continue;
      }
      String consumer = row.name.substring(prefix.length(), row.name.length() - suffix.length());
      if (consumer.isEmpty()) {
        continue;
      }
      byConsumer.computeIfAbsent(consumer, key -> new TreeSet<>(Calver.ORDER)).add(row.version);
    }
    return byConsumer;
  }

  /**
   * The deployed and rollback versions of every repository, through the applications it produces:
   * a repository that two applications are built from serves the versions of both.
   */
  private Map<String, Set<String>> deployedByRepository() {
    Map<String, Set<String>> byApplication = deployed.byApplication();
    Map<String, Set<String>> byRepository = new TreeMap<>();
    graph
        .applicationProducers()
        .forEach(
            (application, repositories) -> {
              Set<String> versions = byApplication.get(application);
              if (versions == null || versions.isEmpty()) {
                return;
              }
              for (String repository : repositories) {
                byRepository.computeIfAbsent(repository, key -> new TreeSet<>()).addAll(versions);
              }
            });
    return byRepository;
  }
}

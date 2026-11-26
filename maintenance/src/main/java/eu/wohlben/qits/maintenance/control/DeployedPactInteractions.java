package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.maintenance.dto.DeployedPactInteractionsDto;
import eu.wohlben.qits.maintenance.dto.DeployedPactInteractionsDto.ConsumerPactDto;
import eu.wohlben.qits.maintenance.dto.DeployedPactInteractionsDto.InteractionDto;
import eu.wohlben.qits.maintenance.dto.DeployedPactsDto;
import eu.wohlben.qits.maintenance.dto.DeployedPactsDto.DeployedPactDto;
import eu.wohlben.qits.maintenance.error.PactJarUnreadableException;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>WHAT A PROVIDER'S DEPLOYED CONSUMERS ACTUALLY CALL</b> (qits-2702, for qits-toggles'
 * retirement, qits-2696): {@link DeployedPacts}' rows, each with the interactions its pact jar holds
 * against the provider.
 *
 * <p><b>A consumer that no longer declares the pact is left out.</b> Its last pact jar stays in the
 * registry and its deployed versions keep pointing at it, so without this a consumer that dropped
 * the provider altogether would count as calling it for ever. "Declares" is the consumer's {@code
 * contracts.pacts} as its latest scan read it ({@code mt_pact_edge}, qits-1952).
 *
 * <p><b>Fails closed.</b> qits-deployments' silence is {@link DeployedPacts}' 503; a jar that cannot
 * be read — missing, unreachable, not a jar, or holding no pact against the provider — is a 502
 * naming it. Never a partial list: a reader deciding "nobody calls this" must not decide it from a
 * read that skipped somebody.
 *
 * <p><b>Jars are cached by coordinate.</b> A released jar never changes, and the GC asks on every
 * run, so each (artifact, version) is downloaded once per process; only a {@link
 * PactJars.Outcome#READ} is kept, so a failure is asked again next time. The cache is bounded.
 */
@ApplicationScoped
public class DeployedPactInteractions {

  /** How many jars' interactions are kept in memory. */
  static final int CACHE_SIZE = 512;

  @Inject DeployedPacts deployedPacts;

  @Inject MaintenanceStore store;

  @Inject PactJars jars;

  private final Map<String, List<InteractionDto>> cache =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<InteractionDto>> eldest) {
          return size() > CACHE_SIZE;
        }
      };

  /**
   * The answer for one provider repository.
   *
   * @throws eu.wohlben.qits.maintenance.error.BadRequestException when no provider is named
   * @throws eu.wohlben.qits.maintenance.error.DeploymentsUnansweredException when qits-deployments
   *     cannot say what serves
   * @throws PactJarUnreadableException when a deployed consumer's pact jar cannot be read
   */
  public DeployedPactInteractionsDto of(String provider) {
    DeployedPactsDto deployed = deployedPacts.of(provider);
    if (deployed.pacts().isEmpty()) {
      return new DeployedPactInteractionsDto(deployed.provider(), List.of());
    }
    Set<String> declaring = new HashSet<>(store.pactConsumers(deployed.provider()));
    List<ConsumerPactDto> pacts = new ArrayList<>();
    for (DeployedPactDto pact : deployed.pacts()) {
      if (!declaring.contains(pact.consumerRepository())) {
        continue;
      }
      pacts.add(
          new ConsumerPactDto(
              pact.consumerRepository(),
              pact.pactVersion(),
              pact.deployedVersions(),
              interactions(pact, deployed.provider())));
    }
    return new DeployedPactInteractionsDto(deployed.provider(), List.copyOf(pacts));
  }

  /** Forgets every cached jar — for a test that re-publishes a coordinate with other content. */
  public void forgetJars() {
    synchronized (cache) {
      cache.clear();
    }
  }

  private List<InteractionDto> interactions(DeployedPactDto pact, String provider) {
    String coordinate = pact.groupId() + ":" + pact.artifactId() + ":" + pact.pactVersion();
    synchronized (cache) {
      List<InteractionDto> cached = cache.get(coordinate);
      if (cached != null) {
        return cached;
      }
    }
    PactJars.Result result =
        jars.interactions(pact.groupId(), pact.artifactId(), pact.pactVersion(), provider);
    if (!result.read()) {
      throw new PactJarUnreadableException(coordinate, result.url(), result.reason());
    }
    synchronized (cache) {
      cache.put(coordinate, result.interactions());
    }
    return result.interactions();
  }
}

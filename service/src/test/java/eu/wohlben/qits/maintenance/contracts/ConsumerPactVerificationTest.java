package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running provider</b>, the way qits-projects-service
 * does.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-maintenance-service.json} (repository names on both sides), this repo
 * pins that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases
 * a changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>Pinned today: qits-projects-service</b> ({@code
 * qits-projects-service-pacts-qits-maintenance-service}) <b>and qits-artifacts-service</b> ({@code
 * qits-artifacts-service-pacts-qits-maintenance-service}, the pins it reads), ticket qits-1149. {@link
 * ClasspathPactLoader#REQUIRED} is true, so a classpath that lost its pinned pacts fails rather than
 * verifying nothing.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP, as the
 * {@code %test} synthetic user — exactly as {@link GoldenMasterRecordingTest}'s calls run. Every
 * {@code @State} method delegates to {@link ProviderStates}; {@link #target} fails an unknown state,
 * and an interaction without {@code comments.references.qits-call} or {@code qits-trigger}.
 *
 * <p><b>A pinned pact that is wrong is named in {@link #KNOWN_WRONG}, by its jar's version</b>, and
 * its interaction is reported as skipped with the reason — never answered by bending the provider
 * to it. The entry is for that version alone: the bump that carries the consumer's fix verifies the
 * interaction for real, and a bump that does not fix it goes red here, which is the point.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-maintenance-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  /** A pinned pact interaction this provider cannot honour, and why: the consumer must fix it. */
  record KnownWrong(String consumer, String version, String description, String reason) {

    boolean names(Pact pact, Interaction interaction) {
      return consumer.equals(pact.getConsumer().getName())
          && description.equals(interaction.getDescription())
          && String.valueOf(pact.getSource()).contains("/" + version + "/");
    }
  }

  private static final String FAILURE_NOT_NULLABLE =
      "the pact binds $.automations[*].failure as an object in every element, but the golden master"
          + " it was built from (state 'a release request with automations') answers null for every"
          + " automation that did not fail — qits-projects-service's row builder drops the null"
          + " alternative when it merges the elements (ticket qits-1149)";

  private static final String FAILURE_NULL_REJECTED =
      "the pact matches $.automations[*].failure as object-or-null but with an object example, and"
          + " pact-jvm then rejects every honest failure: null ('Expected null (Null) to be the same"
          + " type as {...}') — a defect in qits-projects-service's row generator; the fix comes as a"
          + " new qits-projects-service-pacts-qits-maintenance-service version (ticket qits-1149)";

  static final List<KnownWrong> KNOWN_WRONG =
      List.of(
          new KnownWrong(
              "qits-projects-service",
              "2026.1011.161452",
              "AutomationRefresh.attempt: listReleaseRequestAutomations",
              FAILURE_NOT_NULLABLE),
          new KnownWrong(
              "qits-projects-service",
              "2026.1011.161452",
              "AutomationRefresh.attempt: triggerReleaseRequestAutomations",
              FAILURE_NOT_NULLABLE),
          new KnownWrong(
              "qits-projects-service",
              "2026.1018.195756",
              "AutomationRefresh.attempt: listReleaseRequestAutomations",
              FAILURE_NULL_REJECTED),
          new KnownWrong(
              "qits-projects-service",
              "2026.1018.195756",
              "AutomationRefresh.attempt: triggerReleaseRequestAutomations",
              FAILURE_NULL_REJECTED));

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-maintenance does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    for (KnownWrong wrong : KNOWN_WRONG) {
      if (wrong.names(pact, interaction)) {
        Assumptions.abort(
            consumer + " " + wrong.version() + " '" + interaction.getDescription() + "': "
                + wrong.reason());
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    context.verifyInteraction();
  }

  // --- the states: each one line into the registry -------------------------------------------

  @AfterEach
  void cleanUp() {
    states.cleanUp();
  }

  @State(ProviderStates.PENDING_BUMPS)
  Map<String, String> pendingBumps() {
    return states.params(ProviderStates.PENDING_BUMPS);
  }

  @State(ProviderStates.NO_PENDING_BUMPS)
  Map<String, String> noPendingBumps() {
    return states.params(ProviderStates.NO_PENDING_BUMPS);
  }

  @State(ProviderStates.MANIFESTS_THAT_PIN_ARTIFACTS)
  Map<String, String> manifestsThatPinArtifacts() {
    return states.params(ProviderStates.MANIFESTS_THAT_PIN_ARTIFACTS);
  }

  @State(ProviderStates.A_REPOSITORY_WITH_DOWNSTREAM_COMPONENTS)
  Map<String, String> aRepositoryWithDownstreamComponents() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_DOWNSTREAM_COMPONENTS);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WITH_AUTOMATIONS)
  Map<String, String> aReleaseRequestWithAutomations() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WITH_AUTOMATIONS);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WITH_A_FAILED_AUTOMATION)
  Map<String, String> aReleaseRequestWithAFailedAutomation() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WITH_A_FAILED_AUTOMATION);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN)
  Map<String, String> aReleaseRequestWithAnAutomationToRerun() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WITH_AUTOMATIONS_AT_ITS_NEWEST_FOLD)
  Map<String, String> aReleaseRequestWithAutomationsAtItsNewestFold() {
    return states.params(ProviderStates.A_RELEASE_REQUEST_WITH_AUTOMATIONS_AT_ITS_NEWEST_FOLD);
  }

  @State(ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN_FOR_A_WORK_ITEM)
  Map<String, String> aReleaseRequestWithAnAutomationToRerunForAWorkItem() {
    return states.params(
        ProviderStates.A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN_FOR_A_WORK_ITEM);
  }
}

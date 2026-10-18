package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-maintenance asks qits-ci, and why</b> (ticket qits-1149): the trigger that applies a
 * bump or an automation, the run it names, the automatic retry of a run, and the queue the
 * dispatcher paces itself by — every call {@link CiClient} makes.
 *
 * <p><b>The two triggers are pending.</b> qits-ci records {@code triggerEvent} for an {@code
 * SCMRelease} only, and the request is the recording's: a {@code MaintenanceBump} or {@code
 * ReleaseRequestAutomation} body needs a state of its own that answers that body with runs.
 */
final class CiContract {

  static final GoldenMasters CI = GoldenMasters.of("qits-ci-service", "qits-ci");

  static final String A_FAILED_RUN_THAT_RETRIES_ANOTHER = "a failed run that retries another";
  static final String RUNNERS_WITH_FREE_SLOTS = "runners with free slots";
  static final String A_MAINTENANCE_BUMP_PIPELINE =
      "a repository with a MaintenanceBump platform pipeline";
  static final String A_RELEASE_REQUEST_AUTOMATION_PIPELINE =
      "a repository with a ReleaseRequestAutomation platform pipeline";

  /** {@link CiClient#run}: what a sweep makes of one run. */
  static final List<String> RUN =
      List.of(
          "status",
          "repoId",
          "retryOfRunId",
          "autoRetry",
          "finishedAt",
          "steps[].status",
          "steps[].stepIndex",
          "steps[].image",
          "steps[].exitCode",
          "steps[].output");

  /** {@link CiClient#automaticRetryOf}: each listed run's id, and what {@link #RUN} reads. */
  static final List<String> RUNS = runs();

  /** {@link CiClient#trigger}: the event and the runs it started. */
  static final List<String> TRIGGERED = List.of("eventId", "runIds[]");

  private static final Change CHANGE =
      Change.of(
          Ecosystem.MAVEN, "pom.xml", "eu.wohlben.qits:qits-db-core", "2026.1.1", "2026.2.1", null);

  static final List<PeerRow> ROWS =
      List.of(
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("BumpDispatcher.tick"),
                      A_MAINTENANCE_BUMP_PIPELINE,
                      "triggerEvent")
                  .consumes(TRIGGERED),
              (peers, p) -> {
                CiClient.TriggerResult result =
                    peers
                        .ci()
                        .trigger(
                            p.get("bumpId"),
                            p.get("repositoryName"),
                            "dependencies",
                            "maintenance/dependencies",
                            "main",
                            List.of(CHANGE));
                assertEquals(CiClient.TriggerResult.Outcome.ACCEPTED, result.outcome());
              }),
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.operation("triggerReleaseRequestAutomations"),
                      A_RELEASE_REQUEST_AUTOMATION_PIPELINE,
                      "triggerEvent")
                  .consumes(TRIGGERED),
              (peers, p) -> {
                CiClient.TriggerResult result =
                    peers
                        .ci()
                        .triggerAutomation(
                            p.get("bumpId"),
                            p.get("kind"),
                            p.get("repositoryName"),
                            p.get("requestId"),
                            p.get("foldSha"),
                            "main",
                            p.get("branch"),
                            List.of("docs/"),
                            null,
                            Map.of());
                assertEquals(CiClient.TriggerResult.Outcome.ACCEPTED, result.outcome());
              }),
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("BumpService.sweep"),
                      A_FAILED_RUN_THAT_RETRIES_ANOTHER,
                      "getRun")
                  .consumes(RUN)
                  .anyLength("steps"),
              (peers, p) -> {
                CiClient.RunState run = peers.ci().run(p.get("runId"));
                assertTrue(run.terminal(), String.valueOf(run));
                assertEquals(p.get("repositoryId"), run.repoId());
                assertEquals(p.get("retryOfRunId"), run.retryOfRunId());
                assertTrue(run.autoRetry());
              }),
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("BumpService.sweep"),
                      A_FAILED_RUN_THAT_RETRIES_ANOTHER,
                      "listRuns")
                  .consumes(RUNS)
                  .anyLength("runs[].steps"),
              (peers, p) ->
                  assertTrue(
                      peers.ci().automaticRetryOf(p.get("repositoryId"), p.get("retryOfRunId"))
                          .readable())),
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("BumpDispatcher.tick"),
                      RUNNERS_WITH_FREE_SLOTS,
                      "getRunQueue")
                  .consumes(
                      "running[]",
                      "queued[]",
                      "runners[].connected",
                      "runners[].quarantined",
                      "runners[].slots")
                  .anyLength("running", "queued", "runners"),
              (peers, p) -> {
                CiClient.QueueState queue = peers.ci().queue();
                assertTrue(queue.readable(), queue.error());
                assertTrue(queue.slots() > 0, "a connected runner with free slots");
              }));

  static final PeerRow.Contract CONTRACT = new PeerRow.Contract(CI, ROWS);

  private static List<String> runs() {
    List<String> paths = new ArrayList<>();
    paths.add("runs[].id");
    RUN.forEach(path -> paths.add("runs[]." + path));
    return List.copyOf(paths);
  }

  private CiContract() {}
}

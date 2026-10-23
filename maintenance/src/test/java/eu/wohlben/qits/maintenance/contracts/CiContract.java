package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-maintenance asks qits-ci, and why</b> (ticket qits-1149): the trigger that applies a
 * bump or an automation, the run it names, the automatic retry of a run, and the queue snapshot the
 * dispatcher reads for whether qits-ci can be asked at all — every call {@link CiClient} makes.
 * <b>The queue carries no capacity any more</b> (epic qits-1481): its provider state was renamed
 * from {@code runners with free slots} to {@code a run queue with a run waiting}, and {@code
 * runners[]} is gone from the body, because a run is now a CI lease and qits-system's allocator,
 * never qits-ci, decides how many run at once.
 *
 * <p><b>Each trigger is its own state</b>, since the request is the recording's: qits-ci answers a
 * {@code MaintenanceBump} body with a run in {@code a repository a maintenance bump targets}. The
 * {@code ReleaseRequestAutomation} row waits on its state until qits-ci publishes it.
 */
final class CiContract {

  static final GoldenMasters CI = GoldenMasters.of("qits-ci-service", "qits-ci");

  static final String A_FAILED_RUN_THAT_RETRIES_ANOTHER = "a failed run that retries another";
  static final String A_RUN_QUEUE_WITH_A_RUN_WAITING = "a run queue with a run waiting";
  static final String A_REPOSITORY_A_BUMP_TARGETS = "a repository a maintenance bump targets";
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

  static final List<PeerRow> ROWS =
      List.of(
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("BumpDispatcher.tick"),
                      A_REPOSITORY_A_BUMP_TARGETS,
                      "triggerEvent")
                  .consumes(TRIGGERED),
              // The recording's body: a bump of group `targeted` with no changes. The request is
              // matched exactly, so the row sends what qits-ci recorded rather than a fuller plan.
              (peers, p) -> {
                CiClient.TriggerResult result =
                    peers
                        .ci()
                        .trigger(
                            p.get("eventId"),
                            p.get("repositoryName"),
                            "targeted",
                            p.get("branch"),
                            "main",
                            List.of());
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
                      A_RUN_QUEUE_WITH_A_RUN_WAITING,
                      "getRunQueue")
                  .consumes("running[]", "queued[]")
                  .anyLength("running", "queued"),
              (peers, p) -> {
                // No more runners[] (epic qits-1481): the dispatcher reads this only for whether
                // qits-ci can be asked at all, never for a slot count. See CiClient#queue and
                // BumpDispatcher's class doc.
                CiClient.QueueState queue = peers.ci().queue();
                assertTrue(queue.readable(), queue.error());
                assertEquals(2, queue.active(), "one running and one queued");
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

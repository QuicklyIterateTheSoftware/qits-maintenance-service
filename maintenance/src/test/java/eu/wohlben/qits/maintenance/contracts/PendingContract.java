package eu.wohlben.qits.maintenance.contracts;

import eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters.Trigger;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>The JSON calls qits-maintenance makes that no pact binds yet</b> (ticket qits-1149), because
 * the provider records no golden master for them: no answer exists to build the interaction from,
 * and this consumer does not invent one. {@code PendingContractTest} reports each row as skipped,
 * naming the provider state it waits for.
 *
 * <p>A row says everything the interaction will need once the provider records the state: the
 * provider repository, the trigger, the state, the operation, the request, and the body paths the
 * client reads ({@code consumes}, the same grammar as {@link ProjectsContract}). When the provider
 * publishes the recording, move the row into that provider's contract table and pin its golden
 * masters.
 *
 * <p><b>qits-ci, qits-githost and qits-artifacts publish no operationId</b> for these routes, so
 * {@code operation} is the method and route until they do.
 *
 * <p><b>One thing to settle before a second provider's golden masters are pinned:</b> every
 * {@code *-golden-masters} jar puts its tree at the same classpath root, {@code golden-masters/}, so
 * two on one test classpath shadow each other. {@code GoldenMasters} reads qits-projects' tree from
 * that root today.
 */
final class PendingContract {

  static final String PROJECTS = "qits-projects-service";
  static final String CI = "qits-ci-service";
  static final String GITHOST = "qits-githost-service";
  static final String ARTIFACTS = "qits-artifacts-service";

  /** One call with no recorded answer yet. */
  record Row(
      String provider,
      Trigger trigger,
      String state,
      String operation,
      String request,
      List<String> consumes) {

    String reason() {
      return "needs provider state '" + state + "' for " + operation + " in " + provider;
    }
  }

  private static final List<String> RUN =
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

  static final List<Row> ROWS =
      List.of(
          // --- qits-projects-service -------------------------------------------------------------
          new Row(
              PROJECTS,
              Trigger.schedule("ScanService.run"),
              "repositories in the catalog",
              "listRepositories",
              "GET /projects/api/repositories",
              List.of(
                  "repositories[].id",
                  "repositories[].projectId",
                  "repositories[].name",
                  "repositories[].mainBranch",
                  "repositories[].archetype")),
          new Row(
              PROJECTS,
              Trigger.schedule("BumpService.sweep"),
              "a repository with a pushed branch and no open release request",
              "createReleaseRequest",
              "POST /projects/api/repositories/{repositoryId}/release-requests"
                  + " {branch, summary, priority: LOWEST}",
              List.of("request.id", "request.state")),
          new Row(
              PROJECTS,
              Trigger.schedule("BumpService.sweep"),
              "a release request awaiting approval",
              "addReleaseRequestSource",
              "POST /projects/api/repositories/{repositoryId}/release-requests/{requestId}/sources"
                  + " {branch, priority: LOWEST}",
              List.of("request.state")),
          new Row(
              PROJECTS,
              Trigger.schedule("BumpDispatcher.tick"),
              "a repository with a release not merged to main",
              "listRepositoryReleaseRequests",
              "GET /projects/api/repositories/{repositoryId}/release-requests?state=all",
              List.of("requests[].version", "requests[].releasedSha", "requests[].mergedToMainAt")),
          new Row(
              PROJECTS,
              Trigger.schedule("BumpDispatcher.tick"),
              "a repository with a tagged release whose request went obsolete mid-publish",
              "listRepositoryReleaseRequests",
              "GET /projects/api/repositories/{repositoryId}/release-requests?state=all",
              List.of(
                  "requests[].id",
                  "requests[].version",
                  "requests[].state",
                  "requests[].pipeline.phases[].phase",
                  "requests[].pipeline.phases[].state")),
          // --- qits-ci-service -------------------------------------------------------------------
          new Row(
              CI,
              Trigger.schedule("BumpDispatcher.tick"),
              "a repository with a MaintenanceBump platform pipeline",
              "POST /ci/api/events/trigger",
              "POST /ci/api/events/trigger {name: MaintenanceBump, eventId, payload}",
              List.of("eventId", "runIds")),
          new Row(
              CI,
              Trigger.schedule("BumpService.sweep"),
              "a finished run with a failed step",
              "GET /ci/api/runs/{runId}",
              "GET /ci/api/runs/{runId}",
              RUN),
          new Row(
              CI,
              Trigger.schedule("BumpService.sweep"),
              "a run retried automatically",
              "GET /ci/api/runs",
              "GET /ci/api/runs?repositoryId={repositoryId}&limit=100",
              runs()),
          new Row(
              CI,
              Trigger.schedule("BumpDispatcher.tick"),
              "runners with free slots",
              "GET /ci/api/runs/queue",
              "GET /ci/api/runs/queue",
              List.of(
                  "running[]",
                  "queued[]",
                  "runners[].connected",
                  "runners[].quarantined",
                  "runners[].slots")),
          // --- qits-githost-service --------------------------------------------------------------
          new Row(
              GITHOST,
              Trigger.schedule("ScanService.run"),
              "a repository with manifests at a revision",
              "GET /git/{projectId}/{repoName}/tree/{revision}[/{path}]",
              "GET /git/{projectId}/{repoName}/tree/{revision}[/{path}] (reads the Git-Commit-Sha"
                  + " header too)",
              List.of("entries[].name", "entries[].type", "entries[].mode", "entries[].sha")),
          new Row(
              GITHOST,
              Trigger.schedule("BumpDispatcher.tick"),
              "a repository whose main contains a released commit",
              "GET /githost/api/repositories/{repoId}/contains",
              "GET /githost/api/repositories/{repoId}/contains?commit={sha}&in={sha}",
              List.of("contains")),
          new Row(
              GITHOST,
              Trigger.schedule("AutomationBranchSweep.sweep"),
              "a repository with automation branches",
              "GET /githost/api/repositories/{repoId}",
              "GET /githost/api/repositories/{repoId}",
              List.of("branches")),
          new Row(
              GITHOST,
              Trigger.schedule("AutomationBranchSweep.sweep"),
              "a repository with an automation branch",
              "DELETE /githost/api/repositories/{repoId}/branches/{name}",
              "DELETE /githost/api/repositories/{repoId}/branches/{name}?projectId=&repoName= (a 404"
                  + " body naming no-such-branch is read as already gone)",
              List.of()),
          // --- qits-artifacts-service ------------------------------------------------------------
          new Row(
              ARTIFACTS,
              Trigger.event("SoftwareRelease"),
              "a released artifact with an SBOM",
              "GET /artifacts/sboms/{type}/{name}/-/{version}",
              "GET /artifacts/sboms/{type}/{name}/-/{version}",
              List.of(
                  "metadata.component.bom-ref",
                  "components[].bom-ref",
                  "components[].purl",
                  "components[].name",
                  "components[].version",
                  "dependencies[].ref",
                  "dependencies[].dependsOn")),
          new Row(
              ARTIFACTS,
              Trigger.schedule("SbomCheckService.probe"),
              "a released daemon",
              "GET /artifacts/api/repositories/{repo}/daemons/{daemon}/versions",
              "GET /artifacts/api/repositories/daemons/daemons/{daemon}/versions",
              List.of("versions[].version")),
          new Row(
              ARTIFACTS,
              Trigger.schedule("BumpDispatcher.tick"),
              "a repository with published changelogs",
              "GET /artifacts/docs/docs/@changelog/{repository}",
              "GET /artifacts/docs/docs/@changelog/{repository}",
              List.of("versions[].version")));

  /** {@code CiClient.automaticRetryOf}: each listed run's id, and what {@link #RUN} reads. */
  private static List<String> runs() {
    List<String> paths = new ArrayList<>();
    paths.add("runs[].id");
    RUN.forEach(path -> paths.add("runs[]." + path));
    return List.copyOf(paths);
  }

  private PendingContract() {}
}

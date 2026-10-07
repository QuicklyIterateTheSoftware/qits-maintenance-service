package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.dto.AdoptionJourneyDto;
import eu.wohlben.qits.maintenance.dto.ArtifactDto;
import eu.wohlben.qits.maintenance.dto.BumpDto;
import eu.wohlben.qits.maintenance.dto.BumpWindowDto;
import eu.wohlben.qits.maintenance.dto.PendingBumpsDto;
import eu.wohlben.qits.maintenance.dto.DependencyDto;
import eu.wohlben.qits.maintenance.dto.DownstreamDto;
import eu.wohlben.qits.maintenance.dto.DependentDto;
import eu.wohlben.qits.maintenance.dto.DependentsDto;
import eu.wohlben.qits.maintenance.dto.GroupDto;
import eu.wohlben.qits.maintenance.dto.PinDto;
import eu.wohlben.qits.maintenance.dto.PinSourceDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDependentsDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDetailDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDto;
import eu.wohlben.qits.maintenance.dto.SbomCheckReportDto;
import eu.wohlben.qits.maintenance.dto.ScanDto;
import eu.wohlben.qits.maintenance.dto.TransitiveDto;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type Jackson touches on this API.
 *
 * <p>{@code ScanController.scan} and {@code RepositoryController.bump} return {@code
 * Response.entity(...)}, which hides the entity type from the build-time analysis — so in the
 * native binary serialization fails at runtime with a 500 while every JVM test stays green.
 * Measured on a sibling, not theoretical: qits-serviceregistry's first live {@code PUT
 * /services/{name}} answered 500 on exactly this.
 *
 * <p>Some of these types happen to be reachable today through a declared return type; they are all
 * listed anyway, because which ones the analysis finds is an implementation detail no test guards.
 *
 * <p><b>A new response type joins this list in the commit that adds it.</b>
 */
@RegisterForReflection(
    targets = {
      RepositoryController.AcceptedResponse.class,
      // The release-request automations' three doors (qits-978). The two request bodies are
      // deserialized, the 202 rides in a Response.entity, and the answer is the wire contract
      // qits-projects' gate reads — a 500 in the binary there would hold every release request.
      ReleaseRequestAutomationController.TriggerRequest.class,
      ReleaseRequestAutomationController.RunRequest.class,
      ReleaseRequestAutomationsDto.class,
      ReleaseRequestAutomationsDto.AutomationDto.class,
      ScanController.StartScanRequest.class,
      ScanController.StartScanRequest.Response.class,
      ArtifactController.IngestRequest.class,
      ArtifactController.IngestRequest.Accepted.class,
      RepositoryDto.class,
      RepositoryDetailDto.class,
      GroupDto.class,
      PinDto.class,
      DependencyDto.class,
      DependencyDto.DependencyPinDto.class,
      // The sbom side. `IngestRequest.Accepted` above is the one that is strictly required — it
      // rides in a Response.entity — and the rest join it for the reason the note above gives:
      // which of these the build-time analysis happens to find is an implementation detail no test
      // guards, and the failure mode is a 500 in the binary while the JVM suite stays green.
      ArtifactDto.class,
      DependentsDto.class,
      DependentDto.class,
      RepositoryDependentsDto.class,
      RepositoryDependentsDto.ArtifactDependentsDto.class,
      TransitiveDto.class,
      // The GC's pin source. Nothing renders it — qits-artifacts reads it through the orchestrator
      // — so a missing registration would be invisible until a collection ran against a 500.
      PinSourceDto.class,
      PinSourceDto.RepositoryStateDto.class,
      PinSourceDto.ArtifactPinDto.class,
      // BumpDto grew `releaseRequestId` with the release door, and the bump detail page reads it.
      BumpDto.class,
      // The dispatch window's three verbs on /bumps/window, and the diagnosis the GET carries: the
      // stalled entries are a nested record and are their own registration, exactly as every other
      // nested shape here is.
      BumpWindowDto.class,
      PendingBumpsDto.class,
      BumpWindowDto.StalledBumpDto.class,
      BumpWindowDto.OwedBumpDto.class,
      ScanDto.class,
      Change.class,
      // The ad-hoc downstream closure and the adoption journey, which replaced the release trains'
      // four records. `DownstreamDto` is the one that would hurt most: it is a wire contract
      // qits-projects reads on its announce path, so a missing registration would be a 500 in the
      // binary that degrades another service's event enrichment with nothing failing here.
      DownstreamDto.class,
      DownstreamDto.EntryDto.class,
      AdoptionJourneyDto.class,
      AdoptionJourneyDto.PackageDto.class,
      AdoptionJourneyDto.AdopterDto.class,
      // The daily SBOM check's report: the POST /sbom-check/runs 202 carries it in a
      // Response.entity, and the same records are read back out of mt_sbom_check_run's JSON.
      SbomCheckReportDto.class,
      SbomCheckReportDto.EntryDto.class,
      SbomCheckReportDto.TicketDto.class,
      // mt_gitlink_tree.pins is a JSON column written by MaintenanceStore.recordGitlinkTree as a
      // List<TreePin> — Jackson touches it on the write side the same way it touches a
      // Response.entity return, and the build-time analysis is no better at seeing through it. The
      // read side rebuilds TreePin by hand off a List<Map<String, Object>> and does not need this,
      // but the write does: without it every gitlink-tree scan dies in the binary with "could not
      // write a json column" while the JVM suite stays green.
      MaintenanceStore.TreePin.class
    })
final class ApiWireReflection {

  private ApiWireReflection() {}
}

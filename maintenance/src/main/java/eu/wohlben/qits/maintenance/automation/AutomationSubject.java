package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.entity.MtRepository;
import java.util.List;

/**
 * What a kind is asked about: one repository's release request at one fold.
 *
 * @param repository the inventory row — name, project, archetype, main branch. What applicability
 *     is decided from, beside the fold itself
 * @param requestId the release request, as qits-projects mints it
 * @param foldSha the fold this outcome is for: the request's {@code mergedSha}
 * @param foldRef the branch qits-projects folds the request into — {@code release/<qualifiedId>}
 *     for a newer request, {@code release/<uuid>} for an older one (qits-1158). What a run fetches,
 *     and checks against {@link #foldSha} before it starts
 * @param sourceBranches the request's named branches that are not its main branch and not one of
 *     the automations' own ({@code maintenance/automations/**}): the branches a SOURCE_BRANCHES kind
 *     may write
 * @param workItem the work item a commit subject names, or null
 * @param fold the tree at {@link #foldSha}
 * @param qualifiedId the request's logical id, {@code <repository>-rr-<n>}, or null when qits-projects
 *     did not say (qits-1158)
 */
public record AutomationSubject(
    MtRepository repository,
    String requestId,
    String foldSha,
    String foldRef,
    List<String> sourceBranches,
    String workItem,
    FoldReader fold,
    String qualifiedId) {

  /** What a sentence calls the request: its logical id when known, else its uuid. */
  public String name() {
    return qualifiedId == null || qualifiedId.isBlank() ? requestId : qualifiedId;
  }

  /** A subject with no logical id. */
  public AutomationSubject(
      MtRepository repository,
      String requestId,
      String foldSha,
      String foldRef,
      List<String> sourceBranches,
      String workItem,
      FoldReader fold) {
    this(repository, requestId, foldSha, foldRef, sourceBranches, workItem, fold, null);
  }

  public AutomationSubject {
    sourceBranches = sourceBranches == null ? List.of() : List.copyOf(sourceBranches);
  }
}

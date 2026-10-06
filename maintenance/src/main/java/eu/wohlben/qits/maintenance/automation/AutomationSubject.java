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
 * @param foldRef the branch qits-projects folds the request into, {@code release/<request>} — what
 *     a run fetches, and checks against {@link #foldSha} before it starts
 * @param sourceBranches the request's named branches that are not its main branch and not one of
 *     the automations' own ({@code maintenance/automations/**}): the branches a SOURCE_BRANCHES kind
 *     may write
 * @param workItem the work item a commit subject names, or null
 * @param fold the tree at {@link #foldSha}
 */
public record AutomationSubject(
    MtRepository repository,
    String requestId,
    String foldSha,
    String foldRef,
    List<String> sourceBranches,
    String workItem,
    FoldReader fold) {

  public AutomationSubject {
    sourceBranches = sourceBranches == null ? List.of() : List.copyOf(sourceBranches);
  }
}

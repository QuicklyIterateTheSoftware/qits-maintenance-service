package eu.wohlben.qits.maintenance.dto;

import java.util.List;

/**
 * <b>The automation kinds catalogue</b> (epic qits-1038): {@code GET
 * /maintenance/api/automations/kinds}, the registered {@code ReleaseRequestAutomation} beans this
 * build offers, so qits-projects' {@code AutomationContentSource} can open an approval requirement
 * for a fold that changes a kind's committable paths without holding a kind table of its own.
 *
 * <p><b>Sorted by kind</b>, the same order {@code AutomationService.kinds()} already answers in; a
 * kind's {@code approvalRoles} are sorted too, so two reads over an unchanged registry answer the
 * same bytes.
 *
 * @param kinds every kind this build offers (registered and switched on)
 */
public record AutomationKindsDto(List<KindDto> kinds) {

  /**
   * One kind, as the catalogue serves it.
   *
   * @param kind the wire name: {@code screenshot-baselines}, {@code entity-diagram}, {@code
   *     estate-pins}, {@code dependency-bump}
   * @param label what the release request page prints: {@code Screenshot baselines}
   * @param committablePaths the git pathspecs a run of this kind may commit; nothing else is ever
   *     staged
   * @param approvalRoles the roles that may approve a fold this kind commits into — {@code person},
   *     {@code agent}, both, or empty when the kind needs no approval — sorted
   */
  public record KindDto(
      String kind, String label, List<String> committablePaths, List<String> approvalRoles) {}
}

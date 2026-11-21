package eu.wohlben.qits.maintenance.dto;

import java.util.List;

/**
 * <b>Everything downstream of one repository — a WIRE CONTRACT, not merely a page's shape.</b>
 *
 * <p>qits-projects reads this on its release-request announce path and folds the names into
 * {@code ReleaseRequestChanged.downstreamTechnicalComponents}, which qits-ci orders its build queue
 * by. Three repositories build against these field names; changing one is a plan edit and a
 * conversation, exactly as {@code qits-maintenance-plan.md} says.
 *
 * <p><b>The order is the information.</b> Depth ascending then name ascending, so a consumer reading
 * it top to bottom reads "upstream first" — which is what a build queue needs and the only reason
 * the field is worth carrying on an event.
 *
 * <p><b>An unknown repository answers 200 with an empty list.</b> The caller is an announce path; a
 * repository this inventory has never scanned must cost it nothing.
 *
 * <p><b>Every name is a LIVE name, and a retired repository is not in it</b> (qits-2375). A
 * repository renamed since it released is reported once, under the name the catalog lists now —
 * never under the old one its artifacts were filed under — and one whose own inventory row is
 * retired (ABSENT or UNDECLARED, with no live row answering for its catalog id) is neither an entry
 * nor a way through to one: it only arrives through an old bill of materials and will never release
 * again. The shape is unchanged; only which names can appear in it is.
 *
 * @param repository the root as this inventory keys it — a CATALOG NAME, whichever spelling was
 *     asked for; a retired name reads as the live name on its catalog id when one answers for it
 * @param catalogId qits-projects' row id for the root, or null when this inventory has no row
 * @param downstream everything that consumes it, transitively; the wrapper (archetype {@code
 *     PROJECT}) is excluded and so is the root itself
 */
public record DownstreamDto(String repository, String catalogId, List<EntryDto> downstream) {

  /**
   * One repository downstream of the root.
   *
   * @param archetype what the catalog says it IS, verbatim and unvalidated — a word this build does
   *     not know is served as it was stored
   * @param depth hops from the root; 1 is a direct consumer
   * @param via the repositories one hop nearer the root that this one was reached through, name
   *     ascending. More than one when two upstreams both lead here.
   */
  public record EntryDto(
      String repository, String catalogId, String archetype, int depth, List<String> via) {}
}

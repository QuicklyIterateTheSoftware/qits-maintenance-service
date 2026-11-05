package eu.wohlben.qits.maintenance.pending;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;


/**
 * One edit, exactly as the bump payload carries it.
 *
 * <p><b>This record IS the wire shape</b> — it is serialised straight into the {@code changes}
 * array of the {@code MaintenanceBump} trigger and stored verbatim on the bump row. The field names
 * are the plan's and a rename here is a change to a contract two repositories build against.
 *
 * @param ecosystem which of the pipeline's two steps can apply it
 * @param manifestPath the file to edit, repository-relative
 * @param name the dependency
 * @param from the version in the file now, so the step can refuse an edit against a file that moved
 * @param to the version to write
 * @param location where in the file: a property, a dependency element, a block or a line number
 */
public record Change(
    String ecosystem, String manifestPath, String name, String from, String to, String location) {

  public static Change of(
      Ecosystem ecosystem, String manifestPath, String name, String from, String to, String location) {
    return new Change(ecosystem.wireName(), manifestPath, name, from, to, location);
  }
}

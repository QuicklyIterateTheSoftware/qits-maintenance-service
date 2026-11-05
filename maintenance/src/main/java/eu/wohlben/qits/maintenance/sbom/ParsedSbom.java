package eu.wohlben.qits.maintenance.sbom;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.util.List;

/**
 * One CycloneDX document, as much of it as this service could read.
 *
 * <p><b>There is no "unreadable" outcome here.</b> A document that carried no dependency graph, or
 * whose root could not be identified, still yields the components it listed — and the {@link
 * #problems} say what was lost. A parser that refused the whole document over one malformed entry
 * would turn a producer's small mistake into an artifact that appears to contain nothing, which
 * reads exactly like an artifact with no dependencies.
 *
 * @param rootRef the {@code bom-ref} of {@code metadata.component}, null when the document named
 *     none — in which case nothing is direct and every component is recorded as transitive
 * @param components everything the document listed, in its own order
 * @param edges who pulled in whom, by index into {@link #components}
 * @param problems what could not be read, one line each, for the ingest row's error column and for
 *     a person comparing this reading with the document
 */
public record ParsedSbom(
    String rootRef, List<Component> components, List<Edge> edges, List<String> problems) {

  /**
   * One entry of {@code components[]}, with this service's reading of its purl beside the purl.
   *
   * @param bomRef the document's identifier for it, which the adjacency refers to
   * @param purl the package url verbatim, null when the document carried none
   * @param ecosystem the mapped world, or null when the purl was absent or of a type this service
   *     does not inventory — such a component is stored and shown and never matched
   * @param name {@code mt_pin}'s spelling when the purl mapped, the component's own {@code name}
   *     field when it did not
   * @param version the version, from the purl or from the component's own field
   * @param direct whether the ROOT declares it
   */
  public record Component(
      String bomRef,
      String purl,
      Ecosystem ecosystem,
      String name,
      String version,
      boolean direct) {}

  /**
   * One "A pulled in B", by position in {@link ParsedSbom#components}.
   *
   * @param parent the index of the component that depends, or -1 for the root
   * @param child the index of the component that is depended on
   */
  public record Edge(int parent, int child) {}

  /** A document that yielded nothing at all, with the reason. */
  public static ParsedSbom empty(String problem) {
    return new ParsedSbom(null, List.of(), List.of(), List.of(problem));
  }
}

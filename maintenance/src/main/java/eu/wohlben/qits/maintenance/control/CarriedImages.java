package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.maintenance.dto.PinSourceDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * <b>THE IMAGE A POM PIN NAMES.</b> Every container image a repository's manifest pins without
 * spelling out — because the version it spells out is a maven or npm artifact whose release put an
 * image of the same calver in the registry.
 *
 * <p><b>Why the keep-set would otherwise have a hole in it.</b> Container image versions used to
 * live in qits-configuration entries, and qits-artifacts read {@code GET /configuration/api/pins} as
 * a pin source. Every consumer has since moved to pinning a MAVEN coordinate whose own version IS
 * the image tag — qits-workspaces at {@code qits-workspace-daemon-protocol} and {@code
 * qits-workspace-editor-image}, qits-projects at {@code qits-projects-daemon-protocol} — so that
 * source now answers {@code {"pins": []}} and holds nothing at all. What is DEPLOYED is still named,
 * by each launching service's own effective-pin door; what lost its cover is the window between a
 * bump landing on main and the consumer deploying, because the bump moves a maven coordinate and
 * this source therefore kept the jar and said nothing about the image of the same version. Measured
 * live 2026-09-17: qits-projects-service pinned {@code qits-projects-daemon-protocol 2026.917.90046}
 * and nothing anywhere named {@code qits/project-agent:2026.917.90046}, whose retention window is
 * {@code P0D}.
 *
 * <p><b>The mapping is the RELEASE's own assertion, never a table.</b> A repository publishing a jar
 * and an image out of one reactor stamps both with the release's version; {@code
 * .config/qits/release.yml} declares them and the {@code SoftwareRelease} frame announces them, which
 * is what filled {@code mt_artifact}. So the rule is a join over two facts this service already
 * holds and nothing anybody has to remember to update:
 *
 * <blockquote>
 * for a pin at {@code (ecosystem, name, version)}, the repository that RELEASED that coordinate, and
 * every DOCKER artifact that same repository released at that same version.
 * </blockquote>
 *
 * <p><b>They join, and they still do not merge.</b> {@code mt_pin} says what a bump edits and {@code
 * mt_artifact} says what a release published; the reading here reaches across {@code (ecosystem,
 * name)} exactly as {@link ArtifactGraph} does everywhere else, and writes through neither. What
 * comes out is a pin row in the sense the consumer means — a version some repository's main branch
 * still references — and {@link PinSourceDto.ArtifactPinDto#via()} names the coordinate it was
 * resolved through, so a keep this produced is never mistaken for a {@code FROM} line somebody wrote.
 *
 * <p><b>A derived row is a DOCKER row in the answer, not a new array.</b> The consumer keeps it under
 * the rule it already has — "referenced by a repository manifest on main" — which is the true
 * sentence: the pom property IS the image tag, and serving what a consumer will actually fetch rather
 * than the literal text of a manifest line is what this source already does for an npm range read
 * through its lock. A seventh keep-set would instead have been a wire change every reader of the
 * supplied pin document has to land before this one can ship, for a keep that is not a different
 * kind of fact.
 *
 * <p><b>No artifact row, no keep, and that is honest rather than defensive.</b> Nothing asserted an
 * image for a version this service never saw released, and inventing one would put a name in a
 * keep-set that no release ever carried. The window that matters is the freshly released version,
 * whose row {@code SoftwareReleaseListener} writes off the frame whatever becomes of its SBOM.
 *
 * <p><b>The walk itself moved to {@link CarriedArtifacts} when {@link CarriedDaemons} needed it
 * verbatim.</b> That is the algorithm — the producer lookup, the de-duplication, the {@code via},
 * and the stored row winning where both exist; what stays here is which artifact type is carried and
 * why this particular hole was worth closing. The two callers differ in two tokens and in nothing
 * else, which is the reason they are one piece of code and not two.
 */
@ApplicationScoped
public class CarriedImages {

  @Inject ArtifactGraph graph;

  /**
   * The docker rows to serve BESIDE the stored pins, derived from them.
   *
   * @param pins the stored pin rows, already filtered to INTERNAL and to a known ecosystem — which
   *     is what makes the producer lookup below meaningful: an external coordinate is somebody
   *     else's release and carries none of our images
   * @return the derived rows, in no particular order; the caller sorts them into the one total order
   *     the answer is served in
   */
  public List<PinSourceDto.ArtifactPinDto> resolve(List<PinSourceDto.ArtifactPinDto> pins) {
    return CarriedArtifacts.resolve(
        pins, graph, Ecosystem.DOCKER.wireName(), graph::imagesReleasedWith);
  }
}

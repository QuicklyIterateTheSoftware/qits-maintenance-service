package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.maintenance.dto.PinSourceDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * <b>THE DAEMON BINARY A POM PIN NAMES.</b> Every binary in the platform's {@code daemons} store
 * that a repository's manifest pins without spelling out — because the version it spells out is a
 * maven or npm artifact whose release published a daemon of the same calver.
 *
 * <p><b>A sibling of {@link CarriedImages}, one artifact type over, and the SAME hole.</b> That one
 * closed it for container images when image versions became pom pins; this one closes it for the
 * {@code qits} CLI, whose version became a pom pin the same way. qits-ci pins {@code
 * eu.wohlben.qits:qits-platform-access-cli-binary}, and that artifact's version IS the {@code
 * daemons}-store coordinate of the binary the same release published — {@code
 * qits-platform-access-cli/.config/qits/release.yml} declares the pair, {@code {type: daemon, name:
 * qits-platform-access-cli}} beside {@code {type: maven, name:
 * eu.wohlben.qits:qits-platform-access-cli-binary}}.
 *
 * <p><b>What rots without it.</b> The {@code daemons} store collects at {@code window=P0D}, keeping
 * the last two versions and whatever a pin source names. Nothing named the binary: the pin is a
 * maven coordinate, so this source kept the jar and said nothing about the daemon of the same
 * version. Two releases of the CLI later, the version the pom still pins is gone from the store and
 * every release pipeline that fetches the CLI 404s — a pin that rots with nothing bumping it and
 * nothing holding it back.
 *
 * <p><b>The mapping is the RELEASE's own assertion, never a table</b>, exactly as it is for images:
 * a repository publishing a jar and a binary out of one reactor stamps both with the release's
 * version, {@code .config/qits/release.yml} declares them, and the {@code SoftwareRelease} frame
 * announces them into {@code mt_artifact}. What that took here was one change at the WRITE end —
 * {@code SoftwareReleaseListener} used to discard a {@code daemon} release before the artifact row
 * was opened, so the join below had nothing to find.
 *
 * <p><b>A derived row is a {@code daemon} row in the answer, not a new array.</b> The consumer keeps
 * it under the rule it already has — "referenced by a repository manifest on main" — which is the
 * true sentence: the pom property IS the store coordinate. The spelling is a CONTRACT: qits-artifacts'
 * {@code MaintenanceHttpDependencyPins} files a row by exactly this word and refuses the whole pin
 * source on one it cannot file, which is also why it is deployed first — every GC run fails closed
 * until it is.
 *
 * <p><b>It is still not an {@link Ecosystem}</b>, and the row's word is the literal {@code
 * Ecosystem#DAEMON_WIRE_NAME}. A fifth constant costs a parser, a resolver and a bump step, and a
 * daemon binary has no manifest to parse, no registry to ask and no line to edit. What it has is a
 * release — which is the half of this schema keyed by the stored string.
 *
 * <p><b>{@code via} and "no artifact row, no keep" are {@link CarriedArtifacts}' and are not
 * restated here.</b> A derived row names the PINNING repository and its manifest, carries the
 * carrier coordinate in {@code via}, and is therefore never mistaken for a line somebody wrote;
 * a version this service never saw released yields nothing at all.
 */
@ApplicationScoped
public class CarriedDaemons {

  @Inject ArtifactGraph graph;

  /**
   * The daemon rows to serve BESIDE the stored pins, derived from them.
   *
   * @param pins the stored pin rows, already filtered to INTERNAL and to a known ecosystem
   * @return the derived rows, in no particular order; the caller sorts them into the one total order
   *     the answer is served in
   */
  public List<PinSourceDto.ArtifactPinDto> resolve(List<PinSourceDto.ArtifactPinDto> pins) {
    return CarriedArtifacts.resolve(
        pins, graph, Ecosystem.DAEMON_WIRE_NAME, graph::daemonsReleasedWith);
  }
}

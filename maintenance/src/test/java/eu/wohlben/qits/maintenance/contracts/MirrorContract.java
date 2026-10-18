package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.List;

/**
 * <b>What qits-maintenance asks qits-mirror, and why</b> (ticket qits-1149): the latest version of
 * an EXTERNAL pin, read through the mirror's Maven Central and npmjs pull-through caches ({@code
 * LatestResolver}). The mirror is a qits service, so the read is a qits call, even though what it
 * serves came from upstream.
 *
 * <p><b>Both rows are pending.</b> qits-mirror records its own API ({@code /mirror/api/...}) and no
 * answer of its caches, so there is no {@code maven-metadata.xml} or packument to bind yet. The
 * metadata is XML: that row binds the status alone, since the consumer helper binds JSON paths only.
 */
final class MirrorContract {

  static final GoldenMasters MIRROR = GoldenMasters.of("qits-mirror-service", "qits-mirror");

  static final String A_CACHED_MAVEN_ARTIFACT = "a cached maven artifact with published versions";
  static final String A_CACHED_NPM_PACKAGE = "a cached npm package with published versions";

  static final List<PeerRow> ROWS =
      List.of(
          new PeerRow(
              GoldenInteraction.of(
                  Trigger.schedule("ScanService.run"),
                  A_CACHED_MAVEN_ARTIFACT,
                  ArtifactsContract.GET_MAVEN_METADATA),
              (peers, p) -> {
                LatestLookup latest =
                    peers.latest().resolve(Ecosystem.MAVEN, PinKind.EXTERNAL, p.get("name", ArtifactsContract.MAVEN_NAME));
                assertTrue(latest.ok(), latest.error());
              }),
          new PeerRow(
              GoldenInteraction.of(
                      Trigger.schedule("ScanService.run"),
                      A_CACHED_NPM_PACKAGE,
                      ArtifactsContract.GET_NPM_PACKUMENT)
                  .consumes("versions", "dist-tags.latest"),
              (peers, p) -> {
                LatestLookup latest =
                    peers.latest().resolve(Ecosystem.NPM, PinKind.EXTERNAL, p.get("name"));
                assertTrue(latest.ok(), latest.error());
              }));

  static final PeerRow.Contract CONTRACT = new PeerRow.Contract(MIRROR, ROWS);

  private MirrorContract() {}
}

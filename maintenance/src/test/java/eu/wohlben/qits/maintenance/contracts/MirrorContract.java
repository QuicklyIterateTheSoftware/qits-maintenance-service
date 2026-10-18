package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>Each cache is read in a state that holds the name and one where upstream has none (404). The
 * metadata is XML, which the consumer helper binds no paths of, so its 200 row binds the status
 * alone — and waits ({@link PeerRow#readingBody}) while qits-mirror records that 200 without the
 * document, since the {@code <versions>} the client reads is all the answer is for.
 */
final class MirrorContract {

  static final GoldenMasters MIRROR = GoldenMasters.of("qits-mirror-service", "qits-mirror");

  static final String A_CACHED_MAVEN_ARTIFACT = "a cached maven artifact with published versions";
  static final String NO_MAVEN_ARTIFACT_UPSTREAM = "no maven artifact of that name upstream";
  static final String A_CACHED_NPM_PACKAGE = "a cached npm package with published versions";
  static final String NO_NPM_PACKAGE_UPSTREAM = "no npm package of that name upstream";

  private static final Trigger SCAN = Trigger.schedule("ScanService.run");

  /** A maven name the cache does not hold, should a state carry no {@code name}. */
  private static final String MAVEN_NAME = "org.example:contract-library";

  static final List<PeerRow> ROWS =
      List.of(
          PeerRow.readingBody(
              GoldenInteraction.of(SCAN, A_CACHED_MAVEN_ARTIFACT, ArtifactsContract.GET_MAVEN_METADATA),
              (peers, p) -> {
                LatestLookup latest =
                    peers.latest().resolve(Ecosystem.MAVEN, PinKind.EXTERNAL, p.get("name", MAVEN_NAME));
                assertTrue(latest.ok(), latest.error());
              }),
          new PeerRow(
              GoldenInteraction.of(SCAN, NO_MAVEN_ARTIFACT_UPSTREAM, ArtifactsContract.GET_MAVEN_METADATA),
              (peers, p) ->
                  assertFalse(
                      peers.latest().resolve(Ecosystem.MAVEN, PinKind.EXTERNAL, p.get("name", MAVEN_NAME)).ok())),
          new PeerRow(
              GoldenInteraction.of(SCAN, A_CACHED_NPM_PACKAGE, ArtifactsContract.GET_NPM_PACKUMENT)
                  .consumes("versions", "dist-tags.latest"),
              (peers, p) -> {
                LatestLookup latest =
                    peers.latest().resolve(Ecosystem.NPM, PinKind.EXTERNAL, p.get("package"));
                assertTrue(latest.ok(), latest.error());
              }),
          new PeerRow(
              GoldenInteraction.of(SCAN, NO_NPM_PACKAGE_UPSTREAM, ArtifactsContract.GET_NPM_PACKUMENT),
              (peers, p) ->
                  assertFalse(peers.latest().resolve(Ecosystem.NPM, PinKind.EXTERNAL, p.get("package")).ok())));

  static final PeerRow.Contract CONTRACT = new PeerRow.Contract(MIRROR, ROWS);

  private MirrorContract() {}
}

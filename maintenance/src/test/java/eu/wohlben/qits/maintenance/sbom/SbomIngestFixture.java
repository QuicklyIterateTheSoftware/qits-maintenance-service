package eu.wohlben.qits.maintenance.sbom;

import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;

/**
 * A real {@link SbomIngestService} over a real {@link SbomClient}, with qits-artifacts replaced at
 * the peer — for a test outside this package, which cannot set the injected fields itself. The
 * daily SBOM check's suite is the reader: its re-read must go through the shipped fetch and write,
 * not through a stand-in of them.
 */
public final class SbomIngestFixture {

  private SbomIngestFixture() {}

  /** The ingest, reading the SBOM route through {@code peers}. Its queue is unset: nothing here queues. */
  public static SbomIngestService over(MaintenanceStore store, PeerClient peers) {
    SbomClient client = new SbomClient();
    client.peers = peers;
    SbomIngestService ingest = new SbomIngestService();
    ingest.store = store;
    ingest.client = client;
    return ingest;
  }
}

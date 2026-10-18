package eu.wohlben.qits.maintenance.contracts;

import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.catalog.CatalogReader;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.TestPeerClient;
import eu.wohlben.qits.maintenance.sbomcheck.TicketClient;

/**
 * The real clients a contract row drives, each pointed at one pact mock server.
 *
 * <p>Every client takes its one dependency, {@code PeerClient peers}, by field injection, and the
 * field is package-private in each client's own package. Setting it by reflection keeps the
 * contract tests in one package without widening a production field for a test.
 */
record Clients(TicketClient tickets, ReleaseRequestClient releases, CatalogReader catalog) {

  static Clients against(String baseUrl) {
    return against(new TestPeerClient(baseUrl));
  }

  /** The same clients over any {@link PeerClient} — the call guard's recording one, too. */
  static Clients against(PeerClient peers) {
    return new Clients(
        wire(new TicketClient(), peers),
        wire(new ReleaseRequestClient(), peers),
        wire(new CatalogReader(), peers));
  }

  static <T> T wire(T client, PeerClient peers) {
    try {
      var field = client.getClass().getDeclaredField("peers");
      field.setAccessible(true);
      field.set(client, peers);
      return client;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(client.getClass().getSimpleName() + " has no peers field", e);
    }
  }
}

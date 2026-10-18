package eu.wohlben.qits.maintenance.contracts;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.bump.changelog.ChangelogClient;
import eu.wohlben.qits.maintenance.control.DeployedVersions;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.GitHostRefs;
import eu.wohlben.qits.maintenance.latest.LatestResolver;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.sbom.SbomClient;
import eu.wohlben.qits.maintenance.sbomcheck.ArtifactPresence;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import java.util.List;
import java.util.Map;

/**
 * <b>One row of a consumer pact against a peer other than qits-projects</b> (ticket qits-1149): the
 * {@link GoldenInteraction} (trigger, provider state, operation, what the client reads) and the
 * real client call that makes it.
 *
 * <p>The call takes the wired clients ({@link Peers}) and the state's params, so the same row runs
 * twice: against a pact mock server in {@code PeerConsumerPactTest}, and against the call guard's
 * recording double in {@code CallCoverageTest}, which is how the guard knows which client method a
 * row exercises — including a row whose provider state is not recorded yet.
 *
 * @param companions rows the call makes AFTER this one, in the same consumer path, which the mock
 *     must serve too: the git host reader asks the root tree after a 404 on a path, to tell a
 *     missing path from a missing commit. The row is in the pact file only once every companion is
 *     recorded, since until then its call cannot be run
 */
record PeerRow(GoldenInteraction interaction, Call call, List<GoldenInteraction> companions) {

  PeerRow(GoldenInteraction interaction, Call call) {
    this(interaction, call, List.of());
  }

  /** The real client call, asserting what the client made of the answer. */
  @FunctionalInterface
  interface Call {
    void run(Peers peers, Params params) throws Exception;
  }

  /** The state's params; a name the state does not hold reads as a placeholder (guard runs only). */
  record Params(Map<String, String> values) {
    String get(String name) {
      return get(name, "contract-" + name);
    }

    /** {@link #get(String)}, with the placeholder a client needs to make the call at all. */
    String get(String name, String placeholder) {
      String value = values.get(name);
      return value != null ? value : placeholder;
    }
  }

  /** Every peer client this service has, each over one {@link PeerClient}. */
  record Peers(
      CiClient ci,
      DeployedVersions deployed,
      SbomClient sboms,
      ChangelogClient changelogs,
      LatestResolver latest,
      ArtifactPresence presence,
      GitHostRefs refs,
      GitHostReader git) {

    static Peers over(PeerClient peers) {
      return new Peers(
          Clients.wire(new CiClient(), peers),
          Clients.wire(new DeployedVersions(), peers),
          Clients.wire(new SbomClient(), peers),
          Clients.wire(new ChangelogClient(), peers),
          Clients.wire(new LatestResolver(), peers),
          Clients.wire(new ArtifactPresence(), peers),
          Clients.wire(new GitHostRefs(), peers),
          Clients.wire(new GitHostReader(), peers));
    }
  }

  /** What one consumer asks one provider: its rows, and the pact the runnable ones make. */
  record Contract(GoldenMasters masters, List<PeerRow> rows) {

    /** The pact of every row whose companions are recorded (the helper keeps only recorded rows). */
    ConsumerPact pact() {
      return ConsumerPact.of(
          eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters.CONSUMER,
          masters,
          rows.stream().filter(this::runnable).map(PeerRow::interaction).toList());
    }

    /** Whether every companion of {@code row} is recorded, so its call can run. */
    boolean runnable(PeerRow row) {
      return row.companions().stream().allMatch(c -> masters.recorded(c.state(), c.operationId()));
    }
  }
}

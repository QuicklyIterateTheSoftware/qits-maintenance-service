package eu.wohlben.qits.maintenance.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The catalog: every repository this service is responsible for.
 *
 * <p><b>qits-projects is the source of truth and nothing here caches it beyond one scan.</b> A
 * repository that leaves the catalog stops being scanned on the next run — and, since 2026-09-03,
 * <b>its inventory follows it out</b>: a full scan marks the row ABSENT and drops its pins and its
 * groups. See {@code ScanService.reconcile}. The old stance was that a listing which briefly lost a
 * name is not evidence that a repository is gone; what that produced was 96 rows against a catalog
 * of 48 and a nightly bump asking qits-ci for branches on names nobody holds. The doubt is answered
 * where it belongs instead — a read that FAILED and a read that listed NOTHING both reconcile
 * nothing at all, so only a listing this service actually believes is ever acted on.
 *
 * <p><b>A row its project no longer declares is still read</b> (qits-1695): it is a repository
 * qits-projects lists but no wrapper entry names, and a full scan marks it UNDECLARED rather than
 * ABSENT — listed, so not gone, but not maintained either. See {@link CatalogEntry#declared}.
 *
 * <p><b>A row with no name is skipped.</b> qits-projects lists rows whose alias is not set, and
 * every read this service makes is name-addressed — there is no address for such a row, so it is
 * dropped with a debug line rather than turned into a repository whose every scan fails.
 */
@ApplicationScoped
public class CatalogReader {

  /** The whole listing, in one call. It takes no paging and no filter. */
  public static final String PATH = "/projects/api/repositories";

  private static final Logger LOG = Logger.getLogger(CatalogReader.class);

  @Inject PeerClient peers;

  /**
   * Every repository in the catalog, or the reason there is no answer.
   *
   * <p>An empty list and a failure are different results and the caller must not confuse them: a
   * scan that read an empty catalog would leave every repository unscanned and report nothing
   * wrong.
   */
  public Result read() {
    PeerAnswer answer = peers.get(PeerTarget.PROJECTS, PATH).answer();
    if (!answer.ok()) {
      return new Result(List.of(), "the catalog could not be read: " + answer.failure());
    }
    JsonNode root = answer.json();
    if (root == null || !root.hasNonNull("repositories") || !root.get("repositories").isArray()) {
      return new Result(List.of(), "the catalog answered a body with no repositories array");
    }
    List<CatalogEntry> entries = new ArrayList<>();
    for (JsonNode row : root.get("repositories")) {
      Optional<CatalogEntry> entry = entry(row);
      if (entry.isPresent()) {
        entries.add(entry.get());
      } else {
        LOG.debugf("Skipping a catalog row with no addressable name: %s", row);
      }
    }
    return new Result(List.copyOf(entries), null);
  }

  static Optional<CatalogEntry> entry(JsonNode row) {
    String name = text(row, "name");
    String project = text(row, "projectId");
    if (name == null || project == null) {
      return Optional.empty();
    }
    String mainBranch = text(row, "mainBranch");
    // The row's own id, kept beside the name and never used as an address here. It is what OTHER
    // contexts spell a repository with — see CatalogEntry.catalogId — and a listing that carried
    // none is still a repository worth scanning, so it is read and never required.
    String catalogId = text(row, "id");
    // WHAT KIND OF THING IT IS, READ VERBATIM AND NEVER VALIDATED HERE. The key is nullable over
    // there and may be absent from an older answer entirely; both read as null, and neither is a
    // reason to skip a row — a repository whose archetype nobody recorded still pins things. Nor
    // is an UNKNOWN spelling: qits-projects' vocabulary grows without asking this service, so the
    // string is carried through to the store as it arrived and only the train layer decides
    // whether it means anything. See CatalogEntry.archetype and model/RepositoryArchetype.
    String archetype = text(row, "archetype");
    // WHETHER ITS PROJECT STILL CARRIES IT (qits-1695). Only an explicit false undeclares a row: a
    // provider older than the field answers no key at all, and treating that as false would stop
    // maintaining the whole estate the day this reader shipped first.
    JsonNode declared = row.get("declared");
    boolean isDeclared = declared == null || !declared.isBoolean() || declared.booleanValue();
    return Optional.of(
        new CatalogEntry(
            project,
            name,
            mainBranch == null ? "main" : mainBranch,
            catalogId,
            archetype,
            isDeclared));
  }

  private static String text(JsonNode row, String field) {
    JsonNode value = row.get(field);
    if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    return value.asText();
  }

  /**
   * The catalog, or why there is none.
   *
   * @param entries every addressable repository, empty when the read failed
   * @param error the sentence, or null when the read succeeded
   */
  public record Result(List<CatalogEntry> entries, String error) {

    public boolean ok() {
      return error == null;
    }
  }
}

package eu.wohlben.qits.maintenance.scan;

import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtGitlinkPin;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore.GitlinkPin;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore.TreePin;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * <b>THE NPM PINS A REPOSITORY REACHES THROUGH ITS GITLINKS</b> — the lockfile of each submodule at
 * the commit the gitlink records, which is what the carrying repository's build will {@code npm ci}
 * (qits-740).
 *
 * <p><b>Why the keep-set had a hole without it.</b> Fifteen services build their frontend out of a
 * submodule at {@code service/src/main/webui}. {@code ManifestScanner} pins the gitlink itself — the
 * commit — and deliberately never walks into it, because the frontend is catalogued and scanned in
 * its own right. But the frontend's own row says what its MAIN pins, and the service builds the
 * frontend at the GITLINKED commit, which lags. So the {@code @qits/*} versions that lockfile pins
 * were named by no pin source at all, and a GC that took one broke that service's {@code npm ci}
 * with E404 (2026-09-05).
 *
 * <p><b>Resolved at SCAN time and stored, so {@code GET /pins} stays a pure read.</b> The tree is
 * read through {@link ManifestScanner#pinsAt} — the same discovery the release ledger reads a tag
 * with, so the submodule's own {@code ignore:} is honoured — and only for a submodule the CATALOG
 * lists, because that is what says which project the git host addresses it under.
 *
 * <p><b>One read per {@code (submodule, sha)}, ever.</b> A commit's tree does not change, so the
 * answer is remembered in {@code mt_gitlink_tree} and a gitlink that has not moved costs a database
 * read and no call. A tree pinning nothing is remembered too.
 *
 * <p><b>INTERNAL by the same rule as a direct npm pin</b> — {@link MaintenanceConfig#kindOf(ParsedPin)}
 * on the parsed pin, or its {@code (ecosystem, name)} half on a remembered one. Every npm pin is
 * remembered and the rule is applied here, so a changed scope list needs no cache invalidation.
 *
 * <p><b>A tree that cannot be read loses NO rows.</b> The rows that gitlink path carried last time
 * are kept as they were — their {@code sha} still names the commit they were read at, so the {@code
 * via} stays honest — with a warning, and the next scan asks again. It is the scan's own rule for an
 * unreachable repository, one hop further out: a peer that could not be asked is not evidence that
 * anything stopped being pinned.
 */
@ApplicationScoped
public class GitlinkNpmPins {

  private static final Logger LOG = Logger.getLogger(GitlinkNpmPins.class);

  @Inject ManifestScanner manifests;

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  /**
   * The rows to store for one repository, from the pins its scan just read.
   *
   * @param repository the repository carrying the gitlinks
   * @param pins every pin the scan read — the GITLINK ones are the only ones looked at
   * @param catalog the whole catalog listing by name, which is how a submodule finds its project
   * @return every row to serve for this repository, kept ones included
   */
  public List<GitlinkPin> resolve(
      String repository, List<ParsedPin> pins, Map<String, CatalogEntry> catalog, Instant now) {
    List<GitlinkPin> rows = new ArrayList<>();
    List<MtGitlinkPin> previous = null;
    for (ParsedPin pin : pins) {
      if (pin.ecosystem() != Ecosystem.GITLINK) {
        continue;
      }
      String submodule = pin.name();
      String sha = pin.version();
      String path = pin.manifestPath();
      CatalogEntry entry = catalog.get(submodule);
      if (entry == null || sha == null || sha.isBlank()) {
        // Not a repository this platform catalogues: there is no project to address it under, and
        // nothing of ours is built out of it.
        continue;
      }
      Optional<List<TreePin>> tree = tree(entry, submodule, sha, repository, path, now);
      if (tree.isEmpty()) {
        if (previous == null) {
          previous = store.gitlinkPins(repository);
        }
        for (MtGitlinkPin kept : previous) {
          if (kept.gitlinkPath.equals(path)) {
            rows.add(GitlinkPin.of(kept));
          }
        }
        continue;
      }
      for (TreePin treePin : tree.get()) {
        if (config.kindOf(Ecosystem.NPM, treePin.name()) != PinKind.INTERNAL) {
          continue;
        }
        rows.add(
            new GitlinkPin(
                path,
                submodule,
                sha,
                Ecosystem.NPM.wireName(),
                treePin.name(),
                treePin.version(),
                path + "/" + treePin.manifestPath()));
      }
    }
    return List.copyOf(rows);
  }

  /** The submodule's npm pins at {@code sha}: remembered, or read and remembered, or empty. */
  private Optional<List<TreePin>> tree(
      CatalogEntry entry, String submodule, String sha, String repository, String path,
      Instant now) {
    Optional<List<TreePin>> cached = store.gitlinkTree(submodule, sha);
    if (cached.isPresent()) {
      return cached;
    }
    ManifestScanner.Pins read;
    try {
      read = manifests.pinsAt(entry.project(), submodule, sha);
    } catch (RuntimeException e) {
      read = new ManifestScanner.Pins(FileLookup.Status.UNREACHABLE, null, List.of(), e.toString());
    }
    if (read.status() != FileLookup.Status.FOUND) {
      LOG.warnf(
          "%s's gitlink %s pins %s at %s, whose tree could not be read (%s: %s); the npm pins it"
              + " reached last time are kept",
          repository, path, submodule, sha, read.status(), read.message());
      return Optional.empty();
    }
    List<TreePin> pins = new ArrayList<>();
    for (ParsedPin pin : read.pins()) {
      if (pin.ecosystem() != Ecosystem.NPM || pin.unresolved() || pin.reactorOwn()) {
        continue;
      }
      pins.add(new TreePin(pin.name(), pin.version(), pin.manifestPath()));
    }
    store.recordGitlinkTree(submodule, sha, pins, now);
    return Optional.of(List.copyOf(pins));
  }
}

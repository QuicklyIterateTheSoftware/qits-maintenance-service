package eu.wohlben.qits.maintenance.bump.changelog;

import java.util.List;

/**
 * The changelogs one change pulls in: the {@code changelog} field of a change on the {@code
 * MaintenanceBump} payload (epic qits-893).
 *
 * <p><b>It names changelogs and never carries one.</b> The payload reaches the bump step as a single
 * environment string, capped at about 128 KiB, and a twenty-dependency bump with several releases
 * each would cross it; {@code qits changelog bump-message} fetches each {@code
 * @changelog/<repository>} at each version from the docs store and composes the commit message.
 * Every changelog named here was proved to exist before the trigger was sent.
 *
 * @param repository the source repository — the docs site's second segment and the {@code ## }
 *     heading of the commit body
 * @param versions every release after the old pin up to and including the new one, oldest first in
 *     calver order; never empty
 */
public record ChangelogRange(String repository, List<String> versions) {

  public ChangelogRange {
    versions = versions == null ? List.of() : List.copyOf(versions);
  }
}

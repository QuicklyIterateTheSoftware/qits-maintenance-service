package eu.wohlben.qits.maintenance.latest;

import java.util.List;

/**
 * Every version one registry has published of one dependency, or why that could not be read.
 *
 * <p>The whole list rather than its highest entry, because one question needs exactly that: <b>is
 * a given version published for EVERY artifact a shared maven property versions</b> (qits-1206). A
 * reactor that publishes content-addressed uploads only the modules whose content changed, so the
 * newest version of one of its artifacts says nothing about whether its sibling exists at that
 * version — the {@link LatestLookup} column cannot answer it, the version list can.
 *
 * <p><b>A failure is a result, not an exception</b>, for the same reason {@link LatestLookup}'s is.
 *
 * @param versions what the registry lists, in its own order; empty when the lookup failed
 * @param sourceUrl the url that was read, so a surprising answer can be reproduced by hand
 * @param error the sentence, or null when the lookup succeeded
 */
public record PublishedLookup(List<String> versions, String sourceUrl, String error) {

  public PublishedLookup {
    versions = versions == null ? List.of() : List.copyOf(versions);
  }

  public static PublishedLookup found(List<String> versions, String sourceUrl) {
    return new PublishedLookup(versions, sourceUrl, null);
  }

  public static PublishedLookup failed(String sourceUrl, String error) {
    return new PublishedLookup(List.of(), sourceUrl, error);
  }

  public boolean ok() {
    return error == null;
  }
}

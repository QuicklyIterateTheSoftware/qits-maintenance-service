package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * <b>THE KEEP-SET THE ARTIFACT GC READS: every internal registry artifact a catalogued repository's
 * main branch still references, and the freshness of the inventory that says so.</b>
 *
 * <p>It is the third of qits-artifacts' pin sources, behind qits-platform-orchestrator: what the
 * running services deploy, what the images name, and — this one — what the manifests pin. A version
 * named here is a version a build would resolve tomorrow, so deleting it would break a repository
 * nobody has touched.
 *
 * <p><b>Two lists, and the second is the reason the first is served at all.</b> {@code pins} is the
 * answer; {@code repositories} is what the answer is worth. A consumer that keeps only what it is
 * told to keep has no way to tell a repository that pins nothing from one this service could not
 * read this morning — so every row of the inventory is served beside the pins, with the status it
 * carries and the moment it was last read.
 *
 * <p><b>The rows are served AS STORED and are deliberately not folded.</b> Five repositories pinning
 * one library are five rows, each naming its repository and its manifest, because the consumer's own
 * question is "who still holds this" the moment it decides not to delete something. Folding them
 * here would answer a smaller question and lose the only field that makes a keep decision
 * explainable.
 *
 * @param generatedAt when this answer was read out of the store — the moment the two lists below
 *     agree on, not a cached one
 * @param repositories every repository the inventory holds, ordered by name
 * @param pins every internal maven, npm and docker pin, in one deterministic order — including the
 *     {@code docker} and {@code daemon} rows RESOLVED out of a maven or npm pin whose release
 *     stamped an image or a daemon binary with the same version, which carry a {@code via} and are
 *     otherwise rows like any other
 */
public record PinSourceDto(
    Instant generatedAt, List<RepositoryStateDto> repositories, List<ArtifactPinDto> pins) {

  /**
   * One repository's freshness, which is the only thing about it this answer is about.
   *
   * <p>No pin counts and no groups: a consumer reviewing whether the keep-set can be trusted asks
   * when the inventory was last read and whether the read succeeded, and both are here.
   *
   * @param name the catalog name
   * @param status OK, ABSENT, UNREACHABLE or CONFIG_ERROR
   * @param lastScanAt when the last scan of it finished, null when it has never been scanned
   * @param headSha the commit its pins were read at
   */
  public record RepositoryStateDto(
      String name, String status, Instant lastScanAt, String headSha) {}

  /**
   * One pin, as one manifest of one repository wrote it — or, where {@link #via} is set, as one
   * manifest of one repository pins it without spelling it out.
   *
   * <p><b>What a row names is what the consumer would FETCH, not the characters in the file.</b> An
   * npm row carries the LOCK's resolved version rather than the range its {@code package.json}
   * writes, and a docker row with a {@code via} carries the image a maven or npm pin names by
   * carrying the version the same release stamped on it — see {@code control/CarriedImages}. Both
   * are the same resolution, and a keep-set built out of literal text would miss both.
   *
   * @param ecosystem maven, npm, docker or — on a derived row only — {@code daemon}, the platform's
   *     binary store, which is not one of this service's ecosystems and is spelled exactly so
   *     because the consumer matches the word. Never gitlink, whose version is a commit sha rather
   *     than a registry artifact
   * @param name the artifact in its own ecosystem's spelling, which is the registry coordinate
   * @param version the exact version referenced; for npm the LOCK's resolved one, because that is
   *     what an install actually fetches out of the registry
   * @param repository the repository whose manifest holds the line — for a gitlink row, the
   *     repository CARRYING the gitlink, whose build installs the version
   * @param manifestPath where that line is, relative to the repository root; for a gitlink row the
   *     submodule's manifest prefixed by the gitlink path
   * @param via the {@code <ecosystem> <name>} of the coordinate this row was RESOLVED through, or
   *     {@code gitlink:<path>@<sha>} on an npm row read out of a submodule's lock at the commit the
   *     repository's gitlink records, and null on a row a manifest wrote out. It is provenance and
   *     no part of the keep: a reviewer asking why an image nobody's {@code FROM} line mentions is
   *     being kept reads the pom property that carries its tag here.
   */
  public record ArtifactPinDto(
      String ecosystem,
      String name,
      String version,
      String repository,
      String manifestPath,
      String via) {}
}

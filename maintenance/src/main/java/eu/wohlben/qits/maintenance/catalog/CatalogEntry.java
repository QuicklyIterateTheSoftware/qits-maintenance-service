package eu.wohlben.qits.maintenance.catalog;

/**
 * One repository as the catalog names it — the coordinate every other read this service makes is
 * built from.
 *
 * @param project the project id the git host serves it under; the first half of
 *     {@code /git/<project>/<repo>}
 * @param name the addressable repository name
 * @param mainBranch the branch a scan reads, as qits-projects records it
 * @param catalogId the catalog row's own opaque id — qits-projects' {@code id} field. NOTHING here
 *     is addressed by it: every read this service makes is name-addressed and stays that way. It is
 *     carried because OTHER contexts address a repository by it — qits-ci's {@code SoftwareRelease}
 *     spells its {@code repository} field this way — so it is the only thing that can turn such a
 *     wire value back into a name. Null when the listing carried none, which is a row nothing can
 *     translate rather than a row to skip.
 * @param archetype what kind of thing the repository is, <b>verbatim as the catalog spelled it</b>
 *     — SERVICE, DAEMON, LIBRARY, FRONTEND, CLI, IMAGE, PROJECT, SERVICE_TEMPLATE, FORK, or
 *     whatever qits-projects adds next. A raw String rather than {@link
 *     eu.wohlben.qits.maintenance.model.RepositoryArchetype} on purpose: the vocabulary is theirs
 *     and this record's job is to carry what arrived, so a word this service has never heard of
 *     reaches the store intact instead of becoming a null nobody can debug. Parsing is {@code
 *     RepositoryArchetype.of}, and it happens where a decision is taken — the train layer — not
 *     here. Null when the listing carried none, which is a repository worth scanning like any
 *     other.
 * @param declared whether a wrapper entry names the repository — qits-projects' {@code declared}
 *     field (qits-1695). A row that is listed but not declared is a retired repository its project
 *     no longer carries: it is marked {@code UNDECLARED} by a full scan and never scanned, bumped or
 *     planned. A listing that carried no such key, or no boolean under it, reads as declared, so an
 *     older qits-projects keeps every row maintained.
 */
public record CatalogEntry(
    String project,
    String name,
    String mainBranch,
    String catalogId,
    String archetype,
    boolean declared) {}

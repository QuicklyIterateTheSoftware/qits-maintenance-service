# qits-platform-maintenance — working notes

Read `README.md` first: it defines the model, the parsers, the pending rule, the bump round trip,
the routes and the config keys. This file is the working conventions on top of it.

## The rules that shape everything

**This service DECIDES; a CI step APPLIES.** It clones nothing, edits no file and pushes no ref. A
bump is a payload naming a file, a location and two versions, and the step that reads it is the only
thing that touches a repository. Anything that would have this process write into somebody else's
tree goes in the pipeline instead.

**The contract is pinned by `qits-maintenance-plan.md` in the qits-qits wrapper.** The route shapes,
the model, the config keys and the bump payload are written down there and three repositories build
against them. Changing one of those shapes is a plan edit and a conversation, not a commit here.

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior `mvn
install` elsewhere, no credentials. That is why the poms duplicate versions instead of inheriting
them, why the suite spawns its own PostgreSQL from a Maven artifact, and why the peers are faked at
the client rather than stubbed with a server.

**The one thing it needs besides Maven Central** is the platform's own Maven repository, for
`qits-db-core`, `qits-auth-core` and `qits-arch-rules`. `<repositories>` in the root pom points at
`${qits.maven.repository.url}`; the image build overrides it through `.qits-maven-settings.xml`,
which mirrors the exact repository id `qits-maven` — an exact id match is what gets past Maven's
`external:http:*` blocker.

**The gate is `./mvnw clean verify -Dquarkus.http.test-port=0`**, and it needs BOTH a node on PATH
and `git submodule update --init`. Always `clean` — incremental compilation leaves stale generated
classes behind when a shape changes. Port 0 is not optional on the deployment host: 8081 is the
platform's own npm registry there.

**Anything returned as `Response.entity(...)` is invisible to the build-time Jackson analysis**,
which is what `api/ApiWireReflection` exists for. The 202 from a queued scan, the 202 from a
requested bump and the 202 from a manual sbom ingest are exactly such responses. A new response type joins that list in the commit that
adds it; the failure is a 500 in the native binary while every JVM test stays green.

**`bus/EventWireReflection` is the second registration and the same rule from the other side**: the
event bus binds through an `ObjectMapper` the library builds by hand, so every frame and payload type
is invisible to the same analysis. A new listener's payload record joins that list in the commit that
adds it. See "The event bus".

## Reading another repository

**Resolve the head sha once, read every file at it.** `GitHostReader.head` reads the root tree at
`main` and takes `Git-Commit-Sha` off the answer, which resolves the branch AND proves the
repository is readable in one call. A scan that read manifests at `main` would produce an inventory
of whatever moved while it ran — pins that correspond to no commit that ever existed.

**A tree entry carries a name, a type and — where the host reports them — a mode and a sha.** The
last two are optional and absent on the deployed git host, which is what makes a gitlink readable in
principle and unpinnable in practice. Read them; never require them; never substitute for them.

**A 404 is not an answer until it has been asked twice.** The git host spells "no such revision" and
"no such path" the same way, so a 404 on a file is followed by the root tree at the same sha:
answered means ABSENT, 404 means GONE. The five-outcome vocabulary is qits-ci's
`HttpGitConfigSource`; keep them matching.

**Discovery is the ROOT plus the reactor and nothing else.** A recursive walk would read every
vendored file in every repository on the platform for a handful of pins, and it would pull in
`service/src/main/webui` — a gitlink to an SPA's own repository, which the catalog lists in its own
right. The module walk refuses any path containing `src/main/webui` outright. The gitlink ITSELF is
a pin — see GITLINK under "Parsers" — and that is a different fact from what it contains.

**Nothing over the wire throws.** A scan reads every repository in the catalog; one unreachable git
host must cost one row's status, not the run. Every failure comes back as a `PeerAnswer` carrying
the sentence.

**An unreachable repository KEEPS ITS PINS.** `markRepository` writes the status and leaves the
inventory alone — a peer that could not be asked is not evidence that a repository stopped pinning
anything, and wiping on every hiccup would make "pending" flicker to zero whenever the git host
restarted.

**A repository the CATALOG dropped does not, and that is the opposite case rather than the same
one.** `ScanService.reconcile` marks every unlisted row ABSENT with `dropped from the catalog` and
deletes its `mt_pin` and `mt_group` rows; the row itself, its `catalog_id` and the three log tables
stay. The scan used to only ever upsert, so a rename left the old name at OK with its pins for ever
— **measured 2026-09-03**: 96 rows against a catalog of 48, ~800 pending, and 23 of the first 30
nightly bumps FAILED with `no run recorded for MaintenanceBump` against ghosts. The difference
between the two rules is the EVIDENCE: an unreachable git host says nothing about anything, while a
catalog that answered its whole listing and did not name a repository has said something. That is
why the three refusals are absolute — a scan of ONE repository never reconciles (a listing of one is
evidence about one, and `ScanTrigger.EVENT` fires it on every push), a FAILED catalog read never
does, and a listing that is EMPTY never does. The last is spelled twice, in `ScanService` and again
in `MaintenanceStore.reconcileCatalog`, because reconciling against nothing empties the whole store
in one transaction. `ScanReconciliationTest` is where all five arms are pinned.

## Parsers

**XML-aware, never a regular expression.** A pom is read to find WHERE a version is set, and the
step edits by that location — so a wrong location is a wrong edit in somebody else's repository. A
line-based reader cannot tell a `<version>` inside `dependencyManagement` from one inside a plugin,
or a commented-out block from a live one. `manifest/Xml` is the DOM, with external entities and the
DOCTYPE off: every document comes off another service over HTTP.

**Expressions are resolved in the groupId and the artifactId, not only the version.** That is not a
refinement: the first live scan over 49 repositories DIED on `${project.groupId}` reaching
`URI.create` verbatim, because only the version was ever expanded. Maven's built-in coordinates are
resolved from the pom's own — with a module inheriting its parent's groupId and version, which is
maven's rule — and every spelling is known, `project.*`, the deprecated `pom.*` and the bare ones.

**A property is remembered, not merely expanded**, and a property defined in the ROOT pom makes the
pin belong to the ROOT pom. Recording the module would send the step to a file that does not hold
the value. A BUILT-IN is never a `property:` location: no file holds `${project.version}`.

**Anything still carrying `${` after that is RECORDED, not dropped and not guessed at.** It is
visible on the repository page as `kind: UNRESOLVED`, and nothing is asked of a registry about it.
A value that never became a version must never become a URL.

**THE REACTOR IS READ BEFORE IT IS PARSED, and that ordering is load-bearing.** "Is this dependency
one of our own modules" cannot be answered from a single pom, and it is what decides whether a pin
is bumpable at all. A one-pass parser offered `eu.wohlben.qits:qits-ci-domain` as an upgrade to
qits-ci — an offer to overwrite what its own release stamps. Two consequences, both in
`ManifestScanner`: a dependency whose `g:a` is in the reactor is `REACTOR`, and a **parent** that is
in the reactor is not recorded at all. A parent from outside it stays a pin.

**Only what has a line is a pin.** A maven dependency with no version takes one from a BOM; an npm
dependency the lock does not resolve is a lock out of step with its manifest; a `FROM` with a digest
or no tag has no order. None of them is recorded — an inventory entry nothing can bump is noise on
every page it appears on.

**Adding an ecosystem is a parser, a resolver and a pipeline step together.** Two of the three is a
column that fills up and never moves.

**GITLINK is the fourth, and its "resolver" is the BUS.** `manifest/GitmodulesParser` reads
`.gitmodules` for a submodule's path and — off the URL's basename, never the `[submodule "..."]`
entry — its repository name; the VERSION is the mode-`160000` entry the tree carries at that path,
because `.gitmodules` names a submodule and never its version. There is no registry to poll, so
`LatestResolver.resolvable` refuses the ecosystem and `bus/ScmEventListener` is the only writer of
its `mt_latest` row. `kindOf` hardcodes INTERNAL: a submodule is a repository on this platform's own
git host, so a config key for it would be a knob whose only correct setting is the default.

**And the half arms only against a git host that reports a tree entry's sha** — qits-githost
33b0ccf teaches `serveTree` to answer a gitlink as `type: "commit"` with `sha` and `mode: "160000"`;
the DEPLOYED githost predates it and still answers `name` and a two-valued `type`, collapsing a
gitlink to `blob`.
`GitHostReader` reads an optional `mode`/`sha` when they are there; `ManifestScanner` pins nothing
when they are not, which is deliberate — a made-up version here is compared by the pending rule and
then applied by the step, into somebody else's repository. `ManifestScannerTest` pins both arms.

## Versions

**A GITLINK PIN HAS NO ORDER AT ALL, and that is the one to get right.** It is a commit sha; nothing
ranks two of those, and maven's order would happily call one "newer" by reading the leading hex as
digits. `pending/PendingChanges.gitlink` therefore asks a DIFFERENCE — is the pin the commit the
newest release was cut from — over `latest/GitlinkSha`, and refuses to answer when either half is
missing. What *is* ordered is the gitlink's `mt_latest.latest`, a calver release version, which rides
maven's order so `recordLatestIfNewer` stays forward-only.

**Three orders, because three ecosystems disagree.** Maven's is `ComparableVersion` — the class a
resolver ranks with, so this service and a build agree. npm's is real semver, which ranks
prereleases by rules maven does not share and disagrees outright on `1.0.0-1` against
`1.0.0-alpha`. An OCI tag is read as a version when it starts with digits and ignored when it does
not: `latest` is a moving reference and `jdk-25` is an upstream's naming.

**`mt_latest.latest` is the highest RELEASE**, falling back to a prerelease only when a dependency
has never published one. One column serves every pin of that dependency, so a release candidate
sitting in it would — by the pending rule — hide every stable upgrade from everyone with a released
pin. The fallback is what keeps SNAPSHOT-only artifacts visible, which is the case the pending
rule's "unless the pin is one too" is written for.

## The worker

**ONE THREAD for the whole service**, scans and bumps alike. A scan rewrites the inventory a bump
reads to compose its payload; two at once would let a bump send a payload computed from a repository
half-rewritten. It also means the git host and the registries see one caller.

**A second bump of one (repository, group) is refused earlier still**, by `MaintenanceStore.openBump`,
whose active-bump check is **inside** the opening transaction — a person and a scheduled scan
arriving together is the ordinary case, not a race worth losing. **The property being protected is a
REF, and the group is only a stand-in for one**: `openTargetedBump` therefore locks on
`(repository, branch)`, and both checks carry the mode so neither mode's lock can be taken by the
other's row.

**A task never throws out of `WorkQueue`.** A thrown exception would lose the sentence; every task
logs its own failure and ends.

**AND THE ROW IS CLOSED BY THE TASK, not by the queue.** `WorkQueue` logs and moves on, which is all
it can do — it does not know what a task was writing. The first live scan died inside a latest
lookup, the queue logged it, and the scan row stayed RUNNING for ever: a scan that is not running
and does not say so is worse than a failed one, because nothing can tell it from a slow one. Every
level now closes what it opened — the lookup answers with an error column, the repository loop marks
one row UNREACHABLE, and `ScanService.run` closes the scan FAILED with the sentence.

**A restart FAILS scans and RESUMES bumps** (`work/RestartRecovery`), because the work lives in
different places. A scan's is in the process — reads it made, a position in a loop nothing recorded
— so a successor cannot resume it and must not pretend to; the next schedule is minutes away. A
bump's is qits-ci's: the run outlived this service and its answer is still there to read, so failing
the row would throw away a branch that may already have been pushed. The ordinary sweep is the
recovery, and recovery only starts it early.

**`awaitIdle` is a barrier task, not a queue-length check.** The executor is single-threaded, so
work that reaches the front after the barrier does not exist yet.

## Bumping

**THERE ARE TWO MODES AND EVERY RULE BELOW IS ABOUT THE FIRST ONE.** A GROUP bump writes
`maintenance/<group>` — a branch this service names, creates, tracks in `mt_branch`, releases and
lets the release delete. A TARGETED bump (`POST /repositories/{name}/branches/bumps`,
`BumpService.requestTargeted`) writes a branch the CALLER names and owns: a workspace branch whose
release request wants its gitlink pins *in the fold* CI gates, rather than banked by the release
afterwards. `mt_bump.mode` is which, and `V12__bump_mode.sql` argues every consequence. The four that
matter: **no `mt_branch` row** (that row's unique `(repository, group_name)` index is an ownership
claim over a ref we do not own), **no release ask** (the caller already holds the request the pins
are for), **no head comparison and therefore no STALE arm** (the workspace commits to its own branch
while the run goes; calling that STALE would declare a live branch abandoned, silently and for
ever), and **the lock is keyed on the BRANCH** (two targeted bumps onto two branches of one
repository are both legitimate; two onto one branch are not). What it shares is everything else: the
payload, `BumpPayload`, the dedupe key, the 503 rule, the poller and the in-flight cap. Its group
column carries the stated sentinel `targeted` — a label for the payload and the commit subject,
never a key; the mode is the discriminator, which is why `newestBump`, `activeBump` and
`bumpsOwedARelease` all carry a mode term.

**A third mode, BASELINES, renders screenshot references** (`POST
/repositories/{name}/release-requests/{requestId}/screenshot-baselines`,
`BumpService.requestBaselines`, CLI `qits maintenance screenshot-baselines`). The request must be
open. It sends qits-ci a `ScreenshotBaselines` event, which qits-ci's packaged
`screenshot-baselines.yml` pipeline answers (on `node-browser-base`, the image the `app` QA step
compares in): from the request's fold, `release/<request>`, it runs `UPDATE_SNAPSHOT=all npm run
test:browser` and commits only `__screenshots__/` files and `renderer.txt` onto
`maintenance/baselines/<request>`. The branch is ours, so the start
head is stored in `result_sha` and compared at the end: unmoved is NOTHING_TO_DO ("unchanged"),
moved is joined to the request (`ReleaseRequestClient.join`) and SUCCEEDED, red is FAILED. No
`mt_branch` row and no release ask: the request decides when the branch ships. Missing references
are written like changed ones, so a repository's first baselines come from here as well. The
commit subject's scope is `mt_bump.work_item` (`V16`), or, when the caller named none, the newest
id on the fold's own commit subjects.

**Two callers on the group path, and no scan is one of them.** The button is `POST
/repositories/{name}/groups/{group}/bumps`; the clock is `schedule/BumpSchedule` at 02:00, INTERNAL
group only. A SCHEDULED scan used to ask for the bumps it found, gated by `bump.auto` — that key and
that coupling are both gone. A scan is a READ whose schedule is set by how fast facts go stale; a
bump is a WRITE into somebody else's repository whose schedule is set by when a branch is welcome.
Welded together, the 01:00 external scan decided when the internal half got a branch.

**The changes are frozen at REQUEST time**, not recomputed at dispatch. A payload recomputed later
would not be the one the operator saw, and a retry after a 503 would send a different list under the
same dedupe key. **That freeze is also the coalescing**: N internal releases between two nightly runs
are ONE branch push, one CI build, one release request and one release.

**The event id IS the bump row id**, and qits-ci dedupes on (event id, repository, config path). A
dispatch whose answer was lost records no second run when it is retried. Never generate a fresh one.

**Three answers, three meanings.** 503 is RETRY and the bump stays REQUESTED with its changes; a 200
with no run id is FAILED, because qits-ci records a run only if the payload's repository was
readable in that evaluation; anything else non-2xx is FAILED. Treating an empty `runIds` as success
would report a branch that was never written.

**Only the branch HEAD is compared, never a commit count — on a GROUP bump.** One bump is up to two
commits — the maven step and the node/docker step each clone, commit and push — so a service
expecting one would report every mixed group as broken. The head is read before the trigger and again
when the run ends; unmoved after a green run is NOTHING_TO_DO, and moved after a red one is STALE.
**What licenses all of that is that nothing else commits to `maintenance/<group>`**, which is exactly
what a TARGETED bump cannot assume: its verdict is its CI run's, its `result_sha` is the head read
once afterwards, and an unreadable head costs the sha and not the SUCCEEDED.

**`BumpPayload.problems` refuses on this side what the step refuses on that one.** The step holds
`to`, `group`, the refs and the manifest path to the same rules; failing here puts the reason on the
bump row instead of in a step log somebody has to go and read.

**A SUCCEEDED group bump asks for the release; the other three endings do not, and a TARGETED bump
never does.** `ReleaseRequestClient` posts to
qits-projects' `POST /projects/api/repositories/<repoId>/release-requests` with the branch and the
commit-subject summary. Nothing merges and nothing is released at that call: a release REQUEST is
OPENED, the quality gates settle the fold it makes, and Auto Release tags it. **This service's job
ends there** — nothing here polls the request or waits for a version. NOTHING_TO_DO pushed nothing; STALE
is somebody's hand-written commit and releasing it on their behalf is the one thing this must never
do.

**A qits-projects that will not answer never flips the status.** The bump succeeded — green run,
moved branch, both facts about this service's own work. `mt_bump.release_request_id` carries the
answer and **NULL is the one value that means work is owed**; `converged` and `refused` are sentinels
that stop the retrying, because a permanently refused ask re-sent every fifteen seconds for the life
of a branch is the failure mode a bare boolean would have. **The retry is bounded by the BRANCH, not
a counter**: it ends when the request exists or when the branch is gone — which is also what a landed
release leaves behind, since a request's named sources are deleted when it lands.

**The `repoId` on that call is `mt_repository.catalog_id`**, the `id` qits-projects' own listing
answers, which `CatalogReader` has copied onto the row since V5. That route resolves its path
parameter against qits-projects' repository table and nothing else, so neither the name nor the
project can address it — the pair the retired qits-workspaces door took. A row with no catalog id is
a refusal, not a retry: the next scan fills the column and the next bump asks with it.

**The ask needs no credential of its own.** It is a qits-projects route and the route admits
`qits:system` beside `qits:admin`, so the bearer every catalog read already mints through the one
`qits` client opens it. There was a client for the release door once — audience `qits-workspaces` —
and it went with the door. A 401/403 is still classified RETRYABLE rather than refused, so a grant
that has not landed heals rather than needing the bump run again.

## Persistence

`MaintenanceStore` is the only writer. **Every METHOD is a `DbRetry.inNewTx` — reads included — and
every write ends in a flush**: `inNewTx` owns the transaction boundary, which is the only way a
retry can tell "the body threw, so it certainly never committed" from "the transaction manager
reported it" — Narayana spells a lost commit and a real rollback with the same exception.

**The reads are in for a second reason, and it is why the rule is UNIFORM rather than audited.** A
durable frame is handled inside the eventstream datasource's claim transaction. Neither datasource
is XA and Narayana admits one last resource, so a BARE read on the `maintenance` datasource from a
bus path enlists a second one into that claim — "Failed to enlist / Exception in association of
connection to existing transaction" — every frame is then classified retryable and the consumer
wedges for ever behind one of them. Measured twice: **2026-09-02** through `repositoryName`, and
**2026-09-03** through `groups`, reached from `ScmEventListener.onMaintenanceBranchReleased`. The
first fix spot-audited the four methods a listener was then known to touch and the second wedge came
through a fifth, so spot-auditing is not the rule any more. The only three methods here that own no
transaction are `writeJson`, `readStrings` and `readObjects`, which are static column codecs and
touch no datasource. A caller that is not a listener pays one transaction it did not need, which is
the cheap side of the trade; the entities are flat — no association, nothing LAZY — so a row read
inside its own transaction is fully materialized before it detaches, and nothing outside the store
mutates one.

**`bus/ClaimTransactionTest` is what holds that.** It opens a transaction with the eventstream
datasource enlisted, walks every read the store offers and then writes, and drives `onFrame` inside
the same sandwich. The other bus tests drive `onFrame` against a store whose tables are maps, which
is exactly why they stayed green through both outages.

**Every method is `@ActivateRequestContext`**, because the caller is usually the worker thread and a
Hibernate session is bound to that context. A route's call already has one, so the annotation covers
both callers.

**Set every field BEFORE `persist`.** `replaceInventory` runs a delete query right after, and
Hibernate flushes before a query — a row persisted with its not-null columns still unset fails the
flush rather than the insert, naming a column nobody was writing at the time. Measured, not
theoretical.

**One repository's inventory is replaced in ONE transaction.** A scan that committed the delete and
failed the insert would leave a repository looking as though it pins nothing — which is also what
"nothing pending" looks like.

Schema changes go in `maintenance/src/main/resources/db/maintenance/migration/`, hand-written, its
own lineage on its own datasource. Keep appending, never edit an applied migration.

**`ScanTrigger` and the five status enums are `@Enumerated`-style string columns under no check
constraint, so a new value is one enum constant and no migration.** `ScanTrigger.EVENT` arrived that
way. The invariant lives where the writes are — `ScanService.request` is the only writer of
`mt_scan.trigger` and it takes the enum.

## The dependency graph

**An SBOM says what a released artifact CONTAINS; `mt_pin` says what a bump EDITS. They relate by
`(ecosystem, name)` and they never merge.** That sentence is written into `V3__sbom_graph.sql`,
`MtArtifact`, `ArtifactGraph` and `RepositoryDetailDto` because it is the one thing a later change
will be tempted to undo. Neither can answer the other's question: an SBOM holds resolved coordinates
and cannot name `property:qits.eventstream.version`, and a manifest holds what its author wrote down
and cannot see what the resolver pulled in behind it. Merging them would produce either an inventory
nothing can bump or a page that cannot answer an advisory.

**THE ROW IS THE OUTBOX AND THE FETCH IS NEVER INSIDE THE CLAIM.** `SoftwareReleaseListener` writes
an `mt_artifact` row PENDING and submits to `WorkQueue`; `SbomIngestService` does the call. A
listener that fetched inline would hold a durable claim open across another service's HTTP call, and
a qits-artifacts that was slow would make one release an event redelivered for ever with this
consumer's watermark stuck behind it.

**404 is MISSING, it is TERMINAL, and that is not laziness.** The route is newer than most of what
the platform has released and a released version is immutable, so a retry asks about the same bytes.
The next release brings its own row; `POST /artifacts/ingest` is the manual move. `SbomSweepSchedule`
and `RestartRecovery` re-queue **PENDING only**.

**`direct` is the ROOT's own `dependsOn` list and nothing else.** It is the only reason to read the
document at all. A parser that marked every listed component direct would leave the whole
transitives section empty with nothing saying why.

**A null `ecosystem` on a component is a purl type this service does not map**, and it is a state
rather than a gap: stored, shown, never matched. `Purl.parse` answers empty for such a type rather
than guessing a mapping, because a guessed name in a join key means something else.

**The two reverse reads go through the store in TWO steps rather than one join**, because
`mt_artifact_component.artifact_id` is a plain uuid like every other relation in this schema and a
join would have to be native SQL. The default view is **newest per dependent artifact NAME**; `all`
is the archaeology. Both are computed on every read, for the reason pending is.

**`mt_artifact_component` and `mt_artifact_edge` carry the only FOREIGN KEYS in this schema.** Every
other relation here is a string another context owns. These two are allowed because both ends are
this context's own tables in this context's own database, and a component has no meaning at all
apart from the artifact it was read out of. **`mt_release_pin.release_id` is deliberately NOT a
third one** — the same part-of shape, but those rows are rewritten by a delete keyed on that column
inside the transaction that replaces the release row, so the constraint would buy nothing the writer
does not already guarantee. **`mt_sbom_ticket_version.ticket_row` (V14) is the third**, for the same
part-of reason the graph's two are: a reported version means nothing apart from the ticket row it
was reported on, and both ends are this context's own.

## The daily SBOM check

**`sbomcheck/SbomCheckService` (V14), on `schedule/SbomCheckSchedule` at 02:15, and SHIPPED ON**
(`qits.maintenance.sbom.check.file-tickets=true`): a run computes which released artifact still in
qits-artifacts has no usable SBOM, stores the report in `mt_sbom_check_run`, files one MAINTENANCE
ticket per `(project, ecosystem, name)`, and drops it again once every listed version is INGESTED or
collected — the semantics of qits-projects' `TicketUnattendedGateTickets`, which is the precedent.
Setting the key back to `false` returns to report-only: the report is still computed and stored, but
NOTHING is called in qits-projects.

**No cut-off; the GC is the retirement.** Every row counts however old, so an old version pinned
somewhere is a finding until the pin moves and the GC collects it — at which point it stops counting
and its ticket closes itself.

**The presence probe must never RECORD AN ACCESS**, which is why it reads listings
(`maven-metadata.xml`, the packument, `tags/list`, the daemon browse listing) and never a
version-addressed GET or HEAD: qits-artifacts moves `accessed_at` on every one of those, and a daily
probe that touched what it reports would keep those versions alive for ever. A 404 is "collected";
anything else that is not a listing FAILS THE RUN rather than guessing either way, and every probe is
made before anything is written.

**A null `project_id` is RESOLVED, not guessed.** Rows written before the listener stored
`SoftwareRelease.projectId` (V14) carry none — and after the GC those old pinned versions are exactly
what the check finds — so a counted row's repository is matched against qits-projects' catalog
listing (`GET /projects/api/repositories`, `CatalogReader`, admits `qits:system`), once per run and
report-only too: an exact catalog id, or an exact name exactly one project carries. The project is
written back onto the row, so it is resolved once. Ambiguous, unknown, or a catalog that would not
answer stays a warning, never a ticket.

**The ticket doors admit `qits:admin`/`qits:agent` in qits-projects today, and this service presents
`qits:system`** — so filing answers 403 until that is settled. Harmless report-only; it is the first
thing to fix before the key is flipped.

## The release ledger

**There is a THIRD fact beside those two, and it is what the adoption view runs on.** An SBOM says
what a release CONTAINS; `mt_pin` says what a bump EDITS; `mt_release` / `mt_release_pin` (V9) say
what a release DECLARED — the INTERNAL pins in the tree at `refs/tags/<version>`. They join the
other two on `(ecosystem, name)` and merge with neither.

**The measurement is the whole argument.** 2026-09-08: the journey of `qits-ui-components-jslib
2026.906.164412` named 15 frontends and 15 services and called every one PENDING while every one had
adopted it. `dependents(npm, @qits/ui-components)` was EMPTY — a frontend publishes no registry
artifact, its release is a tag — and `qits-ci-service`'s newest document held 239 maven components
and 0 npm ones, because a compiled Angular dist carries no npm metadata. Neither hop had a document
that could ever have named the coordinate. One evidence kind was structurally blind to half the
estate.

**A PIN AT A TAG IS NOT A PIN AT `main`, and that distinction is the licence for this whole
feature.** The objection `AdoptionEvaluator` was built on is not "a pin is weak evidence": it is
that a pin read at `main` is a fact about a WORKING TREE — unpolled, revertible, moving. A tag is
immutable and is tied to the consumer's own released version, which is the value the answer reports.
Anything that starts reading pins at a branch for this has thrown the argument away.

**GITLINK is the half that pays, and its version is a COMMIT.** `kindOf` hardcodes GITLINK→INTERNAL,
so a submodule survives the kind filter with no special case; its recorded `version` is the embedded
sha, and a reader RESOLVES it through the submodule repository's own `mt_release` rows (`GitlinkSha.same`,
abbreviation-tolerant) before comparing. A sha the ledger cannot place is no match — honest, not
defensive. No SBOM component is ever a gitlink, so `AdoptionEvaluator` skips the `dependents` read
for that coordinate outright.

**`mt_release.repository` is the CATALOG NAME under both writers and must stay that way.** The
listener writes `SCMRelease.repositoryName` and the backfill writes a gitlink `mt_latest.name`, which
is a repository name by construction — which is why the evaluator compares it directly, with none of
the id↔name translation `mt_artifact.repository` needs (V5). Writing another context's spelling into
it would be silent.

**The tree is read through `ManifestScanner.pinsAt`, not through a copy of the discovery.** Same head
resolution, same four parsers, same per-line dedupe as `read(CatalogEntry)`; what a scan wraps around
it — a status, a grouping, a sentence for the row — has no meaning about a tag nobody can push to. A
second copy would be a second answer to "what does this repository declare".

**The write is IDEMPOTENT and both writers need it to be.** `recordRelease` finds the row by
`(repository, version)` and rewrites it and its pins in one transaction, so a durable redelivery and
a boot backfill converge rather than accumulate.

**`work/ReleaseLedgerBackfill` is a fourth startup class and deliberately not a fourth step in
`RestartRecovery`.** Nothing was in flight: it fills in releases that predate the table, which is
SBOM-shaped work (one idempotent read of an immutable tag) rather than recovery of an abandoned row.
Its only possible input is `mt_latest`'s GITLINK rows — the one place "this repository released this
version, at this commit" is written down — so it is one release per repository and that is all this
question needs.

**`PeerTarget.ARTIFACTS_SBOM` is a ninth address and the fourth on qits-artifacts, and its key
carries no path.** The three registry keys name a MOUNT whose prefix is a repository row a
deployment may move; `/artifacts/sboms/…` is qits-artifacts' own API, so its prefix is code. The
name goes into the path LITERALLY — slashes kept — because `/-/` is what separates it from the
version, which is why that separator exists.

**`SbomClient` goes through `PeerClient` rather than opening a second HttpClient.** That gets the
shared instance field (a static one is the native-image hazard), the shipped `call-timeout`, the
forward-auth pair, the optional bearer, the response bound — and `FakePeers`, which is how the whole
suite replaces the network.

**`SoftwareRelease.repository` IS THE ROW ID, and `mt_repository.catalog_id` (V5) is the
translation.** Measured live on 2026-09-02: qits-ci names the repository by qits-projects' row uuid,
not by the catalog name — so `mt_artifact.repository` held `daf73ae4-…` while `RepositoryDetailDto`'s
transitives, `GET /repositories/{name}/dependents` and `DependentDto.repository` (the SPA's link
target) all join it on a NAME, and every one of them was dark without saying so. The catalog answers
`id` beside `name`, `CatalogReader` keeps it and every scan writes it, so the same repository is
known under both spellings. **Two arms and both are needed**: `SoftwareReleaseListener` resolves the
frame before the row is written (one query, inside the failure policy it already has), and
`ArtifactGraph.RepositoryNames` translates both ways at read time for the immutable rows written
before that — out, so a listing hands the client a name, and in, so a name-keyed lookup finds the
uuid rows. **An unknown spelling passes through untouched in every arm**: a release of a repository
this inventory has never scanned still happened, and guessing a name here would put one on a page
that no event ever carried.

**No `mt_artifact` row is ever a `gitlink`, and a `daemon` row is an artifact like any other.** A
gitlink is a submodule rather than a published package and no release announces one as its
`packageType`; GITLINK reaches `mt_latest` through the OTHER listener, off the same `SCMRelease` a
branch's state is read from. A `daemon` release writes the artifact row (never `mt_latest`) under
the literal word — it is not an `Ecosystem` — PENDING, and its document is fetched and ingested like
a jar's: **the SBOM route is keyed by the released artifact's TYPE, so `SbomClient` takes the stored
wire string, not an `Ecosystem`.** That is what qits-703 corrected — the row used to be written
terminal FAILED (`DAEMON_SBOM_UNREAD`) because the fetch took an `Ecosystem`; V13 re-queued those.
`SoftwareReleaseListener.ECOSYSTEMS` maps the three ecosystems and filters `docs` and unknown types.

## The event bus

`service/…/bus/` is the whole of the wiring, and the machinery is the published `qits-eventstream`
jar. Its rules live in that library's own repository and are not restated here. **This service
subscribes and publishes nothing.**

- **Two listeners, and their `consumerId()`s are STORAGE.** `maintenance-internal-latest`
  (`SoftwareRelease`) and `maintenance-branch-tracking` (`SCMRelease`, `SCMDeleteBranch`,
  `SCMPublishCommit`). Change one and you mint a brand-new consumer that initializes at the head of
  the log, silently skipping everything in between; the old watermark is orphaned. Reuse one and a
  listener inherits another's watermark, believing it has handled events it was never offered. Both
  are pinned as literals in `bus/ForeignEventContractTest` for that reason.
- **THERE IS A THIRD ID AND IT IS ABANDONED: `maintenance-release-trains`.** It was a second durable
  consumer of `SoftwareRelease`, opening the station of the release train that release belonged to.
  The trains were retired (`V8__retire_release_trains.sql`) and the listener went with them — but its
  `consumed_event` rows and its `consumer_watermark` are in the eventstream database and are
  deliberately **left alone**, which is the abandoned-consumer rule: a consumer id is storage, and
  deleting a watermark and deleting a consumer are not the same operation. **Never reuse the
  string.** A future listener adopting it would inherit a watermark saying it had already handled
  every release up to the day the trains were removed — it would start deaf, with nothing in any log.
  It is pinned as a literal in `bus/ForeignEventContractTest` beside the two that are live, with the
  assertion that no live listener claims it.
- **`mt_latest` has two writers now and they are deliberately different methods.**
  `MaintenanceStore.recordLatest` is the POLL's and replaces the column whatever it says;
  `recordLatestIfNewer` is the BUS's and only ever moves it forward. A poll asks a registry what the
  newest version is — a downgrade is a real answer, because a package can be unpublished. An
  announcement is evidence that THIS version exists and never that a higher one does not, so letting
  the bus write through `recordLatest` would let a catch-up frame from yesterday rewind a column this
  morning's scan filled, and every pin of that dependency would read as up to date until the next
  scan. The guard is `VersionOrder`'s, the same comparison the pending rule makes. A frame that is not
  newer writes **nothing at all**, `checked_at` included: stamping it would say a lookup happened.
- **`SCMRelease` does TWO things here, and the first is the gitlink.** Every release records the latest of a
  GITLINK — the version, and the commit `refs/tags/<version>` resolves to, in `source_url` as
  `sha:<hex>`. It runs on every release whatever the branch was, and it is idempotent, so the
  redelivery an unreachable git host causes replays it harmlessly. The failure split is the seam's: a
  tag the host does not HOLD is poison (WARN, settle), a host that cannot be ASKED is retryable and
  thrown, because no scan ever refreshes this row.
- **The SECOND is the release LEDGER, and it runs whether or not the latest column moved.**
  `adoption/ReleaseLedger` reads the tree at the same tag and records its INTERNAL pins. The two
  writes answer two questions and only one of them is forward-only: `mt_latest` is "where can a pin
  move to", which a catch-up frame must not rewind, while a ledger row is "what did THIS release
  declare" — a fact about a version rather than about the newest one. Same failure split, and the
  tag sha is handed over already resolved rather than resolved twice. See "The release ledger".
- **The maintenance-branch arm of `SCMRelease` is GONE, and `BranchState.RELEASED` is a word nothing
  writes.** It was there because qits-workspaces' door published the event naming the branch it had
  just tagged over, which was the one fact nothing else could tell this service. A release is now a
  tag on a release request's fold, `release/<id>`, published by qits-projects — so `branch` on that
  event names the fold and never a `maintenance/` one, and the arm could not fire. A maintenance
  branch's whole ending is the `SCMDeleteBranch` that follows the release (a request's named sources
  are deleted when it lands), which is the same signal a person deleting it by hand sends, and `NONE`
  is the right answer to both. That delete is also the only thing that ever clears a `STALE` row.
  `RELEASED` stays in the enum because old rows hold it.
- **A main-branch push is a SCAN and never a bump.** `ScanTrigger.EVENT`, one repository, through
  `ScanService.request` — the same path `POST /scans {repository}` takes, so it is a row a client can
  follow, it is behind the one worker thread, and it closes itself on failure. What a push changes is
  a manifest; whether the pending set becomes a branch is still the clock's standing instruction or a
  person's press, and a bump per push would put a branch on every repository somebody touched today.
  The debounce is `MaintenanceStore.scanPending`, against the store rather than a field, so it
  survives a restart and sees a scan a person queued a second earlier.
- **`selects()` is left at its default in both, and the filtering is inside `onFrame`.** The seam
  wants a predicate that is PURE and cheap; every question worth asking here is a database read (is
  this repository in the catalog, is that its main branch, is that group one of its own), it would be
  asked once and then asked again in the handler, and one that threw on a blip would leave the event
  owed for ever. The price is a claim row per frame of those four signatures, and it is bounded: the
  library's sweeper prunes claims the watermark has passed by more than `prune-horizon`.
- **Every handler failure is a decision between two.** A throw rolls the claim back and the event
  stays owed — offered again for ever, with this listener's watermark stuck behind it, because the
  seam has no dead letter. So: **poison** (a payload that will not parse, one naming no version, one
  naming a repository or group this inventory does not hold) is a WARN or a DEBUG and a return;
  **retryable** (the store will not answer) is left to throw. Both listener tests assert both arms.
- **The consumed payloads are TRANSCRIPTIONS and `bus/ForeignEventContractTest` is where they are
  kept.** qits-projects keeps its `SCMRelease` in its own `bus/` package, and taking qits-ci-events or
  qits-githost-events would be a compile-time dependency on another context for four field lists. So
  each record copies a component list, the canonical bytes are produced by the library's own
  serializer rather than by a JSON fixture, and the tests drive those bytes through the listeners'
  records. **A rename over there is a change to that file in the same campaign**; landing it there and
  not here leaves this suite green and a listener deaf.
- **`bus/EventWireReflection` is the second member of `api/ApiWireReflection`'s family** and is there
  for a related but distinct reason: `CanonicalJson` builds its OWN `ObjectMapper` — deliberately and
  permanently, because the canonical form is a wire contract another service compares byte for byte —
  so everything it binds is invisible to the build step that scans for what needs reflecting on. On a
  JVM these reflect whether anyone registered them or not, which is what lets the omission survive a
  green suite; the failure is in the binary, on the first frame. `EventPage` and the
  `CanonicalJson$QitsEventMixin` are named as STRINGS because both are non-public in the library, and
  `EventWireReflectionTest` resolves both so a rename cannot rot silently. Leaving `EventPage` out
  costs **catch-up alone** — the half that only matters after a cutover. Do not "fix" a recurrence by
  injecting the CDI mapper.
- **The jar brings a MANDATORY deployment resource, and the resource NAME is load-bearing.**
  `.config/qits/deployments.yml` declares
  `postgresql:eventstream:qits_platform_maintenance_eventstream`; the jar reads
  `QITS_RESOURCE_EVENTSTREAM_*`, whose names follow it. `qits.eventstream.enabled=false` (`%dev`,
  `%test`) stops publishing, sweeping and dialling — never the datasource, which Quarkus opens and
  Flyway migrates at boot regardless. That is why the suite hands out a second database
  (`testdb/EmbeddedPgConfigSource`) and why `PackagedSurfaceIT` supplies a second triple **and** turns
  the bus off by hand: a launched artifact runs in NORMAL mode, where `%test` does not apply.
- **Nothing about the bus is configured in `application.properties` except the darkness.**
  `qits.events.url`, the outbox datasource and its resilience baseline, the timeouts, the retry budget
  and the catch-up schedule are ordinal-100 defaults in the jar. A copy here would be a second place
  to change. (That also means `DatasourceBaselineTest` passes for the second datasource without a line
  in this repository — the jar ships the three.)

## Identity: two tracks, one set of roles

A request with no `Authorization` header is USER traffic — qits-gateway performed the login and
asserted `X-Qits-User` / `X-Qits-Roles`. A request WITH a bearer is MACHINE traffic, validated by
quarkus-oidc against qits-platform-idp.

**Both land as roles, which is why every route is `@RolesAllowed({"qits:admin", "qits:system"})`.**
An operator presses Bump in a browser; a machine may post the same request. There is no anonymous
route here and there must never be one — the write surface pushes branches into every repository on
the platform.

**Outbound, this service is a MACHINE and nothing else**: every call carries `X-Qits-Roles:
qits:system`, and the idp client carries `qits:system` — the orchestrator's machine role.
`qits:admin` is the human role and this service never holds it, not even to read a CI run:
qits-ci a3ecce2 made its read-only run and repository routes take `qits:system`. What the client
needs beyond the orchestrator's is one claim, `project = *`, because qits-ci's trigger demands every
project. See README's "Rollout needs".

`quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}` — validation follows the rollout
gate rather than standing on its own, so with the gate off there is no OIDC tenant, nothing fetches
a JWKS, and a clone-alone build needs no issuer. There is no third state.

**ONE AUDIENCE AND IT IS A LITERAL**: `quarkus.oidc.token.audience=qits-platform`. qits-platform-idp
stamps that value onto every token it mints whatever the client asked for, so the tenant admits
every caller there is and the ROLES above are the whole of what separates them. Nothing here reads
`qits.auth.machine.audience` — the shipped properties do not set it, and the one profile that turns
the gate on states it itself, because qits-auth-core's `MachineAuth` refuses to start with the gate
on and no audience configured.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and **the tests
  inherit it** — Quarkus merges the test resources over it rather than replacing it. Never
  re-declare an app-level setting in test resources.
- **No dev services and no containers, ever.** `EmbeddedPg` starts zonky's postgres and
  `EmbeddedPgConfigSource` hands its coordinates to every `@QuarkusTest` at an ordinal above
  `application.properties`, because the port is chosen at run time. Both are **copied** per module
  rather than shared: a test-jar dependency between two modules that have none is the higher price.
  **Every (module, datasource) pair names its own database** — `maintenance_domain`,
  `maintenance_svc`, `maintenance_svc_eventstream`, and the two ITs' pairs — so no two suites can
  mean one schema. `service` hands out six values rather than three because the bus jar opens a
  second datasource whether it is dark or not.
- **`FakePeers` is an `@Alternative` over `PeerClient.get` / `.post`** — replacing those two is
  replacing the network, at no port and no dependency, and the urls in the assertions stay the real
  ones because the inherited `url()` still resolves them from the shipped target configuration. It
  keys on the TARGET as well as the path: three of the eight targets are one service behind three
  prefixes, and a path alone would let a maven lookup answer for the mirror's.
- **An unscripted path answers 404**, because that is what the git host says about a file a
  repository does not carry — the ordinary case in a scan, and it keeps a fixture to what it means
  to say.
- **Every poll is a fresh HTTP request, and that is load-bearing.** A `@QuarkusTest` holds ONE
  request context for the whole method, so one Hibernate session would answer every read from its
  first-level cache — the row would look unchanged for ever while the worker closed it in another
  session.
- **The same rock reached `MaintenanceStoreTest`, and the shape that avoids it is "every write, then
  one read".** It used to be that a store read outside a transaction was answered by the
  request-bound session while every store WRITE ran in `DbRetry.inNewTx` on a session bound to its
  own transaction — so a read between two writes was answered from the first session's cache and an
  assertion about the last write failed against a store that was perfectly correct. Measured
  2026-09-01 writing the forward-only latest tests. **The uniform wrap of 2026-09-03 took that away**
  — a read now runs in its own transaction like a write does — but keep the shape: it is the one
  that says what a test means, and it costs nothing.
- **`InventoryReset` empties the store between methods, after `WorkQueue.awaitIdle`.** The graph
  goes first there — `mt_artifact_component` and `mt_artifact_edge` carry a foreign key to
  `mt_artifact`, and the SBOM check's `mt_sbom_ticket_version` one to `mt_sbom_ticket`, so those go
  before what they point at. The ledger pair goes with it, pins before
  releases: nothing enforces that order (see "The release ledger"), but the two read as unrelated
  tables written the other way round. Flyway's
  `clean-at-start` runs per Quarkus start, not per test, and an active bump row holds its branch's
  lock: without the reset the second test of a class is answered 409 by the first test's leftovers.
  The drain first, or the delete lands between a running task's read and its write.
- **The scheduler is off in the suite** (`quarkus.scheduler.enabled=false`). The crons would fire
  once a day for nobody's reason; the bump sweep is an INTERVAL and would fire, racing every test
  that drives a bump by hand. **The story catalogue is the one exception and it is deliberate**: a
  bump is closed by the sweep and by nothing else, so `StoryProfile` turns the scheduler back on and
  removes every other timer at its own key. See "The userflows".
- A `@QuarkusTest` runs under the `test` profile, where qits-auth-core ships a dev user carrying
  `qits:admin` and `qits:system` — so the shipped `@RolesAllowed` pair is exercised rather than
  bypassed, with no `@TestSecurity` fabricating an identity no deployment produces.
- **Every `@QuarkusTest` here is a fake identity away from a deployment; the six ITs are not.** An IT
  runs against the ARTIFACT, so the roles arrive the way the edge sends them and the datasource
  arrives as the shipped `QITS_RESOURCE_DB_*` indirection rather than as the datasource keys.
  `PackagedSurfaceIT` is the one that is about the artifact itself — the route prefixes, Flyway's
  migration surviving as a classpath resource, the client at the root, and **peers that are real
  calls to a dead loopback port**, which is the honest end-to-end proof that a failure reaches a
  readable row with none of the suite's fakes involved. The other five are the story catalogue
  below, and they point those same peers at recording stand-ins instead. ITs are skipped by default;
  `-DskipITs=false` runs against the fast-jar and `-Dnative` against the binary.
- **`TokenValidationBootstrapIT` is the second, and the only place the OIDC tenant is ever ON.** The
  shipped tenant is gated on `qits.auth.machine.required`, which every other suite here leaves
  false, so the whole `quarkus.oidc.*` block runs nowhere else. Its far side is qits-service-mock's
  `MockIdp`, which serves a real JWKS for a generated keypair, mints tokens against it and records
  what it answered — so "the service fetched the keys at startup" is an assertion, not an inference.
  It is also the **first class of the story catalogue** below.

## The userflows

Nine `@UserStory` methods across five classes, emitting `service/target/userstories/` and published
as `@userflows/qits-platform-maintenance` by the second step of the release-request phase of
`.config/qits/release.yml` — once per release-request fold, not per commit. `skipITs` stays true and
the pipeline names the classes:
`-DskipITs=false "-Dit.test=TokenValidationBootstrapIT,ScanCycleIT,InventoryIT,BumpIT,MaintenanceRefusalIT"`.

| class | category | what it is about |
|---|---|---|
| `api/TokenValidationBootstrapIT` | authentication | the JWKS fetched at boot, and the three tokens that open nothing |
| `stories/scan/ScanCycleIT` | the scan | every manifest at one commit, every registry asked by KIND — and a git host that goes dark mid-story |
| `stories/inventory/InventoryIT` | the inventory | what an operator reads, and the five arrows that are not there |
| `stories/bump/BumpIT` | the bump | a payload for somebody else's pipeline, and the two endings a green run has |
| `stories/refusals/MaintenanceRefusalIT` | refusals | 401, 403, and the requests that start no work |

**ONE PROFILE, ONE LAUNCH, ONE DATABASE.** `stories/support/StoryProfile` extends
`PackagedSurfaceIT.PackagedUnderTarget` and every story class names it, the bootstrap IT included. A
second profile would be a second boot with a second inventory, and every story after the first reads
what an earlier story's scan wrote. The database is `maintenance_userflows_it`, its own, because
`PackagedSurfaceIT` is a different launch of the same artifact and a shared schema would have each
reading the other's rows.

**The network diagram is observed, never narrated.** `Interactions.happened()` was removed from the
framework in 2026.829 and there is nothing to replace it with. `stories/support/StoryNetwork.install()`
is the whole wiring in one call: the framework's **shipped** `NetworkTaps.restAssured(SERVICE)` for
what a story sends in (the per-repo `StoryNetworkFilter` copy this repo used to carry is deleted —
`NetworkTaps` is where it lives now), and five cumulative `NetworkCapture.source` registrations for
what left. A story sets `NetworkCapture.actor(...)` **before** each call, because the tap sees a
request and never a narrative role, and then only asserts and notes.

**The five peers are real sockets that record.** `stories/support/StoryPeers` is a `com.sun`
HttpServer per service — qits-projects, qits-githost, qits-ci, qits-platform-artifacts,
qits-platform-mirror — armed by `StoryCatalog` with a two-repository platform, and the launched
process is handed their addresses as its shipped `qits.maintenance.*-url` keys. It is **not**
`FakePeers` and it is not a `MockService`, for three reasons that are each independently fatal: a
CDI alternative does not exist in a launched fast-jar; a recording of the CALL cannot label an edge
with the status that came BACK; and neither can stop answering mid-story, which is what
`reachable(false)` does — the connection goes away with no status, recorded as the word `dropped`.
A path is armed by its DECODED form and recorded in its RAW one, because `%2f` is one segment to a
registry and two to `URI.getPath()`.

**The store is the one declared edge, everywhere.** Every JDBC read happens inside the launched
process where no tap of ours stands, so each story `network.declare`s it; declared edges carry
`"declared": true` and render muted and dashed, so a claim never reads like evidence. **An absence
is never an edge** — it is `assertNoEdgesTo(<peer>)`, which is the assertion that pays: the
inventory story's whole subject is that the peers were up and answering and none of them was asked.
Every story also pins `assertEdgeCount` and `assertOnlyEdgesFrom`, so a call appearing later shows
rather than passing quietly.

**Ordering is load-bearing rather than tidy.** A cumulative source is attributed by a cursor, so
pre-story traffic — the startup JWKS fetch — lands in whichever story drains first, and a "no peer
was asked" claim is only checkable once the story that DID ask has drained. Every story method
carries `@UserflowRunsAfter` and `UserflowClassOrderer` (junit's secondary orderer, registered in
`service/src/test/resources/application.properties`) turns that into the chain
`TokenValidationBootstrapIT → ScanCycleIT → InventoryIT → BumpIT → MaintenanceRefusalIT`. Two
multi-story classes also pin `@TestMethodOrder`.

**Namespacing, not resetting.** `InventoryReset` has no equivalent in a launched process, so the
catalogue keeps stories apart by giving them different repositories: `qits-ci` carries the rich
reactor and every reading story, `qits-eventstream` exists so the second bump story has a branch of
its own — a bump holds its (repository, group) lock until it ends.

**The clock: one timer alive, and a story drives it.** A bump is closed by `BumpPollSchedule`'s
sweep and by nothing else, so `StoryProfile` turns the scheduler back **on** and then removes every
other timer at its own shipped key — both scan crons are `off` (the scheduler's own value for "do
not register this trigger"), `scan.enabled=false`, and `bump.poll-interval=1s`. The sweep is a no-op
whenever no bump is in flight, so the only stories it can reach are the two holding one open.
**Two paths are therefore not covered by a story**: the nightly internal bump (`BumpSchedule`, which
needs the cron this profile removes), and `RestartRecovery` resuming a bump across a restart, which
needs a second boot.
Both keep their coverage in `MaintenanceApiTest`, which drives `bumps.sweep()` by hand.

**What the catalogue does not show, because this service does not do it.** There is no story of a
commit being made, a file being written or a ref being pushed: this service decides, a CI step
applies and the platform releases, so the two furthest arrows out of it are a `MaintenanceBump`
trigger and a release ASK — neither of which touches a tree. And nothing here
transitively resolves, orders an external base image or runs `ng update`; each is a decision recorded
under "Deliberately not here yet", not a gap in the stories.

**There is still no `event` edge in any story, and that is now a gap rather than an absence.** The
two bus listeners are real (see "The event bus" above), and `StoryProfile` inherits the parent
profile's `qits.eventstream.enabled=false` — so the launched process dials nothing and no story
walks an arriving frame. Covering one would mean a qits-events stand-in on the far side of a
websocket, which is a stand-in of a different kind from the six `StoryPeers` and a decision worth
making deliberately. The listeners' own coverage is `bus/*Test`, driving `onFrame` directly.

## The client

`service/src/main/webui` is the `qits-maintenance-platform-frontend` submodule (`ignore = all`,
`update = merge`, `branch = main` — the sibling shape). Quinoa 2.8.2 is pinned by hand in the root
pom, because Quinoa is in no BOM and its version does not track the platform's.

- **The segment is spelled twice**, `quarkus.quinoa.ui-root-path` here and `baseHref` in the
  submodule's `angular.json`. A mismatch serves a page whose every asset 404s and nothing on this
  side notices, so `PackagedSurfaceIT` asserts the `<base href>` string rather than the status.
- **`ignored-path-prefixes` values are RELATIVE**, matched after `ui-root-path` is stripped: `/api`
  and `/q`, never `/maintenance/api`. An absolute value matches nothing and is indistinguishable
  from an unset key. Setting the key REPLACES Quinoa's derivation, which is why both are spelled by
  hand. **Add a literal route under `/maintenance` and its entry here in the same commit** — and
  give it a segment of its own, because an entry protects a segment and not a string prefix.
- **The bundle is built OUTSIDE the docker build.** `@qits/ui-components` exists only on the
  platform's own npm registry, which a `RUN` reaches by no address at all. So
  the pipeline step (`.config/qits/ci-event-release.yml`, or `ci-event-release-request.yml` for a
  fold) builds it in the step container (on qits-net) and the
  Dockerfile neuters Quinoa's install/ci/build commands with `--version`, guards the staged bundle
  with a `test -f` before the multi-minute native compile, and `cp`s the bundle onto itself so
  Quinoa's MOVE does not hit overlayfs' EXDEV.
- **Quinoa is off in test mode and stays off.** Every claim about the SPA belongs in
  `PackagedSurfaceIT`.
- **`quarkus-undertow` must never be on the classpath.** It arrives transitively from anything
  servlet-shaped and takes over the static-resource route Quinoa serves the bundle through — a
  packaged process that answers the API correctly and the SPA with a 404.

      ./mvnw -pl service -am dependency:tree | grep -i undertow

## Deliberately not here yet

Each is a decision, not an omission:

- **Bumping a transitive.** They are READ now — see "The dependency graph" — and shown on the
  repository page beside the pins. What is still not here is doing anything about one: a manifest
  holds direct pins and only those have a line to edit, so a transitive upgrade means somebody
  adding a managed version, which is a decision about their own build.
- **Retrying a MISSING sbom.** A released version is immutable and a 404 is the ordinary permanent
  answer for anything published before qits-artifacts had that route. `POST /artifacts/ingest` is
  the manual move and there is no schedule behind it.
- **External base image tags.** `FROM eclipse-temurin:…` — ordering tags across vendors is a later
  decision, and the mirror would answer with an upstream's whole tag history with no rule to rank
  it by. `FROM qits/*` is in scope.
- **`ng update` and tool-driven upgrades.** A group carries `name` and `deps`, and the step edits
  lines. A migration is a person's job.
- **Workspace creation.** Pushing the branch is the whole "merge request"; it is released through a
  qits-projects release request like any other branch — this service opens that request itself.
- **Polling the release request's PROGRESS.** qits-projects answers an id and this service stores it
  and stops. Its job ends at "request opened": the gates settle it, Auto Release tags it, and two
  mechanisms watching one fact would be two ways to disagree about it.
  <br>**What the dispatcher does ask is a different question, and it had to be added
  (2026-09-10).** Not "how far along is the release" but "is this request still one my bump can wait
  for" — because the gated dispatcher HOLDS a repository whose branch is pushed until the release
  lands on main, and a REJECTED request never lands. Live that day: nineteen bumps released, the
  twentieth was rejected at 07:14 for a red gating build, and four hours later the window was still
  open, qits-ci idle, that one repository still "owed", nothing dispatched and no line anywhere
  saying why. So `ReleaseRequestClient.state` reads the one request the bump names, on the tick that
  needs it: PENDING/READY/RELEASED holds, everything else makes the candidate STALLED and drops it
  out of the night, and an unreadable answer holds. The answer is never stored as a verdict — that
  service re-arms REJECTED, FAILED and CONFLICTED to PENDING on the next merged sha — only cached
  for `bump.dispatch.release-state-ttl` and written onto the bump row for a reader.
- **Automatic EXTERNAL bumps.** `qits.maintenance.bump.external.auto` exists so the deployment
  surface does not change the day they are implemented, and is read only to WARN when it is set.
  Somebody else's framework major is an opinion, and it stays a person's press.
- **Cancelling a bump.** There is no route and no column. The CI run has its own cancel, and a bump
  row saying CANCELLED would be a claim this service cannot make about a step that may already have
  pushed.
- **A second environment.** Every bump row records the environment it ran in, so a second one is a
  config entry — but the routing that would pick between two CIs is not written.

## OpenAPI document, pact provider and golden masters (epic qits-112)

`docs/openapi.yml` is written by `OpenApiSchemaExportTest` from the running app; commit it with
any API change. `BumpController`'s reads carry `operationId`s (`listBumps`, `getBumpWindow`,
`getBump`), which generated clients name their functions after.

qits-maintenance is a pact provider, set up like qits-githost-service and qits-events-service.
The test package `service/src/test/java/eu/wohlben/qits/maintenance/contracts/` holds it:

- `ProviderStates`: "pending bumps" (seven bump rows in the year 2100, so they are the newest:
  requested, running, green with its release owed, green with its request open, and three that
  are over: converged, released, failed) and "no
  pending bumps". The rows are written straight to `mt_bump` and deleted again (`cleanUp()`), so
  the dispatcher and the other suites never see them.
- `GoldenMasterRecordingTest` records `listPendingBumps` with `?limit=20` into `golden-masters/`.
  It compares by default; `-Dgolden.update=true` rewrites.
- `ConsumerPactVerificationTest` verifies `pacts/*_qits-maintenance-service.json` on the test
  classpath. It passes with no pact until the first consumer pact jar is pinned; then drop
  `@IgnoreNoPactsToVerify` and set `ClasspathPactLoader.REQUIRED = true`.

**Give a consumer an object, not a bare array.** pact-jvm (4.6.21) takes a consumer's rule at the
body root (`$`) as a whole-body matcher and runs `arrayContains` on the raw bytes, which passes
whatever the provider answers. That is why `listPendingBumps` answers `{ "bumps": [...] }`.

`.config/qits/release.yml` declares the golden masters as a contract, so the platform publishes
`eu.wohlben.qits:qits-maintenance-golden-masters` and `@qits/maintenance-golden-masters` when they
change.

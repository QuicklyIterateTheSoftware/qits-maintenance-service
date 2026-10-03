# qits-platform-maintenance

**The dependency inventory of every repository in the catalog**, the latest version of everything
they pin, and the maintenance branches that close the gap between the two.

It edits no file and pushes no branch. It reads manifests from the git host, asks the registries what
is newest, groups the pending upgrades per each repository's own configuration, and asks qits-ci to
apply one group as one branch. **This service decides *what* changes; a CI step applies them.**

It replaces the 71 per-repository `.config/qits/ci-event-upstream-*.yml` hop files: those followed
one internal release each, one branch per dependency, and force-pushed. The fifteen
`ci-event-upstream-frontend.yml` files are the same story one layer down — a service following its
own SPA submodule — and they are what the `gitlink` ecosystem retires.

The contract — routes, model, config keys, schedules and the bump payload — is pinned by
`qits-maintenance-plan.md` in the qits-qits wrapper. Three repositories build against it.

## What it reads, and from where

| Fact | Peer | How |
|---|---|---|
| the catalog | qits-projects | `GET /projects/api/repositories`; a row with no `name` has no address and is skipped. The row's `id` is kept as `mt_repository.catalog_id` — never an address here, and the only way another context's spelling of a repository is read back as a name. Its nullable `archetype` (SERVICE, DAEMON, LIBRARY, FRONTEND, CLI, IMAGE, PROJECT, SERVICE_TEMPLATE, FORK) is kept beside it as `mt_repository.archetype` (V6) and served on both repository DTOs, so what a repository IS is a column rather than a read per row; the vocabulary is qits-projects' own, so it is stored **verbatim, unvalidated and with no check constraint**, and an unrecognised word costs a repository nothing but a typed reading — the one archetype anything acts on is `PROJECT`, which the downstream closure excludes. **The listing is authoritative in both directions**: what it stops naming goes ABSENT — see below |
| manifests at `main` | qits-githost | `GET /git/<project>/<repo>/tree/<rev>[/<path>]` and `…/blob/<rev>/<path>` |
| internal latest | qits-artifacts | maven `maven-metadata.xml`, npm packument, OCI `/<name>/tags/list` |
| external latest | qits-platform-mirror | `central` maven-metadata, `npmjs` packument |
| applying a bump | qits-ci | `POST /ci/api/events/trigger`, event `MaintenanceBump` |
| the bump's outcome | qits-ci + qits-githost | `GET /ci/api/runs/{id}`, then the branch head |
| whether CI is busy | qits-ci | `GET /ci/api/runs/active` — the dispatch gate; an unreadable listing counts as busy |
| an internal release | qits-events | `SoftwareRelease` off the durable bus — see **The event bus** |
| a branch's life | qits-events | `SCMRelease`, `SCMDeleteBranch`, `SCMPublishCommit` |
| what a release CONTAINS | qits-artifacts | `GET /artifacts/sboms/<type>/<name>/-/<version>` — one CycloneDX document per released artifact; see **The dependency graph** |
| what a release DECLARED | qits-githost | the same manifests, read at `refs/tags/<version>` instead of at the main branch; see **The release ledger** |

**The head sha is resolved once per repository and every manifest is read at it.** The git host
stamps `Git-Commit-Sha` on every tree and blob answer, so one read of the root tree at `main` both
resolves the branch and proves the repository is readable. A scan that read each file at `main`
would be an inventory of whatever moved while it ran.

**A 404 from the git host is followed by a second read.** It answers "no such revision" and "no such
path" identically, so the root tree at the same sha is what tells ABSENT from GONE — qits-ci's
`HttpGitConfigSource` model, copied on purpose.

**THE INVENTORY FOLLOWS THE CATALOG OUT, NOT ONLY IN.** A scan that read the whole catalog marks
every `mt_repository` row the listing does not name `ABSENT` with `dropped from the catalog`, and
deletes its `mt_pin` and `mt_group` rows. Until 2026-09-03 a scan only ever upserted what the catalog
listed, so a renamed or removed repository kept the status its last successful scan wrote — OK — with
its pins, its groups and its pending count, for ever, because nothing else writes those rows.
**Measured on the first live nightly bump, 2026-09-03**: 48 repositories in the catalog against 96
rows here and ~800 pending changes; the clock asked for 30 bumps and 23 came back FAILED with `no run
recorded for MaintenanceBump`, every one of them a pre-rename ghost (`qits-spa-artifacts`,
`qits-stt`, `qits-projects`, `qits-platform-spa-*`).

- **The row is KEPT, not deleted.** The name still answers on `GET /repositories/{name}` with an
  honest status instead of a 404 that says nothing about why, and `catalog_id` — the translation
  every `mt_artifact` row written under another context's spelling reads back through — survives
  with it. `mt_branch`, `mt_scan` and `mt_bump` stay too: those are the LOG of what was asked and
  what came back, and a repository leaving the catalog does not un-push a branch that was pushed.
- **The pins and the groups go**, because they are a cache of the catalog's world. A repository the
  catalog dropped contributes no pending change and offers the clock no group; `BumpSchedule` skips
  everything that is not OK, and the button 404s on a repository that declares no group.
- **Three things never reconcile, and each of them would mark the whole platform absent.** A scan of
  ONE repository (`POST /scans {repository}`, and every `ScanTrigger.EVENT` push) read a listing of
  one and is evidence about nothing else; a catalog read that FAILED carries an empty list and is one
  peer's outage; and a read that succeeded and listed NOTHING closes the scan FAILED before the
  reconciliation is reached. The last guard is spelled twice, in `ScanService` and again in
  `MaintenanceStore.reconcileCatalog`, because the cost of the two disagreeing is the whole store.
- **A repository that RETURNS needs nothing of its own**: the next scan lists it and the ordinary
  upsert writes OK over the ABSENT row with fresh pins and fresh groups.

## What it scans

| Manifest | Ecosystem | Pins read | `location` |
|---|---|---|---|
| `pom.xml` + the poms its `<modules>` name | maven | `dependencies`, `dependencyManagement`, `parent` | `property:<name>`, `dependency:<g>:<a>`, `parent:<g>:<a>` |
| `package.json` + `package-lock.json` | npm | `dependencies`, `devDependencies` | `dependencies` / `devDependencies` |
| `Dockerfile`, `*.Dockerfile` | docker | every `FROM <image>:<tag>` | `line:<n>` |
| `.gitmodules` + the tree's `160000` entries | gitlink | every submodule | `gitlink:<path>` |

`kind` says what can be done with a pin, which is not the same question as who published it:

| `kind` | meaning |
|---|---|
| `INTERNAL` / `EXTERNAL` | a real version, comparable and bumpable; the name rule decides which registry answers |
| `REACTOR` | **this repository's own artifact** — its version comes from maven's coordinates (`${project.version}`), or its `groupId:artifactId` is a module of this same reactor. It moves with this repository's own release and no line anywhere holds it. |
| `UNRESOLVED` | an expression this service could not resolve. Recorded so a person sees what the repository wrote. |

`REACTOR` and `UNRESOLVED` pins are shown, and are never looked up, never pending and never in a
bump payload. **A gitlink is always `INTERNAL`, by construction rather than by a name rule** — a
submodule is a repository on this platform's own git host and nothing else can be one, so there is
no key to configure and no external half.

Discovery is the repository ROOT plus the reactor, and nothing else. `service/src/main/webui` is
never *scanned*: it is a gitlink to the SPA's own repository, which the catalog lists in its own
right. **The gitlink itself is a pin**, which is a different fact from what it contains — one line
this repository owns and can move, where its contents belong to the submodule's own row.

- **A gitlink's name is the URL's basename**, `qits-artifacts-frontend`, not the `[submodule "..."]`
  entry's: a rename leaves the section behind and the url is what a clone resolves. Both spellings on
  this platform give the same answer — the wrapper writes them relative, the service repositories
  absolute.
- **A gitlink's version is a COMMIT SHA**, read from the mode-`160000` entry in the tree rather than
  from `.gitmodules`, which names a submodule and never its version. A git host that does not report
  that sha yields **no gitlink pin at all** rather than one at a guessed version — see *Rollout
  needs*.
- **An unparseable `.gitmodules` is not `CONFIG_ERROR`.** That status is for
  `.config/qits/maintenance.yml`, this service's own configuration surface. `.gitmodules` is git's
  file: what parses is used and the rest is dropped.

**The whole reactor is read before any of it is parsed**, because two of the rules below cannot be
answered from a single pom.

- **Expressions are resolved in the groupId and the artifactId too, not only the version** — from
  the pom's properties and from maven's own built-in coordinates (`${project.groupId}`,
  `${project.version}`, their `project.parent.*` siblings, the deprecated `pom.*` and the bare
  spellings), with a module that declares no groupId or version inheriting its parent's.
- **A property reference is resolved and remembered**, so the location is the property rather than
  the dependency element — rewriting the element would replace an expression with a literal. A pin
  whose property lives in the ROOT pom is recorded against the root pom, because that is the file
  holding the line. A BUILT-IN is never such a location: no file holds `${project.version}`.
- **A parent that is this repository's own root pom is not recorded at all.** It is the reactor's
  shape, not a dependency. A parent from OUTSIDE the reactor — a shared `qits-parent` from the
  registry — stays a pin and is bumpable.
- **A dependency with no version of its own is not a pin.** It takes one from a BOM; there is no
  line here to edit.
- **npm's version is the LOCK's, and the manifest's range rides beside it as `range`.** A range is
  not a version and cannot be compared with a registry's answer.
- **A digest or a tagless `FROM` is not a pin**, and v1 looks up only `qits/*` images: ordering base
  tags across vendors is a later decision.

## Grouping — the kind split, and `.config/qits/maintenance.yml`

**The default grouping is the pin's KIND.** A repository that configures nothing gets two groups:
`dependencies` claims every `INTERNAL` pin and `external` claims every `EXTERNAL` one, so the
platform's own releases travel on `maintenance/dependencies` and everybody else's on
`maintenance/external`. The two halves are found by different schedules and reviewed by different
eyes; one branch carrying both made a nightly internal bump wait behind an opinion about a framework
major. `dependencies` keeps its name and its branch — it is now the internal half of what it used to
be all of.

A repository that wants a FINER grouping than those two halves writes one:

```yaml
groups:
  - name: angular
    deps: ["@angular/*", "@qits/angular"]
  - name: quarkus
    deps: ["io.quarkus:*", "io.quarkus.platform:*"]
```

A group's name is also its branch: `maintenance/angular`. `deps` are globs — `*` and `?` only, over
the flat dependency name — and **a pin matching two groups belongs to the first declared**. The kind
pair is appended AFTER whatever the file declares, so a configured group always claims first and
what none of them claimed still splits by kind. A file that declares `dependencies` or `external`
itself keeps its own globs under that name, and only the other half is appended. **Invalid yaml is
`CONFIG_ERROR` on the repository row and nothing is bumped for it** — falling back to the default
grouping would put changes on a branch the author configured against.

`GroupDto.kind` is `INTERNAL`, `EXTERNAL` or null: how the group claims, which is a different
question from `source` (whether the repository asked for the grouping at all).

The same file also carries `ignore:`, which takes a whole **ecosystem** off the repository:

```yaml
ignore: [gitlink]        # maven | npm | docker | gitlink
```

Grouping says which branch a bump rides on; `ignore` says the pin is not one at all. An ignored
ecosystem is **not parsed, not stored, not grouped and never pending** — the manifests it would have
read are not even fetched, and because an inventory is replaced wholesale, pins an earlier scan
stored disappear on the first scan after the line is committed. **An unknown ecosystem name is
`CONFIG_ERROR`**, like any other mistake in this file: a typo quietly dropped would read as a working
opt-out while the ecosystem the author meant to protect went on being bumped nightly.

The case it was built for is the **qits-qits wrapper**, whose forty-seven submodule gitlinks are
deliberately lagging bank markers rather than version pins — its own README says they exist so
`git submodule update --init` works on a fresh clone while the submodules follow their branches, and
every entry carries `ignore = all` for the same reason. Without the opt-out this service would read
those forty-seven lagging shas as forty-seven upgrades and open a nightly bump against a doctrine
the repository states in writing. The mechanism is general; the wrapper is why it exists.

## Pending

`mt_pin ⋈ mt_latest`, read through `mt_group`, computed on every read and never stored. A pin has to
be `INTERNAL` or `EXTERNAL` before anything is offered at all; then two rules decide:

1. it is strictly newer in that ecosystem's own order — maven's `ComparableVersion` for a pom, real
   semver for npm, the maven order over calver tags for an image;
2. **a prerelease is offered only when the pin is a prerelease too.**

`mt_latest.latest` is the highest **release**, falling back to a prerelease only when a dependency
has never published one. One column serves every pin of a dependency, and a release candidate
sitting in it would — by rule 2 — hide three stable upgrades from everyone.

A latest that could not be read offers nothing and says so: the pin carries `latestError`, because
"we could not find out" must not look like a green tick.

**Neither rule reaches a `gitlink`, and forcing one would be arithmetic on a hash.** A gitlink pin is
a commit sha, which no order ranks and which is neither a release nor a prerelease. So the question
is the only one that can be asked honestly — *is the submodule pinned at the commit the newest
release was cut from* — and **both shas have to be known** for the answer to be no: `mt_latest`
carries the release's commit in `source_url` as `sha:<hex>`, and a row without one offers nothing.
The change's `to` is then the calver **version** (the step fetches `refs/tags/<to>`) while its `from`
is the sha the tree holds now.

## The bump

**Two callers ask for one: a person, and the clock.** `POST
/repositories/{name}/groups/{group}/bumps` is the button, on any group. The clock owes the INTERNAL
group (`dependencies`) of every OK repository that has something pending there and no bump already
going — the external half and a repository's own configured groups are manual-only. **No scan bumps
anything any more**, whoever triggered it; the old `bump.auto` tail of a SCHEDULED scan is gone,
because a scan's schedule is set by how fast facts go stale and a bump's by when a branch is welcome.

### The clock does not fire, and it does not open a window either — DEBT DOES

`schedule/BumpSchedule` at 02:00 used to walk the inventory and ask for every eligible bump in one
tight loop. The first live run of that asked for 30 at once, and the wavefront was only half the
damage: a library's bump and its consumer's went out in the same breath, so the consumer built
against the pin it was about to be handed anyway and needed a second bump the next night. So the
cron stopped firing and opened a **dispatch window** instead, with `bump/BumpDispatcher` handing out
one bump per tick inside it.

**Which left the hour as the only thing that could arm any of it, and that was the next defect.**
`open` had two callers — the cron and the by-hand door — so the path the design is actually built
around (something becomes owed, qits-ci goes idle, the bump goes) could not happen at any other
hour. Live on 2026-09-11: `@qits/ui-components` cut at 10:44, fifteen repositories owed by 10:45,
and at 17:20 none of them sent with an idle CI queue in front of them. A wider window would not have
saved it either — one such release is roughly **twenty-eight dispatches**, because every frontend
ships inside its service as a gitlink and the service's hop cannot begin until the frontend's
release has landed and been rescanned.

**So the window is a consequence of debt.** `schedule/BumpDispatchSchedule` ticks every 15s (the
interval the poller uses), the dispatcher asks the same questions with or without a window row, and
it opens one itself the moment something dispatchable is owed:

| gate | question | when it is not met |
|---|---|---|
| in flight | how many bumps of ours are REQUESTED or RUNNING | wait; the window stays open |
| owed | is anything a candidate | **the window closes** — the ordinary ending |
| quiet hours | is this an hour a branch is unwelcome in | wait, with the owed set reported |
| qits-ci | `GET /ci/api/runs/active`, every entry counted | wait |

**"Not now" is `bump.dispatch.quiet-hours`, and nothing else.** Comma-separated `HH:MM-HH:MM` ranges
in `qits.maintenance.time-zone`, start inclusive and end exclusive, wrapping midnight allowed, and
**empty by default** — a platform that never asked for a quiet period does not silently have one,
which is what the cron had quietly become. It suppresses the debt-driven opening only: `POST
/bumps/window` is the override, because pressing it is the statement quiet hours are an exception
to. An unparseable entry is dropped with a WARN rather than obeyed, because read as quiet it would
stop every bump for ever.

**And the window's expiry is a safety valve, not a guillotine.** It used to end the night and drop
whatever the chain had not reached, silently, until the next 02:00. Now it closes the row **saying
how many it cut short and which they are**, and the same tick re-opens one if the work is still
owed. What expiring still buys is the periodic reset — the refusal set and the cached release
states start clean — and a real stop the moment a quiet hour is reached.

**A repository whose branch is pushed is HELD — and a hold asks what became of its release.** Pending
is read off the pins on `main`, and main does not move until the release lands, so a bumped
repository is still owed by every measurement here: held keeps it out of the pick, keeps it blocking
its consumers, and keeps the window open. That rests on the release being on its way, and on
2026-09-10 it was not — one repository's release request was REJECTED for a red gating build at
07:14, and four hours later the window was still open, qits-ci idle, that repository the only thing
owed, nothing dispatched and nothing saying why. So the hold reads the request
(`ReleaseRequestClient.state`): PENDING, READY and RELEASED hold; REJECTED, FAILED, CONFLICTED and
WITHDRAWN make the candidate **stalled** — dropped from the night, so its consumers go and the window
can close — and an unreadable answer holds, like an unreadable CI queue. It is asked again every
tick and never stored as a verdict, because qits-projects re-arms a rejection on the next merged sha;
`bump.dispatch.release-state-ttl` (60s) is how long one answer is reused, and `mt_bump.release_state`
is what a reader sees.

**`GET /bumps/window` answers WHY, not just whether — and it answers with no window open.** Beside
`openedAt`/`closesAt` (null when there is none) it carries the tick's own reasoning as the read finds
it: `outcome`, the in-flight and CI counts, how many are owed and held, every stalled repository with
qits-projects' sentence about it, what would go next, and **`queue` — the whole owed set in dispatch
order**, each entry READY / BLOCKED (on whom) / HELD / STALLED / REFUSED. It used to 404 when no
window was open, and `GET /bumps` holds only bumps that were *dispatched*, so "fifteen owed, nothing
sent" and "the scheduler is dead" were the same picture from every surface — which is how an arming
bug sat unnoticed for a day. Nothing on that read closes a window or dispatches anything.

**Both counts are compared against `bump.dispatch.max-in-flight`.** At its default of 1 that is the
request in its plain form: an empty CI queue and nothing of ours outstanding. **An unreadable
listing is BUSY, never empty** — a gate that read "I could not ask" as "nothing is going" would fire
the whole night at the one moment qits-ci is least able to say so.

**The candidates are recomputed every tick, never frozen as a night's plan.** `PendingChanges` is
computed on every read anyway, so the tick after a bump releases sees its consumers' new pin instead
of the one they were already going to get.

**The window is a row (`mt_bump_window`), because this service redeploys itself in the middle of
one.** It was a field, and the argument for that was that a process down at 02:00 misses the night
today too. The argument does not hold here: qits-maintenance is one of the fifty repositories
qits-maintenance bumps, so a successful bump of it becomes a release and a release becomes a
redeploy of this container — a restart mid-window is the ordinary outcome of the window working, not
a rare accident. Measured live on 2026-09-10: nineteen bumps dispatched one at a time from 06:32,
this service's own release deployed at 08:11, and then nothing at all, with eleven repositories owed
and four hours of window left. Persisting it resumes the night; it does not make it eternal, since
`closes_at` is still compared with the clock and a window slept through comes back already over.

### Bottom of the chain first

`bump/BumpOrder` picks the candidate nothing else owed sits below. The relation is read off the
**changes**, not off the whole graph: a candidate is held back only while one of its own pending
changes names something another candidate publishes. A dependency on a repository with nothing owed
is not a reason to wait — that release already happened, and its version is what the change moves to.

Two spellings of "who publishes this", because there are two kinds of edge: a maven, npm or docker
coordinate is matched through `ArtifactGraph#producers()` (the released-artifact ledger), and a
GITLINK pin — which has no artifact at all — is matched by name, because a submodule's `name` IS its
repository. Only for that ecosystem, so a package called like a repository invents no edge.

**A cycle degrades to a pick, never to a stall.** The graph is not guaranteed acyclic; when every
candidate waits on another, the least-blocked one goes with a WARN naming what it was waiting on. A
night in which nothing is bumped is the worse outcome.

**And among equally ready candidates the tiebreak is LEAST RECENTLY BUMPED, because it used to be
the alphabet.** `BumpOrder` takes the first free candidate nothing owed sits below *in the order it
was handed*, and that order was `MaintenanceStore.repositories()` — `ORDER BY name`. One bump goes at
a time and each is held until its own release lands, so an estate-wide fan-out drains at roughly one
repository every five to fifteen minutes and the end of the alphabet is the end of every night, for
the same repositories every time. Measured 2026-09-13: `qits-projects-daemon` and
`qits-workspace-daemon` consume the identical two jars from one `qits-coding-agents` release and are
ready at the same instant; the first was dispatched at 19:53, the second at 21:28 — nine repositories
later, purely by name. So `assess()` now orders its candidates by `lastScheduledBumpAt()` ascending
(a repository the clock has never reached counts as the oldest and goes first, the name is the final
tiebreak so the order stays deterministic) and hands that to `BumpOrder`, whose algorithm is
unchanged. The topological rule still wins outright: an owed upstream goes before its consumer
however long ago either was bumped. **Only SCHEDULED rows count** — a targeted or hand-pressed bump
is a caller's press on a named branch, and letting one push a repository to the back of the queue
the clock drains would be the starvation again with a different cause.

**`bump.dispatch.gated=false` brings the old loop-and-fire back**, unchanged. It stays reachable
rather than deleted because how much a qits-ci can take at once is a property of a deployment.

**One nightly bump coalesces every release since the last one.** The changes are frozen onto the row
at request time, so five internal releases between two nights are ONE branch push, one CI build, one
release request and one release — not five of each. That is the whole of the storm fix.

`POST /repositories/{name}/groups/{group}/bumps` freezes the group's pending changes onto an
`mt_bump` row and queues it. The row id travels as the CI event's `eventId`, which is the dedupe key
— a dispatch whose answer was lost records no second run when it is retried.

```json
{ "name": "MaintenanceBump", "eventId": "<mt_bump.id>",
  "payload": { "repository": "qits-ci-service", "group": "dependencies",
               "branch": "maintenance/dependencies", "baseRef": "main",
               "changes": [ {"ecosystem":"maven","manifestPath":"pom.xml",
                             "name":"eu.wohlben.qits:qits-eventstream",
                             "from":"2026.811.1","to":"2026.821.3",
                             "location":"property:qits.eventstream.version"} ] } }
```

`location` is honoured for maven only; npm edits whichever section holds the entry, docker anchors on
the image name, and gitlink restates the path. It is sent anyway. **A gitlink change is the one whose
`manifestPath` names no file**: it is the directory the submodule sits at, and the step writes a
`160000` index entry there after fetching the tag from the sibling repository its `name` addresses. `from` is never a precondition — a manifest already at
`to` is a quiet no-op. Every value is validated on this side against what the step enforces, so a
bad payload is a sentence on the bump row rather than a step log somebody has to read.

**Three answers from qits-ci and they mean different things:**

| answer | outcome |
|---|---|
| 200 with run ids | RUNNING; the poller follows them |
| **503** | RETRY. The bump stays REQUESTED with its changes and the sweep sends the same payload under the same event id. |
| **200 with no run id** | FAILED, `no run recorded for MaintenanceBump (repository unreadable or no platform pipeline)`. A run exists only if the repository was readable in that evaluation, so nothing is running and nothing will be. |

**The branch head is read twice — before the trigger and when the run ends — and only the head is
compared, never a commit count.** One bump is up to two commits, because the maven step and the
node/docker step each clone, commit and push.

| run | branch | bump | `mt_branch` | release ask |
|---|---|---|---|---|
| SUCCESS | moved | `SUCCEEDED` | `PUSHED` | **asked** |
| SUCCESS | unmoved | `NOTHING_TO_DO` | unchanged | not asked — nothing was pushed |
| red | unmoved | `FAILED` | `FAILED` | not asked |
| red | **moved** | `FAILED` | `STALE` — the push is ff-only and never forced, so a branch that moved anyway is a person's commit. They own it now. | not asked — releasing somebody else's commits on their behalf is the one thing this must never do |

### The release ask

**A bump that pushed a branch asks for it to be released, itself.** It calls

```
POST {projects-url}/projects/api/repositories/<repoId>/release-requests
{ "branch": "maintenance/dependencies",
  "summary": "bump(dependencies): 5 dependencies" }
```

and gets back `{"request": {id, state, backingBranch, mergedSha, …}}`. **Nothing merges and nothing
is released at that call** — a release REQUEST is OPENED, qits-projects folds `main`, this branch and
the repository's released tags still in flight onto `release/<id>`, the quality gates settle that
fold, and Auto Release tags it once they pass. **This service's job ends here**: it does not
poll the request, wait for a version, or record a release.

- **The repository is addressed by its CATALOG ID** — `mt_repository.catalog_id`, the `id`
  qits-projects' own listing answers, which `CatalogReader` has copied onto the row since V5. That
  route resolves its path parameter against qits-projects' repository table and nothing else, so the
  name and the project cannot address it. A row with no catalog id records a refusal; the next scan
  fills the column and the next bump asks with it.
- **`summary` is the commit subject shape the bump's own commits carry**, word for word from
  qits-ci's `.config/qits/platform-pipelines/maintenance-bump.yml`. The `n` is what was ASKED FOR, and it
  cannot be what a commit says: one bump is up to two commits and each counts what its own step
  applied. It doubles as the fold's commit message.
- **No `expectedSha`, and that is a deliberate loss.** qits-workspaces' door armed a request at the
  instant it was asked, so a head that had moved in between had to be a refusal. A release request
  is re-folded and re-gated on every push to any of its named sources, so a commit landing after the
  ask is gated rather than smuggled in — continuous re-gating replaces the pin.
- **No `requester`.** The field states whom a machine peer acts for, and a bump has no such person:
  a nightly one was asked for by a clock and a manual one records no operator. Omitted,
  qits-projects attributes the request to this service's own identity.
- **The ask is CONVERGENT**, which is what makes a retry of it free: a branch already participating
  in an open release request answers that request rather than opening a second one.

**`mt_bump.release_request_id` holds the answer, and NULL is the one value that means work is owed.**

| value | what it means |
|---|---|
| a request id | a release request is open and this bump names it. It is OPEN, not released — follow it in qits-projects |
| `converged` | there was nothing to hold on to — a 2xx with no id, or the branch was gone before the ask could be made |
| `refused` | a 4xx that is not an auth failure: a refusal a retry cannot fix. `message` says which, and the next nightly bump of the group asks again from scratch |
| null | the ask is still owed, and the sweep re-attempts it |

**A qits-projects that will not answer NEVER flips the bump's status.** The bump succeeded: the run
was green and the branch moved, both facts about this service's own work. A transport failure, a 5xx,
or a 401/403 leaves the row `SUCCEEDED` with a sentence on `message` and the column null, and the
poll sweep asks again on the next tick. **The retry is bounded by the branch, not by a counter**: it
stops when the request exists or when `SCMDeleteBranch` makes the branch NONE — which is also what a
landed release leaves behind, since a request's named sources are deleted when it lands. A counter
would additionally have to be right about how long qits-projects may be down for.

## The event bus

**This service subscribes and publishes nothing.** Two durable listeners on the platform's
`qits-eventstream` bus turn four facts other services know into inventory writes within seconds,
where v1 waited up to six hours for a poll.

| listener | `consumerId` (storage — never change it) | events | what it does |
|---|---|---|---|
| `bus/SoftwareReleaseListener` | `maintenance-internal-latest` | `SoftwareRelease` (qits-ci) | moves `mt_latest` **forward only**, so every pin of that dependency is pending the moment the package is in the registry |
| `bus/ScmEventListener` | `maintenance-branch-tracking` | `SCMRelease` (qits-projects), `SCMDeleteBranch`, `SCMPublishCommit` (qits-githost) | records every release as the latest of a **gitlink** and in the **release ledger**, clears a maintenance branch when it is deleted, and re-reads one repository's manifests after a push to its main branch |

- **`SoftwareRelease` is the only writer of `mt_latest` that moves it forward only**, and that is the
  difference between an announcement and a poll. A poll ASKS a registry what the newest version is
  and the answer replaces what was there, downgrades included; an announcement is evidence that THIS
  version exists and never that a higher one does not. Without the guard, a catch-up frame from
  yesterday would rewind a column this morning's scan filled, and the whole inventory would report
  that dependency as up to date until the next scan. `packageName` joins `mt_pin`'s naming directly:
  maven `g:a`, npm `@scope/name`, docker `qits/<name>`. A `docs` release settles — an api-docs
  bundle is a real fact and nothing pins it.
- **A `daemon` release writes the artifact row and NOT the latest column**, which is the one type
  that makes exactly one of the two writes. The qits CLI's version is a pom pin now — qits-ci pins
  `eu.wohlben.qits:qits-platform-access-cli-binary`, whose version IS the `daemons`-store coordinate
  of the binary the same release published — so a daemon release that left no `mt_artifact` row left
  the GC nothing to derive a keep from, and a store collecting at `window=P0D` took the binary out
  from under a pin that still named it. No `mt_latest`: that column is compared against a pin of the
  same ecosystem and there is none (the pom holds the *maven* coordinate), and `LatestResolver` has
  no registry to refresh it from. `daemon` stays **out of the `Ecosystem` enum** — a fifth constant
  costs a parser, a resolver and a bump step, and a daemon binary has none of the three — so the row
  carries the literal string and `Ecosystem.of("daemon")` still answers empty. The row is written
  **PENDING and its document is ingested like any other artifact's**: the SBOM route is keyed by the
  released artifact's *type* (`/artifacts/sboms/daemon/<name>/-/<version>`), not by an ecosystem,
  and `SbomClient` addresses a row by its stored word — so a library's dependents include the daemon
  binaries that carry it. (Until qits-703 the row was written terminal `FAILED`, because the route
  was then addressed through an `Ecosystem`; `V13__requeue_daemon_sboms.sql` put those rows back to
  PENDING and the boot's `RestartRecovery` read them.)
- **The artifact row also keeps where the release came from** (V14): `projectId`, `section`
  (`artifacts`|`contracts`) and `runId`, the three facts the daily SBOM check reads. All optional on
  the wire; filled when a frame names them, never overwriting a stored value, and a `runId` that is
  not a uuid is dropped rather than failing the decode.
- **`SCMRelease` is also the ONLY source of a gitlink's latest.** There is no registry to poll — a
  submodule is a git repository and nothing publishes one — so the daily scan neither fills that row
  nor clears it, and `LatestResolver.resolvable` refuses the ecosystem outright. Every release is
  recorded, not only those of repositories something pins today: the gate would be wrong in the one
  direction that costs, leaving a repository that grew a submodule between two releases with no
  latest for weeks. Two facts are written — the calver `version`, which is what the step fetches as
  `refs/tags/<version>`, and the **commit that tag resolves to**, carried in `source_url` as
  `sha:<hex>`. The tag is read from the git host rather than correlated with the `SCMPublishCommit`
  a moment earlier: a release is an atomic push of the branch and its tag, so one read answers it,
  while pairing two publishers' frames by repository and time is wrong exactly when two releases are
  close together. A tag the git host does not hold is settled; a git host that cannot be *asked* is
  thrown, because nothing else ever writes this row.
- **`SCMRelease` makes a SECOND write here too, and it is the release ledger.** One hop after the
  gitlink latest, the tree at `refs/tags/<version>` is read and its INTERNAL pins are recorded as
  what that release DECLARED — see **The release ledger**. It runs **whether or not the latest
  column moved**: `mt_latest` is forward-only because it answers "where can a pin move to", which a
  catch-up frame must not rewind, while a ledger row answers "what did THIS release declare", and a
  late-announced older release deserves its row exactly as much as this morning's does. The failure
  split is the same one: an unreachable git host is thrown, a tag it does not hold is a WARN, and a
  manifest that would not parse records whatever parsed.
- **`SCMRelease` says nothing about a maintenance branch any more, and `BranchState.RELEASED` is a
  word nothing writes.** It used to: qits-workspaces' door published the event naming the branch it
  had just tagged over, which was the one fact nothing else could tell this service. A release is now
  a tag on a release request's fold, `release/<id>`, published by qits-projects — so `branch` on that
  event names the fold and never a `maintenance/` one. A maintenance branch's whole ending is the
  `SCMDeleteBranch` that follows the release (a request's named sources are deleted when it lands),
  which is the same signal a person deleting it by hand sends, and `NONE` — "the next bump starts
  fresh from main" — is the right answer to both. That delete is also the only thing that clears a
  `STALE` row. `RELEASED` stays in the enum because old rows hold it.
- **A push to a repository's own main branch queues a scan of that ONE repository**, through the same
  path `POST /scans {repository}` takes, with `trigger: EVENT`. It **never bumps** — a push changes a
  manifest, and whether the pending set becomes a branch is still the clock's standing instruction or
  a person's press. A burst of pushes is debounced against a scan of that repository already queued
  or running.
- **`SoftwareRelease` makes a SECOND write, and it is a different fact.** `mt_latest` says a version
  EXISTS; an `mt_artifact` row says THIS release has contents worth reading, PENDING, picked up off
  the worker queue afterwards. The row is written whether or not the column moved — a catch-up frame
  is not the newest version and its contents are still unrecorded. See **The dependency graph**.
- **The daily scans are the reconciliation belt, not the mechanism.** The internal cron moved from
  every six hours to 00:30 daily when these landed. It still covers three things no listener can: an
  event that was never published or was settled as poison, a repository added to the catalog (which
  announces nothing here), and the window after a new consumer starts at the head of the log and
  skips everything published before it.
- **Delivery is durable, so a disconnect is a delay rather than a hole.** A claim and the handler run
  in one transaction and a watermark is paged forward from qits-events' log at startup and on a
  schedule. The failure rule is the seam's: a payload that will not parse, or one naming a repository
  or group this inventory does not hold, is poison — a WARN and a settle; a database that will not
  answer is left to throw, and the event stays owed for the next sweep.
- **The consumed payloads are TRANSCRIPTIONS of records in three other repositories**, decoded into
  local records so no foreign jar is on the path, and pinned by `bus/ForeignEventContractTest`. A
  rename over there is a change to that file in the same campaign.
- **The bus brings a second database.** `.config/qits/deployments.yml` declares
  `postgresql:eventstream:qits_platform_maintenance_eventstream`, and the resource name is
  load-bearing — the jar reads `QITS_RESOURCE_EVENTSTREAM_*`. It is dark in `%dev` and `%test`
  (`qits.eventstream.enabled=false`), and **dark is not absent**: the datasource is opened and
  migrated at boot regardless.

## The dependency graph — what a release CONTAINS

**An SBOM says what a released artifact CONTAINS; `mt_pin` says what a bump EDITS.** They are
related by `(ecosystem, name)` and they never merge, because neither can answer the other's
question:

- **an SBOM cannot name a pom property.** It holds resolved coordinates and versions; a bump needs
  the LINE — `property:qits.eventstream.version` — which only a manifest read gives.
- **a pin cannot see a transitive.** A manifest holds what its author wrote down; everything the
  resolver pulled in behind it exists only in the built artifact's bill of materials.

So an inventory built from SBOMs would be unbumpable, and an inventory built from manifests cannot
answer "who ships a copy of this". Both are kept, joined at read time, and neither is derived from
the other.

**The row is the OUTBOX.** A `SoftwareRelease` frame writes an `mt_artifact` row PENDING and
returns; the document is fetched afterwards on the one worker thread. A listener that fetched inline
would hold a bus claim open across another service's HTTP call, and a slow qits-artifacts would turn
one release into an event redelivered for ever.

**A 404 is MISSING, it is the ORDINARY answer, and nothing retries it.** The SBOM route is newer than
most of what this platform has released, so most coordinates have no document — and a released
version is immutable, so asking again tomorrow asks about the same bytes. What supplies an answer is
the NEXT release of that artifact, which brings its own row; a person who knows a document has since
been stored asks by hand with `POST /artifacts/ingest`.

**Direct is the root component's own `dependsOn` list and nothing else.** That is the whole value of
reading the document: a direct component is something a manifest could hold a line for, and a
transitive one is something no line anywhere names. A component whose purl names a world this
service does not inventory (`pkg:golang/…`) is stored with a **null ecosystem** — shown, never
matched.

Three tables, and the graph's foreign keys: `mt_artifact` (one row per released
version), `mt_artifact_component` (what it contains, with the purl verbatim), `mt_artifact_edge`
(who pulled in whom — adjacency, not a closure, because the question is the PATH).

**`SoftwareRelease.repository` is qits-projects' ROW ID, not the catalog name, and
`mt_repository.catalog_id` (V5) is the translation.** Measured live on 2026-09-02: the field arrives
as `daf73ae4-…`, so `mt_artifact.repository` filled up with uuids while every read that joins it —
the detail page's transitives, `GET /repositories/{name}/dependents`, and `DependentDto.repository`,
which the client renders as the link to that page — joins on the NAME, and all of them answered
nothing without saying so. The catalog is where both spellings are known at once: qits-projects'
listing answers `id` beside `name`, `CatalogReader` keeps it, and every scan writes it. The frame is
resolved at the WRITE now (`SoftwareReleaseListener`), and `ArtifactGraph` translates in both
directions at READ time for the rows written before that — an id the catalog does not know passes
through untouched in either arm, because an unknown spelling must not lose the fact that a release
said it.

## The release ledger — what a release DECLARED

**An SBOM says what a release CONTAINS; the release ledger says what it DECLARED.** Two tables,
`mt_release` (one row per released `(repository, version)`, with the commit its tag resolved to) and
`mt_release_pin` (the INTERNAL pins the tree at that tag held). Written by `bus/ScmEventListener` on
every `SCMRelease`, through `adoption/ReleaseLedger`.

**It exists because the SBOM rule cannot reach the npm world at all, and that was measured.** On
2026-09-08 the adoption journey of `qits-ui-components-jslib 2026.906.164412` named fifteen
frontends at depth 1 and fifteen services at depth 2 and reported every one of them PENDING, while
every one of them had picked the release up weeks earlier:

- **a frontend publishes no registry artifact.** Its release is a git tag, and what consumes it is
  the embedding service's gitlink bump — so there is no `mt_artifact` row of the frontend to hold a
  component, and `dependents(npm, @qits/ui-components)` answered the empty list;
- **a service's docker-image SBOM holds maven components only.** The compiled Angular dist carries
  no npm metadata, so `qits-ci-service`'s newest document listed 239 maven components and 0 npm
  ones, and the frontend→service hop was equally unprovable.

**Why a pin is allowed to be evidence here, when a pin on `main` still is not.** The objection to a
pin was never that it is a pin: it is that a pin read at `main` is a fact about somebody's WORKING
TREE — nothing polls it, it moves under you, it is revertible, and a verdict computed from one would
flicker. A pin read at `refs/tags/<version>` is a different fact. A tag is immutable and it is tied
to the consumer's own released version, which is exactly what the adoption answer REPORTS. So the
rule stands as it always did — the evidence is about a release, not a working tree — and the ledger
is what makes it obtainable for a repository that publishes nothing.

- **INTERNAL pins only**, by the same `kindOf` the inventory uses. EXTERNAL is somebody else's
  package and nothing of ours releasing it makes anybody downstream; REACTOR and UNRESOLVED name no
  version anything could compare. **GITLINK survives that filter without a special case**, because a
  submodule is INTERNAL by construction — and it is the half that matters most.
- **A gitlink pin's version is a COMMIT SHA**, kept verbatim, the same asymmetry `mt_pin.version`
  carries. A reader resolves it through the submodule repository's own `mt_release` rows.
- **The write is idempotent**: one row per `(repository, version)`, and re-recording rewrites the
  pins rather than adding to them, because the second reading of one tag is a correction of the
  first. That is what makes a durable redelivery and a backfill re-run converge.
- **The reads are the manifest scanner's own**, at a revision instead of a branch — one head
  resolution, the four parsers, the per-line dedupe. A second copy of that discovery would be a
  second answer to "what does this repository declare", and the two would disagree the first time a
  parser changed.
- **It is a LOG, like `mt_artifact`, and unlike `mt_pin`.** A scan replaces `mt_pin` wholesale
  because that question is about the main branch TODAY; a row here is the reading of one immutable
  tree and nothing a repository does afterwards invalidates it.

**A backfill fills in what the estate already released**, at boot, on the single worker thread
(`work/ReleaseLedgerBackfill`). Its input is `mt_latest`'s GITLINK rows — the only place on this
platform where "this repository released this version, at this commit" is written down, filled in by
that same listener since long before this table existed. One release per repository, which is all
`mt_latest` keeps and all this question needs: a journey asks who is carrying a release NOW, and a
consumer's newest release is the one that answers. A repository with no inventory row is skipped (a
ledger read needs a project to address the git host with), and a failure is one repository's rather
than the run's.

## API

Under `/maintenance/api`, path-routed on every vhost. Every route takes `qits:admin` (a person, via
the edge's `X-Qits-User` / `X-Qits-Roles`) or `qits:system` (a machine, via a bearer). Every `GET`
also takes `qits:agent` (a commissioned agent); no write does. There is no anonymous route. Every
error body is `{"message": "..."}`.

```
GET  /repositories                                → [{name, project, lastScanAt, headSha, status,
                                                      message, pending,
                                                      groups:[{name, source, kind, branch, state,
                                                               headSha, pending}]}]
GET  /repositories/{name}                         → the above, plus
                                                    pins:[{manifestPath, ecosystem, name, version,
                                                           range, kind, latest, latestError,
                                                           pending, group, location,
                                                           scope: "DIRECT"}]
                                                    transitives:[{ecosystem, name, version, via,
                                                                  behind}]
GET  /dependencies?name=<glob>[&kind=]            → [{ecosystem, name, latest, checkedAt, error,
                                                      pins:[{repository, version, manifestPath,
                                                             pending}]}]
GET  /dependencies/dependents?ecosystem=&name=    → {ecosystem, name, latest,
     [&all=true]                                     dependents:[{artifactEcosystem, artifactName,
                                                                  artifactVersion, repository,
                                                                  embeddedVersion, direct,
                                                                  occurredAt, sbomStatus}]}
GET  /pins                                        → {generatedAt,
                                                     repositories:[{name, status, lastScanAt,
                                                                    headSha}],
                                                     pins:[{ecosystem, name, version, repository,
                                                            manifestPath, via}]}
                                                                503 the inventory holds no row at all
GET  /artifacts                                   → [{ecosystem, name, repository, latest, version,
                                                      occurredAt, sbomStatus, dependentCount,
                                                      behindCount}]
POST /artifacts/ingest {ecosystem,name,version}   → 202 {id}    400 unknown ecosystem
GET  /repositories/{name}/dependents              → {repository,
                                                     artifacts:[{ecosystem, name,
                                                                 dependents:[…as above]}]}
POST /scans {scope, repository?}                  → 202 {id}    400 unknown scope
GET  /scans/{id}                                  → {id, scope, repository, trigger, status,
                                                     startedAt, finishedAt, message}
POST /repositories/{name}/groups/{group}/bumps    → 202 {id}    404 unknown repo or group
                                                                409 one is active, or bumping is off
GET  /bumps?repository=&limit=20                  → [the bump below]
GET  /bumps/{id}                                  → {id, repository, group, branch, environment,
                                                     trigger, status, ciEventId, ciRunId, ciRunIds,
                                                     configPath, ciRunStatus, startedAt, finishedAt,
                                                     message, changes:[…]}
GET  /repositories/{name}/downstream              → {repository, catalogId,
                                                     downstream:[{repository, catalogId,
                                                                  archetype, depth, via:[…]}]}
GET  /adoption/by-release?repository=&version=    → {repository, catalogId, version,
                                                     packages:[{ecosystem, name}],
                                                     adopters:[{repository, catalogId,
                                                                repositoryStatus, archetype,
                                                                depth, via:[…],
                                                                state: ADOPTED|PENDING,
                                                                adoptedVersion, adoptedAt}]}
                                                                400 half a key is not a lookup
GET  /sbom-check                                  → {ranAt, filed,
                                                     entries:[{project, repository, ecosystem, name,
                                                               version, reason}],
                                                     warnings:[…],
                                                     tickets:[{project, ecosystem, name, ticketSlug,
                                                               versions:[…]}]}
                                                                404 no check has run yet
POST /sbom-check/runs                             → 202 the report above, once the run finished
                                                                502 qits-artifacts could not be asked
```

- **The SBOM check** (`sbomcheck/SbomCheckService`, daily at 02:15): every `mt_artifact` row of type
  maven, npm, docker or daemon, from the `artifacts` section (or from before sections), whose SBOM is
  MISSING, FAILED, or PENDING past `pending-grace`, whose version is still in qits-artifacts, and
  which names a project — no cut-off on age. `reason` is MISSING, FAILED or PENDING. A row with no
  project (released before V14) has it resolved from its repository through the catalog listing
  (`GET /projects/api/repositories`) — exact catalog id, or an exact name exactly one project carries
  — and written back; ambiguous, unknown or an unreadable catalog is a `warnings` line, never a
  ticket. Presence is read from listings that record no access
  (`maven-metadata.xml`, the packument, `/v2/<name>/tags/list`,
  `/artifacts/api/repositories/daemons/daemons/<name>/versions`) so the probe never keeps alive
  what it reports; a 404 is "collected" and any other failure fails the run (502 on the door, nothing
  stored). `tickets` is every ticket row still open after the run. `GET` takes `qits:agent` too;
  `POST` is `qits:admin`/`qits:system`. Shipped report-only — `filed: false`.

- `scope` is `INTERNAL`, `EXTERNAL` or `ALL`. **Every scan re-reads every manifest whatever the
  scope says** — the scope governs only which half of the registry lookups refresh.
- **`POST` answers 202 and does not wait.** A scan is one git-host read per repository plus a
  registry lookup per dependency; a bump is a CI run. The client polls `GET /scans/{id}` or
  `GET /bumps/{id}`.
- A scan `FAILED` means the scan did nothing — the catalog was unreadable, or the run hit an
  exception. One unreachable repository is that repository's status, not the scan's.
- **A scan row is never left RUNNING.** Any exception closes it FAILED with the sentence, and at
  boot every scan a dead process left open is closed `interrupted by restart`: a scan's work is
  entirely in-process, so a successor cannot resume one and must not pretend it did. **Bumps are
  resumed instead** — their work is qits-ci's, the run outlived this service, and the first sweep
  after boot re-dispatches a REQUESTED bump under the same event id or polls a RUNNING one to its
  end.
- `GET /bumps` carries `changes` too; a change list is small.
- **`kind` on `/dependencies` is INTERNAL or EXTERNAL and nothing else.** REACTOR and UNRESOLVED are
  refused with a 400 rather than answered with an empty list: neither is a half of the split the
  filter serves, and an empty list would read as "there are none of those". The filter is
  server-side because the two halves are two pages — the same split every default group, every
  branch and both scan schedules already make.
- **`/dependencies` and `/dependencies/dependents` are two routes because they are two facts.** A
  pin is a line a bump can edit; a dependent is a component inside a published package, transitives
  included. The default view of `dependents` is the NEWEST released version of each dependent —
  forty-nine older releases of one library are answers about versions nobody can change any more —
  and `all=true` is the archaeology.
- **`transitives` on the repository detail is what its RELEASES contain that no manifest names.** It
  is read from the newest INGESTED document of each artifact the repository publishes, with anything
  that is also a pin removed (that row is already on the page, with a verdict). `via` is the direct
  component whose subtree pulled it in — the first by name where several do, because a graph has
  many paths and a page needs one. **Empty means "we do not know"**, not "there are none": a
  repository whose releases have no stored document is the ordinary state during the rollout.
- **`/pins` is the artifact GC's dependency-pin source and is read by a machine, not a page.**
  qits-artifacts collects the registry against a few keep-sets read once per run — what the running
  services deploy, what the images name, and this one: every internal maven, npm and docker version
  a catalogued repository's main branch still references. Rows are served **as stored** — no dedupe
  and no folding, because the consumer folds and each row names the repository and manifest that
  make a keep decision explainable — in one total order (ecosystem, name, version, repository,
  manifest), so two reads over an unchanged store answer the same bytes. `gitlink` is excluded: it
  is INTERNAL by construction and its version is a commit sha, which is not an artifact anything
  could collect. `repositories` carries the freshness the consumer judges the answer by.
- **The docker rows include the images a pom pins WITHOUT SPELLING THEM OUT, and `via` is how you
  tell.** Container image versions are maven pins now — qits-workspaces pins
  `qits-workspace-daemon-protocol` and `qits-workspace-editor-image`, qits-projects pins
  `qits-projects-daemon-protocol`, and each of those artifact versions IS the tag of an image the
  same release published. So `control/CarriedImages` resolves them: for every internal maven or npm
  pin, the repository that released that coordinate, and every docker artifact **that same repository
  released at that same version**. The mapping is the release's own assertion —
  `.config/qits/release.yml` through the `SoftwareRelease` frame into `mt_artifact` — and never a
  table anybody has to maintain. Without it the image of a bump that has landed on main but not yet
  deployed is named by no pin source on the platform and is held by retention alone, which for OCI is
  a `P0D` window and `RELEASES_KEPT=2`. A derived row carries `via` (`maven eu.wohlben.qits:…`) and
  the repository and manifest of the **pinning** pom; a row a `FROM` line really wrote carries none,
  and where both exist the stored row wins.
- **The same rule one artifact type over: the `daemon` rows.** `control/CarriedDaemons` is
  `control/CarriedImages` with the platform's binary store in place of the registry — for every
  internal maven or npm pin, the repository that released that coordinate, and every **daemon**
  artifact that same repository released at that same version — and the two call one walk
  (`control/CarriedArtifacts`), because they differ in two tokens and nothing else. It exists for the
  `qits` CLI: qits-ci pins `eu.wohlben.qits:qits-platform-access-cli-binary`, whose version IS the
  store coordinate of the binary, and the `daemons` store collects at `window=P0D` keeping the last
  two versions — so the pin rotted with nothing bumping it and nothing holding it back, and release
  pipelines 404'd on the fetch. The derived rows are served with `ecosystem: "daemon"`, which is a
  **contract**: qits-artifacts' `MaintenanceHttpDependencyPins` files a row by exactly that word and
  refuses the whole pin source on one it cannot file, so it is deployed before this service serves
  one or every GC run fails closed. `daemon` is not an `Ecosystem` and never becomes one; only a
  derived row is ever spelled with it, since no manifest this service parses pins a daemon.
- **An inventory with no rows at all answers 503 rather than an empty keep-set.** The consumer is
  fail-closed on a source it could not read — that run deletes nothing — and treats an answer as
  authoritative, so "this service has never scanned" must never arrive as "nothing on the platform
  is referenced". A scanned inventory in which some repositories are UNREACHABLE still answers 200:
  those rows keep the pins their last good scan read, so the keep-set is stale rather than absent,
  and `status` and `lastScanAt` say so on the row.
- **`scope` on a pin is always `DIRECT`, and it is a constant on purpose.** The detail now serves two
  lists whose rows look alike, and a client rendering them in one table needs the distinction on the
  row rather than derived from which array it came out of.
- **`/repositories/{name}/downstream` IS A WIRE CONTRACT**, not merely a page's shape: qits-projects
  reads it on its release-request announce path and folds the names into
  `ReleaseRequestChanged.downstreamTechnicalComponents`, which qits-ci orders its build queue by.
  The shape is pinned in `qits-maintenance-plan.md` in the qits-qits wrapper, and **the ORDER is the
  information** — depth ascending then name, so reading it top to bottom reads "upstream first".
- **The closure is TRACED TO THE END, and it is a query rather than a table.** It replaced the
  persisted release trains, whose membership was written down ONCE at the release by a single
  one-hop pass — so a library release named the frontend that pins it and could never name the
  service behind that frontend. Two sides are unioned: every INTERNAL `mt_pin` on a coordinate the
  repository publishes (the DECLARED side) and every artifact whose bill of materials names one (the
  EVIDENCE side). Cycles terminate on a visited set, and two bounds truncate rather than throw —
  depth 10 and 500 repositories.
- **A repository's own NAME is a coordinate, in the `gitlink` ecosystem, and that is the second
  hop.** A frontend is a service's `service/src/main/webui` submodule; `GitmodulesParser` records the
  submodule's repository name as the pin's `name`, so "who submodules this repository" is already an
  indexed answer. **The wrapper is excluded by its `PROJECT` archetype** rather than by pretending
  gitlinks do not exist — it pins every submodule on the platform, so including it would put it on
  every answer and then expand it into the whole estate.
- **Both routes take a catalog NAME or a catalog id**, because the caller usually holds the latter:
  qits-projects addresses repositories by its own row id, which IS this inventory's `catalog_id`.
  The answer is always spelled as the catalog names it.
- **Neither route 404s.** An unknown repository is an empty closure — the caller is an announce path
  and must not have to classify a refusal — and an unknown release is empty `packages` with a real,
  wholly PENDING closure. The retired `/trains/by-release` answered 404 for a release that had opened
  no station, which was a fact about the log rather than about the release.
- **`state` is `ADOPTED` or `PENDING` and there is no third.** ADOPTED means one of the
  repository's OWN releases proves it is carrying the coordinate at or above the required version,
  compared inclusively in that ecosystem's own order — a consumer that skipped straight past the
  version has adopted it too. `adoptedVersion` is **the adopter's OWN released version**, never the
  dependency version it took: it is half of the address
  `release-requests/by-release/<catalogId>/<adoptedVersion>`, and a dependency version there
  resolves to nothing. The EARLIEST matching release wins, by `occurredAt`.
- **TWO EVIDENCE KINDS PROVE IT, and both are about a RELEASE.** What the release CONTAINS — a
  component in its bill of materials (`mt_artifact_component`), which sees transitives no manifest
  names. And what the release DECLARED — a pin in the tree at its own tag (`mt_release_pin`), which
  sees what nothing ever published. The second is not "a pin moved on a branch", which this service
  still refuses: a pin read at `main` is a fact about somebody's working tree, revertible and
  unpolled, while a pin read at `refs/tags/<version>` is immutable and is tied to the consumer's own
  released version — the very thing `adoptedVersion` reports. See **The release ledger**.
- **A PENDING hop leaves everything behind it PENDING**, because there is no version of it to
  require yet — a service cannot be shipping a library through a frontend that has not shipped the
  library.
- **The GITLINK hop is provable now, and it used to be the one that never closed.** A repository's
  own name is a coordinate in the `gitlink` ecosystem, so it joins the requirement at every hop
  beside whatever the release put into a registry; the evidence for it is the ledger and only the
  ledger, because no SBOM component is ever a gitlink. The pin's version is a COMMIT, so it is
  resolved through the SUBMODULE repository's own releases (matched abbreviation-tolerantly) to the
  version that commit belongs to, and that version is what the comparison sees. A commit the ledger
  cannot place is no match — the embedder pinned an off-release commit, or a release older than
  anything recorded.
- **`repositoryStatus` is joined LIVE from `mt_repository`**; `ABSENT` beside a PENDING row says the
  journey is waiting on something the catalog no longer lists.
- **`packages` is what the release put into a registry**, read from its `mt_artifact` rows through
  the same `ReleaseCoordinates` the closure derives coordinates from — so the label and the adopters
  cannot disagree about what was released. It carries no version, because every row of it is at the
  release's. Empty is ordinary: a `docs`-only release names no coordinate, and nothing pins a
  daemon.

The document is at `/maintenance/q/openapi`, the browsable UI at `/maintenance/q/swagger-ui`, and
readiness at `/maintenance/q/health/ready`. The client is served at `/` — this service has a host of
its own, `maintenance.<env>.<domain>`, and the `/maintenance` segment is the wire surface alone.

## Configuration

Every key below is defaulted in the domain jar
(`maintenance/src/main/resources/META-INF/microprofile-config.properties`) and overridable by
environment without a rebuild.

| key | default | what it decides |
|---|---|---|
| `qits.maintenance.targets.projects-url` | `http://qits-projects:8080` | where the catalog is |
| `qits.maintenance.targets.githost-url` | `http://qits-githost:8080` | where the manifests are |
| `qits.maintenance.targets.ci-url` | `http://qits-ci:8080` | which CI applies a bump |
| `qits.maintenance.targets.artifacts-url` | `http://qits-artifacts:8080` | where the SBOM documents are — a bare host, because the route's whole path belongs to the caller |
| `qits.maintenance.registries.maven-url` | `http://qits-artifacts:8080/artifacts/maven/maven` | internal maven |
| `qits.maintenance.registries.npm-url` | `http://qits-artifacts:8080/artifacts/npm/npm` | internal npm |
| `qits.maintenance.registries.oci-url` | `http://qits-artifacts:8080/v2` | internal images |
| `qits.maintenance.mirror.maven-url` | `http://qits-platform-mirror:8080/artifacts/maven/central` | Maven Central, cached |
| `qits.maintenance.call-timeout` | `PT60S` | how long one peer call may take |
| `qits.maintenance.internal.maven-groups` | `eu.wohlben.qits` | which maven groups this platform publishes |
| `qits.maintenance.internal.npm-scopes` | `@qits` | which npm scopes it publishes |
| `qits.maintenance.internal.image-prefixes` | `qits/` | which images it publishes |
| `qits.maintenance.scan.enabled` | `true` | whether the CLOCK may scan |
| `qits.maintenance.scan.internal.cron` | `0 30 0 * * ?` | the internal scan, 00:30 daily — the reconciliation belt behind the bus |
| `qits.maintenance.scan.external.cron` | `0 0 1 * * ?` | the external scan, 01:00 daily |
| `qits.maintenance.sbom.sweep-cron` | `0 5 * * * ?` | re-queue artifact rows still PENDING, hourly. It never retries MISSING or FAILED |
| `qits.maintenance.sbom.check.cron` | `0 15 2 * * ?` | the daily SBOM check (`SbomCheckSchedule`) |
| `qits.maintenance.sbom.check.pending-grace` | `PT24H` | how long a PENDING row is a queued fetch rather than a finding |
| `qits.maintenance.sbom.check.file-tickets` | `true` | **shipped on**: a check run files, comments and drops MAINTENANCE tickets; off returns to report-only, where a run calls nothing in qits-projects |
| `qits.maintenance.time-zone` | `UTC` | the zone both crons are read in |
| `qits.maintenance.bump.enabled` | `true` | whether a branch may be pushed at all |
| `qits.maintenance.bump.internal.cron` | `0 0 2 * * ?` | the INTERNAL bump's hour — **only used when `dispatch.gated=false`**; gated dispatch arms itself on debt |
| `qits.maintenance.bump.internal.auto` | `true` | whether the clock asks for those bumps. **The live deployment holds it `false` until the pre-split branches are drained** |
| `qits.maintenance.bump.external.auto` | `false` | **reserved.** External bumps are manual-only; setting it logs a WARN once and does nothing |
| `qits.maintenance.bump.poll-interval` | `15s` | how often an unfinished bump is looked at — and how often a dispatch is considered |
| `qits.maintenance.bump.dispatch.gated` | `true` | one bump at a time against an idle qits-ci. `false` restores the old fire-everything loop |
| `qits.maintenance.bump.dispatch.max-in-flight` | `1` | what "idle" means, compared to BOTH our unfinished bumps and qits-ci's whole active listing. Floored at 1 |
| `qits.maintenance.bump.dispatch.release-state-ttl` | `60s` | how long qits-projects' answer about ONE release request is reused before a held candidate asks again. `0` asks every tick |
| `qits.maintenance.bump.dispatch.quiet-hours` | *(empty)* | hours a branch is unwelcome in: `HH:MM-HH:MM[,…]` in `time-zone`, end exclusive, midnight-wrapping allowed. Suppresses the debt-driven opening only; `POST /bumps/window` overrides it |
| `qits.maintenance.bump.internal.window` | `6h` | how long one window lasts before it is closed, logged and re-opened if work is still owed. It closes early the moment nothing is owed, and it is also how long a refusal stands |
| `qits.maintenance.environment` | `dev` | which environment's CI is recorded on a bump row |

**The registry keys carry a PATH as well as a host**, because a registry is mounted under a prefix
and the prefix names the repository row it serves. Moving a row is then a deployment's decision.
**`targets.artifacts-url` deliberately does not**: `/artifacts/sboms/…` is qits-artifacts' own API
rather than a mount, so its whole path belongs to the caller and lives in the code.

**The npmjs cache has no key at all.** `qits.maintenance.mirror.npm-url` is gone (qits-472): its
address is derived in code (`PeerTarget.NPM_MIRROR`) as
`http://${QITS_ENVIRONMENT:dev}-qits-platform-mirror:8080/npm/npmjs`, so a deployment still setting
`QITS_MAINTENANCE_MIRROR_NPM_URL` sets something nothing reads. It stays on the internal alias
rather than the public edge because the edge wants Basic with a client pair or a `qits_tok_`, and
every call from here carries the `qits` client's minted bearer instead.

**`bump.enabled` stops the button as well as the schedule**, which is the point: a platform that
wants to watch what *would* change for a week reads the inventory and pushes nothing.
`bump.internal.auto` only stops the clock. **No scan bumps under any setting** — pressing Scan asks
what is out of date, pressing Bump asks for a branch, and the clock's standing instruction is its own
cron.

**`QITS_MAINTENANCE_BUMP_AUTO` is now inert.** Nothing reads it; MicroProfile does not fail on an
environment variable no key claims, so a live platform still carrying it is misleading rather than
broken. Remove it from the deployment's extras at the next edit of that file.

**One tier service by configured url.** qits-ci is per environment (`dev-qits-ci`) while this
service is platform tier, so a live platform injects the qualified name. Known debt, the same one
qits-configuration and qits-platform-orchestrator carry.

**Outbound credentials are one named oidc client, `qits`** (epic qits-540 dossier, 'Plan (as of
2026-09-13)', C4). It used to be five — a token was cut for one service's own audience, so a
peer-scoped call needed a peer-scoped client — but every token now asks the one platform audience,
`qits-platform`, which every receiver accepts, so one client mints for every peer. Its id, secret and
idp address come from the deployer's `idp:client` resource alone: `.config/qits/deployments.yml`
declares it, and qits-deployments injects `QITS_RESOURCE_IDP_URL`, `QITS_RESOURCE_IDP_CLIENT_ID` and
`QITS_RESOURCE_IDP_CLIENT_SECRET`. Configure none of them, and the old `projects` client's extras set
nothing.

Three old named blocks are still shipped, `projects`, `ci` and `githost`, and none is a stub for a
client nobody configures: the container still carries `QUARKUS_OIDC_CLIENT_PROJECTS_{CLIENT_ID,
CREDENTIALS_SECRET,CLIENT_ENABLED}` and one leftover `_AUTH_SERVER_URL` apiece for `ci` and
`githost` — one such variable is enough to mint each map key, and both `client-enabled` and
`discovery-enabled` default to true when nothing says otherwise. Each block's three keys,
`client-enabled=false`, `discovery-enabled=false` and `token-path=token`, are what keep that client
from dialling its issuer during runtime init and failing the boot — `ci` and `githost` are pointed at
the now-retired `dev-qits-platform-idp` alias, so left alone either would fail outright rather than
build an inert client. Nothing injects any of them and no code asks them for anything. They go once
no such variable reaches the container any more — the config GC deletes the retired entries and the
deployer's extras file stops stating them (qits-375) — not before.

**One toggle reaches every peer.** With the client off, calls go out with the forward-auth pair
alone (`X-Qits-User: qits-platform-maintenance`, `X-Qits-Roles: qits:system`), which every call
carries regardless; with it on, all five peers — qits-projects, qits-githost, qits-ci,
qits-platform-artifacts and qits-platform-mirror — are called with a bearer. There is no per-peer
arming, and every one of the five accepts `qits-platform`.

**There was a sixth, `configuration`, and it went with the release trains** — as did
`qits.maintenance.targets.configuration-url` and `qits.maintenance.train.sweep-cron`. Nothing here
polls qits-configuration any more: the two release-train ends that were polled (an image becoming a
runtime pin, a daemon climbing qits-ci's ladder) were each the owning service's fact one click away,
and re-reading two peers on a timer bought failure modes for them. A deployment still setting
`QITS_MAINTENANCE_TARGETS_CONFIGURATION_URL`, `QITS_MAINTENANCE_TRAIN_SWEEP_CRON` or
`QUARKUS_OIDC_CLIENT_CONFIGURATION_*` is setting keys nothing reads; remove them at the next edit of
that file.

**The release ask needs no client of its own.** It is a qits-projects route, so it rides the `qits`
client's bearer every catalog read already mints; the route admits `qits:admin` and
`qits:system`, and `qits:system` is what every call here carries. There was a client for it once —
audience `qits-workspaces`, for the release door — and it went with the door, the same way the
`configuration` one went with the trains. A deployment still setting
`QUARKUS_OIDC_CLIENT_WORKSPACES_*` is setting keys nothing reads.

**The store** is its own PostgreSQL database, `qits_platform_maintenance`, declared by
`resources: postgresql:db` in `.config/qits/deployments.yml`. Twelve tables in four families:
`mt_repository`, `mt_pin`, `mt_group`, `mt_latest` (an inventory a scan replaces wholesale);
`mt_scan`, `mt_branch`, `mt_bump` (a log of what was asked and what came back, derivable from
nothing); `mt_artifact`, `mt_artifact_component`, `mt_artifact_edge` (what each released
artifact CONTAINS, replaced per artifact by each ingest — and the only foreign keys in the schema,
because both ends are this context's own); `mt_release`, `mt_release_pin` (what each released
TREE declared, rewritten per release by each recording); and `mt_sbom_check_run`, `mt_sbom_ticket`,
`mt_sbom_ticket_version` (V14 — the daily SBOM check's reports and the ticket it holds per
artifact, the last carrying one more part-of foreign key).

**A second database, `qits_platform_maintenance_eventstream`**, declared by
`postgresql:eventstream:<name>` beside it, holds the bus's outbox and the two durable consumers'
claim ledger and watermarks. It is qits-eventstream's, with its own Flyway lineage, and it is never
shared with the store above.

## Rollout needs

**The idp client `qits-platform-maintenance` is the orchestrator's shape plus one claim.** The
claim is not optional — it is a route this service cannot use without it.

| what | why |
|---|---|
| role `qits:system` | the machine role qits-platform-orchestrator's client carries. It covers qits-projects' catalog, qits-githost's content policy, qits-ci's trigger and — since qits-ci a3ecce2 — the read-only run and repository routes the bump poller follows. |
| a qits-projects serving `POST /repositories/{repoId}/release-requests` | **The release ask, and it needs nothing new here.** That route admits `qits:system` beside `qits:admin`, so the `qits` client's bearer already opens it and no `qits:admin` lands on a service — the bootstrap's "qits:admin is a person's role" doctrine stands. Until that qits-projects release is deployed the ask is a 404, recorded as a refusal; the next nightly bump of the group asks again. |
| claim `project` = `*` | qits-ci's trigger calls `machineAuth.requireProject("*")`, which passes only for a token literally granted every project. The bump names one repository but the trigger route demands them all. Today the only such grant is qits-platform-artifacts'; this service needs its own. |
| audience `qits-platform` | **The only audience the `qits` client asks for now** (C4) — one platform audience for every peer, not one per service. Nothing to grant beyond it: C2 makes `qits-platform` always allowed for any client, so the per-service audience list below is no longer needed for this service's own calls. |

In `qits-configuration` / `.qits-bootstrap.env` terms that is a client with `_ROLES` carrying
`qits:system` and `_CLAIMS_PROJECT: "*"`. The `_AUDIENCES` list that used to name `<env>-qits-ci`, `qits-projects`, `qits-githost`,
`qits-platform-artifacts` and `qits-platform-mirror` is inert now — every call asks for
`qits-platform` alone — and is removed once this repository's C5 cutover lands (C9).

**Gitlinks need the git host to report a tree entry's sha — written (qits-githost 33b0ccf), not
yet deployed.** That commit teaches `GET /git/<project>/<repo>/tree/<rev>[/<path>]` to answer a
gitlink entry as `{"name","type":"commit","sha","mode":"160000"}` while every other entry keeps its
two-field shape; `blob`/`tree` of a gitlink path stay 404 because the sha on the entry is the whole
answer. Against the deployed githost, which still collapses a gitlink to `blob` with no `mode` and
no object name, this service reads `mode` and `sha` off a tree entry when they are there and pins
**nothing** when they are not — a made-up version would be compared by the pending rule and then
applied into somebody else's repository. Both spellings (`mode` or `type`) are accepted here. Until
that githost release deploys, the fifteen `ci-event-upstream-frontend.yml` hop files still do the
work and nothing is lost.

**qits-ci answers the events with its packaged platform pipelines**:
`.config/qits/platform-pipelines/maintenance-bump.yml` for `MaintenanceBump` and
`screenshot-baselines.yml` for `ScreenshotBaselines` (until 2026-10-02 the bump pipeline was a
wrapper file). A qits-ci without them records no run, and every bump ends FAILED with `no run
recorded for …`, which is the honest answer rather than a silent success.

**The nightly bump needs the branches drained first.** Every `maintenance/dependencies` branch that
exists right now carries MIXED commits — internal and external together, from before the kind split
(see `V2__internal_external_split.sql`). Release or delete them, then flip
`QITS_MAINTENANCE_BUMP_INTERNAL_AUTO=true`. The jar defaults it true; the deployment holds it false
until that is done, because the first internal bump after the split would otherwise push onto a
branch still carrying an external upgrade nobody reviewed under that name.

**The bus needs one deploy and one check, in that order.**

- **Deploy A — the resource.** The second `resources:` line is read at the built sha, so the first
  deployment carrying it is what creates `qits_platform_maintenance_eventstream` and injects
  `QITS_RESOURCE_EVENTSTREAM_*`. The variables have no defaults on purpose: a container started
  without them dies at Flyway naming what is missing, and the health gate keeps the previous one — a
  loud, safe failure rather than a fallback store nobody meant. `QITS_EVENTS_URL` defaults to the
  qits-net alias `http://qits-events:8080` and needs nothing.
- **Deploy B — confirm the maven `packageName` against ONE live frame.** The three name spellings
  the release listener assumes are read off the `artifacts:` declarations committed in the
  platform's own `ci-event-release.yml` files, which qits-ci copies into the payload verbatim; maven
  is the one worth checking by hand, because it is the only name carrying a separator and a frame
  arriving as `qits-eventstream` rather than `eu.wohlben.qits:qits-eventstream` would write a row
  nothing joins to and say nothing about it. After the first internal release following the deploy,
  read that frame's payload and compare it with the same artifact's `mt_pin.name`.
- **Both consumers start at the HEAD of the log.** `maintenance-internal-latest` and
  `maintenance-branch-tracking` are new storage keys, so every release published before the deploy
  is skipped. That is what the 00:30 scan is for; nothing has to be replayed by hand.

## Building and testing

```
./mvnw clean verify -Dquarkus.http.test-port=0
```

Green on a clone with **no docker and no credentials** — the suite spawns its own PostgreSQL from a
Maven artifact (zonky) and the six peers are faked. It needs two things: a **node on PATH** and an
**initialised webui submodule**, because `verify` runs `package` and `package` is where Quinoa
builds the client. `./mvnw test` needs neither — Quinoa is off in test mode.

`-Dquarkus.http.test-port=0` is not optional on the deployment host: Quarkus' default test port 8081
is the platform's own npm registry there.

Integration tests are skipped by default. `-DskipITs=false` runs `PackagedSurfaceIT` against the
fast-jar; `-Dnative` builds the GraalVM binary (`.sdkmanrc` names `25.0.2-graalce`) and runs it
against that.

The image is `docker/Dockerfile`, built from the repo root with the client bundle already in the
context — see `AGENTS.md`.

Release requests opened by this service always ask at the LOWEST priority — a dependency bump is never what anybody is waiting for.

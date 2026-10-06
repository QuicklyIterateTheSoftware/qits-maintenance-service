-- RELEASE-REQUEST AUTOMATIONS (epic qits-978): one engine for every regeneration that has to land
-- INSIDE a release request, run on every fold of that request and held against it until fresh.
--
-- The two regenerations this service already ran were two MODES, each with its own dispatch and its
-- own ending: TARGETED (V12) wrote a wrapper's gitlink pins onto the caller's branch, BASELINES (V16)
-- rendered a request's screenshot references onto a branch of its own and joined it. They differed
-- in one fact — whose branch the commit lands on — and that fact is now the kind's own `target()`.
-- So both become one mode, AUTOMATION, and WHICH regeneration a row is becomes a column. A third
-- kind is one more implementation of `automation.ReleaseRequestAutomation`, never a migration.
--
-- `release_request_id` is reused as it is: a BASELINES row always carried its request there, and an
-- automation row knows its request from the moment it opens. GROUP rows are untouched — a group bump
-- learns its request only after the release ask, and every column added here stays null on it.

-- The kind's wire name: `estate-pins`, `screenshot-baselines`. Null on every GROUP row.
alter table mt_bump add column automation_kind varchar(64);

-- The fold an automation outcome is FOR: the request's merged sha when the row was opened. An outcome
-- is a statement about one fold and about nothing else — the gate holds a request until every kind
-- that applies is fresh for the sha it is about to release. Null on GROUP rows and on the rows mapped
-- below, which predate it.
alter table mt_bump add column fold_sha varchar(64);

-- The fold before it, as the trigger named it, and whether every path that changed between the two
-- lay under the applicable kinds' committable paths. Together they are what CARRY-OVER is decided by
-- — a fold that only automations' own commits produced is fresh without a run — and what the circuit
-- breaker counts. Stored rather than recomputed because a row that waits behind a running one is
-- re-decided when it is finally dispatched, long after the trigger that carried the diff has gone.
alter table mt_bump add column previous_fold_sha varchar(64);
alter table mt_bump add column automation_only boolean;

-- The kind's own payload fields, as its plan answered them at the fold (a JSON object), sent beside
-- the shared ones when the row is dispatched — which may be a sweep later than the plan, so they are
-- frozen on the row exactly as a bump's changes are. Null when the plan named none.
alter table mt_bump add column automation_extras text;

-- The two old modes, mapped across. Nothing writes either word any more; BumpMode.of still reads
-- them, so a row this migration somehow did not reach is still what it was.
update mt_bump set mode = 'AUTOMATION', automation_kind = 'screenshot-baselines'
    where mode = 'BASELINES';
update mt_bump set mode = 'AUTOMATION', automation_kind = 'estate-pins'
    where mode = 'TARGETED';

-- What the engine asks on every fold and every sweep: the rows of one request (per fold, per kind),
-- and how many automation runs are going estate-wide.
create index idx_mt_bump_automation_request on mt_bump (release_request_id, automation_kind, started_at)
    where automation_kind is not null;

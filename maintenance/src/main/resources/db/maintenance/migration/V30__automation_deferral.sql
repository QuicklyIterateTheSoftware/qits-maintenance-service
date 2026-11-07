-- A KIND QA CHECKS, DEFERRED AT A FOLD, IS STORED (qits-1920).
--
-- Since qits-1180 a kind the repository's QA names under `check-outputs:` is not run before QA for
-- a trigger that accepts DEFERRED: QA runs the same generator and fails on stale output. That
-- answer was never stored, so the read door (`GET /release-requests/{id}/automations`, what `qits
-- maintenance automations` prints) built from `mt_bump` alone did not list the kind at all, and a
-- fold where every kind was deferred had no row — the door's default fold fell back to an older
-- one.
--
-- One row per (request, fold, kind) the trigger answered DEFERRED, written `on conflict do
-- nothing` against the unique index below: qits-projects asks about the same fold again every
-- thirty seconds until its answer is final. Not an `mt_bump` row and not a `BumpStatus`: nothing
-- ran, nothing is followed, and `GET /bumps/{id}` has nothing to say about it. A kind that later
-- gets an `mt_bump` row at the same fold — the re-run door — is read from that row instead.
create table mt_automation_deferral (
    id uuid not null,

    -- qits-projects' release request id, as `mt_bump.release_request_id` holds it.
    request_id varchar(255) not null,

    -- The fold the kind was deferred at.
    fold_sha varchar(64) not null,

    -- The kind's wire name, as `mt_bump.automation_kind` holds it.
    automation_kind varchar(64) not null,

    -- The sentence the trigger answered with.
    detail text,

    -- When the first trigger deferred it; the read door's default fold compares it with
    -- `mt_bump.started_at`.
    created_at timestamp(6) with time zone not null,

    primary key (id)
);

create unique index uq_mt_automation_deferral
    on mt_automation_deferral (request_id, fold_sha, automation_kind);

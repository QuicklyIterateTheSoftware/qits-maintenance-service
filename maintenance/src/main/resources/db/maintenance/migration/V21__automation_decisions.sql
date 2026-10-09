-- PRE-RUN DECISIONS THAT HAVE NO RUN (qits-1133).
--
-- An automation kind that does not apply to a repository at one fold used to be left out of the
-- answer and decided again on every ask. qits-projects now lists every kind, with the reason it was
-- skipped, so the decision is kept: one row per (request, fold, kind).
--
-- Only NOT_APPLICABLE is written today. It is a table of its own and not an mt_bump row, so the bump
-- lists, the poll, the history the circuit breaker walks and carry-over never see it.
create table mt_automation_decision (
    id uuid not null,
    release_request_id varchar(255) not null,
    fold_sha varchar(64) not null,
    automation_kind varchar(64) not null,
    repository varchar(255) not null,
    state varchar(32) not null,
    reason text,
    decided_at timestamp(6) with time zone not null,
    primary key (id)
);

create unique index uq_mt_automation_decision
    on mt_automation_decision (release_request_id, fold_sha, automation_kind);

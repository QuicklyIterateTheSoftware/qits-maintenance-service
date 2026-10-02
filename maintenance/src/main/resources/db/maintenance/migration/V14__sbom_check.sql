-- THE DAILY SBOM CHECK (qits-621 / qits-668): which released software artifact still in
-- qits-artifacts has no usable bill of materials, and the MAINTENANCE ticket that says so.
--
-- mt_artifact learns three facts off the SoftwareRelease frame that announced the row:
--   * project_id — the qits-projects project the release belongs to, which is where a ticket about
--     it is filed. Null on every row written before the listener read it; such a row is a WARN line
--     in the check's report and NEVER a ticket into a guessed project.
--   * section — `artifacts` or `contracts`, the release.yml section the entry came from. Contracts
--     are never counted by the check. Null on rows from before qits-ci carried it, and null counts as
--     `artifacts`, which is what every entry was before the section existed.
--   * run_id — the qits-ci release run that published it, for the ticket's description.
-- All three are filled when a frame names them and never overwrite a value already stored.
alter table mt_artifact
    add column project_id varchar(64),
    add column section varchar(16),
    add column run_id uuid;

-- One row per check run: when, whether it filed (qits.maintenance.sbom.check.file-tickets), and the
-- report it computed, as JSON text — the convention every other JSON column in this schema follows
-- (mt_bump.changes, mt_group.patterns), rather than jsonb nothing here queries into. GET
-- /maintenance/api/sbom-check answers the newest row's report.
create table mt_sbom_check_run (
    id uuid primary key,
    ran_at timestamptz not null,
    filed boolean not null,
    report text not null
);

create index idx_mt_sbom_check_run_ran_at on mt_sbom_check_run (ran_at desc);

-- The CURRENT ticket per (project, ecosystem, name). The version is deliberately not in the key:
-- one artifact with ten affected versions is one ticket with comments, not ten tickets. When a
-- closed ticket is replaced, the row is overwritten in place — ticket_id and ticket_slug replaced,
-- closed_at nulled — and its version rows deleted, so the unique key always names one live ticket.
--
-- closed_at is set when the check is done with the ticket: it dropped it, found it already closed or
-- gone, or found it retyped away from MAINTENANCE (then it is told and left open in qits-projects,
-- and closed here so it is never commented on twice).
create table mt_sbom_ticket (
    id uuid primary key,
    project_id varchar(64) not null,
    ecosystem varchar(32) not null,
    name varchar(512) not null,
    ticket_id uuid not null,
    ticket_slug varchar(64),
    opened_at timestamptz not null,
    closed_at timestamptz,
    unique (project_id, ecosystem, name)
);

-- Every version reported on the current ticket, so a version is never reported twice. reason is the
-- one it was reported for: MISSING, FAILED or PENDING.
create table mt_sbom_ticket_version (
    ticket_row uuid not null references mt_sbom_ticket (id),
    version varchar(255) not null,
    reason varchar(16) not null,
    reported_at timestamptz not null,
    primary key (ticket_row, version)
);

-- THE GC'S KEEP-SET IS A UNION, NEVER A REPLACE (qits-1172).
--
-- `mt_pin` is replaced by every scan, and a release scan reads the TAG. A tag can run ahead of main
-- and of the deployment, so on 2026-10-10 the pins main and the deployed qits-ci still used lost
-- their keep and the GC deleted those versions. `GET /pins` now serves the union of:
--   * `mt_pin` and `mt_gitlink_pin`, the last scan, as before;
--   * `mt_main_pin`, what main declared at its last scan — written by main scans only;
--   * the ledger pins of every release whose commit is not on main yet (`on_main` false);
--   * the ledger pins of every release qits-deployments serves or would roll back to.

-- FALSE UNTIL PROVEN. A main scan asks the git host whether the release's commit is an ancestor of
-- main and only a "yes" sets it. Every existing row starts false, so the first answer after this
-- migration keeps more, never less.
alter table mt_release add column on_main boolean not null default false;

-- What one repository's main declared at its last scan, in the shape `GET /pins` serves it:
-- INTERNAL registry pins and the npm pins its gitlinks reach. Replaced by each main scan only.
create table mt_main_pin (
    id uuid not null,
    repository varchar(255) not null,
    sha varchar(64) not null,
    ecosystem varchar(32) not null,
    name varchar(512) not null,
    version varchar(255) not null,
    manifest_path varchar(1024) not null,
    via varchar(1024),
    primary key (id)
);

create index idx_mt_main_pin_repository on mt_main_pin (repository);

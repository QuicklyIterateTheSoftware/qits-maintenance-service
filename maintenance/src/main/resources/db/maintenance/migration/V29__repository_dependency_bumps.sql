-- WHETHER A REPOSITORY IS OWED DEPENDENCY BUMPS AT ALL (qits-1784).
--
-- A repository decides it in its own tree: `dependency-bumps: true|false` in
-- .config/qits/maintenance.yml, and `repository.archived` in .config/qits/repository.yml, the
-- workspace tooling's file. The switch decides when present; absent, bumps are on unless the
-- repository is archived. Until this existed an archived repository went on receiving a
-- bump(dependencies) request for every internal release, and the only way to stop one was to take
-- its project's declaration away.
--
-- A scan writes the effective decision here, beside the status rather than as one: the row stays
-- OK and its pins are still read and kept (the artifact GC needs them), and the dispatcher skips a
-- row whose bumps are off. The reason is the short word the row reads back — `archived` or
-- `switch` — and null while the bumps are on.
--
-- True on every row before this column: nothing was off until a scan read the two files.
alter table mt_repository add column dependency_bumps boolean not null default true;
alter table mt_repository add column dependency_bumps_reason varchar(16);

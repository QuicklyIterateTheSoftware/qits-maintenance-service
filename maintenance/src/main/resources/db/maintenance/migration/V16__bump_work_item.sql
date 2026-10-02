-- The work item a BASELINES bump names in its commit subject (`chore(<work item>): ...`), as the
-- caller gave it. Null on every other bump, and on a BASELINES bump whose caller named none.
alter table mt_bump add column work_item varchar(64);

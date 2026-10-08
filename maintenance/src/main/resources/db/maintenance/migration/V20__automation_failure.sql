-- WHY AN AUTOMATION'S RUN WENT RED (qits-1116).
--
-- A red automation used to record only "the <label> run ended FAILED; its step log says why", so a
-- person had to open the run in qits-ci to learn which step failed and on what. The failing step is
-- now read off the run's steps when it ends: its index, the image it ran, its exit code and a short
-- excerpt of its log (the error lines, Maven's boilerplate dropped).
--
-- Nullable with no backfill: a row that ended before this recorded nothing, and every ending but a
-- red run leaves all four null.
alter table mt_bump add column failed_step_index int;
alter table mt_bump add column failed_step_image text;
alter table mt_bump add column failed_step_exit int;
alter table mt_bump add column failure_excerpt text;

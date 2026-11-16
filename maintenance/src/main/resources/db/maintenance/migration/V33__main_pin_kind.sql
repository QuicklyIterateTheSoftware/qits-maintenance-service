-- MAIN KEEPS ITS EXTERNAL IMAGE PINS TOO, SO THE MIRROR'S KEEP-SET IS A UNION (qits-2253).
--
-- `GET /pins/images` hands qits-mirror the external `docker` tags still pinned, and the mirror
-- evicts every mirrored tag it does not name. Like the GC's keep-set (V24, qits-1172) it is a
-- union of the last scan (`mt_pin`, which a release scan replaces with the TAG's pins) and what
-- main declared at its last main scan — so `mt_main_pin` now holds main's EXTERNAL docker pins
-- beside the INTERNAL pins it always held, and says which a row is.
--
-- A column rather than a sibling table: the rows have the same shape, are written by the same
-- main scan in one replace, and are deleted by the same retirements (`reconcileCatalog`,
-- `retireRenamed`). A second table would need every one of those paths twice, and a path that
-- forgot it would keep a retired repository's tags for ever.
--
-- Every existing row was written under the INTERNAL-only rule, so that is what it is. External
-- rows appear at each repository's next main scan; until then `mt_pin` still answers for them.
-- `GET /pins` serves INTERNAL rows only, exactly as before.
alter table mt_main_pin add column kind varchar(32) not null default 'INTERNAL';
alter table mt_main_pin alter column kind drop default;

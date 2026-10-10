-- GROUP BUMPS ARE GONE (qits-1133 R5).
--
-- R2 turned the upstream half of the pre-run on and stopped every path that wrote a
-- maintenance/<group> branch; R5 deletes that code. Nothing dispatches, polls or releases a GROUP row
-- any more, so this migration settles every row the old path could still have been waiting on, and
-- drops the one table that existed only for it.
--
-- * THE BUMP WINDOW. `mt_bump_window` (V10) was the group dispatcher's night: a row so a redeploy
--   mid-chain resumed it. The dispatcher that is left opens main-only release requests and is gated
--   by qits-ci's free slots and the quiet hours alone; it has no window to remember.
--
-- * A GROUP ROW STILL REQUESTED OR RUNNING. Nothing sends or follows one any more. It ends
--   NOTHING_TO_DO with the `converged` sentinel, the same ending R2 gave a row the cutover reached
--   before it was sent: whatever it would have written, the dependency-bump automation plans now, and
--   the legacy sweep deletes the branch.
--
-- * A GROUP ROW STILL OWED A RELEASE ASK (green, `release_request_id` null). Nothing asks any more;
--   `converged` closes the ask, as LegacyGroupBranchSweep does when it retires the branch.
--
-- GROUP rows themselves stay: they are history, listed by `GET /bumps`, and `BumpMode.GROUP` still
-- reads them.

drop table if exists mt_bump_window;

update mt_bump
   set status = 'NOTHING_TO_DO',
       finished_at = coalesce(finished_at, now()),
       release_request_id = 'converged',
       message = 'group bumps are retired (qits-1133); this one was never finished, and the'
           || ' dependency-bump automation plans these pins now'
 where mode = 'GROUP'
   and status in ('REQUESTED', 'RUNNING');

update mt_bump
   set release_request_id = 'converged'
 where mode = 'GROUP'
   and status in ('SUCCEEDED', 'NOTHING_TO_DO')
   and release_request_id is null;

-- WHAT A GROUP BUMP'S BRANCH WAS CUT FROM, AND WHAT IT WAS REBUILT OVER (qits-1081).
--
-- Every bump used to start `maintenance/<group>` from the repository's main branch. qits-projects
-- folds every released-but-unmerged tag into each release request, so a branch cut from main that
-- edits the pin line such a tag already moved conflicts in every fold until the tag is merged. A
-- bump now starts from `refs/tags/<version>` when the newest released tag is not on main, and sends
-- the branch's head as `replaceHead` when the branch has to be rebuilt on it.
--
-- `base_ref` is what the payload carried: the main branch's name, or the full tag ref. It is also
-- the dispatcher's once-per-tag guard — a CONFLICTED release is rebuilt on a given tag at most once,
-- and the newest bump row's base is how it knows it already was.
--
-- `replace_head` is the branch head the step was told it may replace, or null when the branch was
-- continued or started fresh.
--
-- Nullable with no backfill: a bump that predates this was cut from main and recorded nothing, and
-- an automation row records nothing here at all.
alter table mt_bump add column base_ref varchar(255);
alter table mt_bump add column replace_head varchar(64);

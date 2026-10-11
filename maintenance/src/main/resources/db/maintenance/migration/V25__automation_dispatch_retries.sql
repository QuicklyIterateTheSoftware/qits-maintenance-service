-- HOW OFTEN A DISPATCH FAILED FOR A REASON THAT CAN GO AWAY, AND WHEN TO TRY AGAIN (qits-1183).
--
-- qits-ci that is restarting, or that started no run because a lookup missed, ended the automation
-- FAILED at once, and a person had to re-run it. Such an answer now keeps the row REQUESTED and the
-- sweep sends it again after a growing wait, a bounded number of times.
--
-- `dispatch_attempts` counts the sends that failed that way. Null on every row before this column,
-- read as zero. `dispatch_retry_at` is the earliest time the sweep sends the row again; null means
-- now.
alter table mt_bump add column dispatch_attempts integer;
alter table mt_bump add column dispatch_retry_at timestamp(6) with time zone;

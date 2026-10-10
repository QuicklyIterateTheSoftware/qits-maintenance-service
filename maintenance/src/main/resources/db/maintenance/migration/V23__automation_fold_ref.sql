-- WHERE A RELEASE REQUEST IS FOLDED, AND WHAT A PERSON CALLS IT (qits-1158).
--
-- qits-projects folded every request onto `release/<uuid>`, and an own-branch automation built its
-- base ref from that rule. New requests fold onto `release/<qualifiedId>` (`qits-ci-service-rr-12`),
-- so the rule no longer holds: the row keeps the branch it was opened for.
--
-- `fold_ref` is the request's backing branch, as qits-projects named it in the trigger or in its
-- answer. Null on every row opened before this column, and on GROUP rows: those dispatch on the
-- old rule, `release/` + `release_request_id`, which is right for every request they can name.
--
-- `release_request_qualified_id` is the request's logical id, for the sentences a person reads.
-- Null when qits-projects did not say; the sentence then names the uuid, as before.
alter table mt_bump add column fold_ref varchar(255);
alter table mt_bump add column release_request_qualified_id varchar(255);

-- WHAT THIS SERVICE REMEMBERS ABOUT A RELEASE REQUEST (qits-1133).
--
-- A release request is qits-projects' row and this service holds nothing about it but the automation
-- rows its pre-run wrote. Two new decisions need a little more, and both are about the REQUEST rather
-- than about any one fold or kind:
--
-- * WHO OPENED IT, AND FOR WHAT. `opened` is true for a request this service asked for: a group
--   bump's release ask (`purpose` GROUP_BUMP, a maintenance/<group> branch) or the main-only LOWEST
--   request the dispatcher opens on the upstream path (`purpose` MAIN_ONLY). Only a MAIN_ONLY
--   request, and only while qits.maintenance.pre-run.upstream.enabled is on, has the
--   dependency-bump automation plan third-party (EXTERNAL) upgrades; every other request — a
--   person's, a group bump's — gets the platform's own releases only, and external upgrades stay a
--   person's press on the group door. A MAIN_ONLY request is also the one WITHDRAWN again when its
--   pre-run finds nothing to bump.
-- * HOW OFTEN AN UPSTREAM RELEASE RESTARTED IT. A library release re-plans the bump of every open
--   consumer request that is not READY; the commit re-folds the request and cancels its QA. A busy
--   upstream could starve a request of a verdict for ever, so after three such restarts without one
--   it is left alone until it has one. The counter is reset by a verdict.
--
-- One row per request, keyed by qits-projects' own id. Nullable everywhere a row may be written for
-- one reason only: a person's request has a counter and was never opened here.
create table mt_release_request (
    request_id varchar(64) primary key,
    repository varchar(255) not null,
    opened boolean not null default false,
    -- GROUP_BUMP or MAIN_ONLY; null on a request this service did not open. No check constraint, the
    -- stance every word column in this schema takes.
    purpose varchar(32),
    branch varchar(255),
    -- The pending set a MAIN_ONLY request was opened for (a JSON array of changes), frozen as a bump
    -- row's are: the dispatcher does not open a second request for the same set once its pre-run
    -- found nothing to write.
    changes text,
    opened_at timestamp with time zone,
    upstream_restarts int not null default 0,
    upstream_restarted_at timestamp with time zone,
    withdrawn_at timestamp with time zone,
    withdrawn_reason text,
    updated_at timestamp with time zone not null
);

create index mt_release_request_repository on mt_release_request (repository, opened_at);

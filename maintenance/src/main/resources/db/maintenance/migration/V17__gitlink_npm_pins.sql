-- THE NPM PINS A SERVICE REACHES THROUGH A GITLINK (qits-740).
--
-- Fifteen services build their frontend out of a submodule — `service/src/main/webui`, or
-- `src/main/webui` — and the lockfile AT THE GITLINKED COMMIT pins `@qits/*` versions that no pin
-- source reported. `mt_pin` holds the gitlink itself (its version is the commit sha) and the
-- frontend's own row holds what the frontend's MAIN pins; neither says what the service will
-- `npm ci` tomorrow. A GC that took such a version would break that build with E404 — which is what
-- happened on 2026-09-05. These two tables close that hole for `GET /pins` without making the read
-- of it call anybody.
--
-- THEY ARE NOT `mt_pin` ROWS, and that is deliberate: `mt_pin` says what a bump EDITS, and nothing
-- in the carrying repository has a line for these — the line is in the submodule's own tree, which
-- the gitlink bump moves wholesale. Writing them there would put them in groups, pending counts and
-- bump payloads that would then try to edit a file the repository does not hold.

-- ONE READ PER (submodule, sha), EVER. A commit's tree never changes, so a gitlink that has not
-- moved is never read again — and a tree with no internal npm pin at all is remembered as such,
-- which is why this is a table of its own rather than "the rows below exist". `pins` is every npm
-- pin the tree's root lock resolved, INTERNAL or not, as json (`[{name, version, manifestPath}]`):
-- which of them count as internal is configuration and is applied when a repository's rows are
-- written, so a changed scope list never needs a cache invalidation.
create table mt_gitlink_tree (
    id uuid not null,
    -- The submodule's repository name, which is a gitlink pin's `name` and a catalog name.
    submodule varchar(255) not null,
    -- The commit the tree was read at, as the parent's tree recorded it.
    sha varchar(64) not null,
    pins text not null,
    read_at timestamp(6) with time zone not null,
    primary key (id)
);

create unique index uq_mt_gitlink_tree on mt_gitlink_tree (submodule, sha);

-- WHAT `/pins` SERVES, per carrying repository: the INTERNAL npm pins each of its gitlinks reaches.
-- Replaced wholesale in the transaction that replaces that repository's `mt_pin` rows, and left
-- standing by everything that leaves those standing (an unreachable repository). A gitlink whose
-- tree could not be read at its new commit keeps the rows it had — so `sha` here is the commit the
-- rows were READ at, which is not necessarily the commit the gitlink pin names this minute.
create table mt_gitlink_pin (
    id uuid not null,
    -- The repository CARRYING the gitlink — the one whose build will fetch these versions.
    repository varchar(255) not null,
    -- The gitlink's path in that repository, `service/src/main/webui`.
    gitlink_path varchar(1024) not null,
    submodule varchar(255) not null,
    sha varchar(64) not null,
    -- `npm`, today the only ecosystem read through a gitlink. A column so a second is a writer
    -- change and not a migration.
    ecosystem varchar(32) not null,
    name varchar(512) not null,
    version varchar(255) not null,
    -- The lockfile's manifest inside the submodule, prefixed by the gitlink path:
    -- `service/src/main/webui/package.json`.
    manifest_path varchar(1024) not null,
    primary key (id)
);

create index idx_mt_gitlink_pin_repository on mt_gitlink_pin (repository);

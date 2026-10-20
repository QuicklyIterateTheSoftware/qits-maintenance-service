-- FINALIZATION IS THE GREEN LIGHT, NOT THE PUBLISH (qits-1472).
--
-- `mt_latest` used to move on qits-ci's `SoftwareRelease`, which qits-ci announces once a green
-- publish run and an `SCMRelease` exist for one (repository, version) — after RELEASED and BEFORE
-- FINALIZED. qits-projects finalizes a request only after its post-release gates (the publish run
-- green, the deployment active where the tag declares one) and only then merges the tag into main;
-- a RELEASED request that a newer one folds past ends OBSOLETE instead, and is never finalized at
-- all. Measured 2026-10-19/20: qits-workspaces 2026.1020.130544 was this service's latest for 71 s
-- before it passed its deploy gate, and qits-pact-consumer 2026.1019.81701 was adopted although its
-- request had gone OBSOLETE a second earlier. A version may be used once its request FINALIZED.
--
-- THE JOIN KEY IS (repository, version), and `repository` in both tables below is the SAME
-- spelling: qits-projects' repository ROW ID, verbatim off the wire. `SoftwareRelease.repository`
-- and `ReleaseRequestStateChanged.repoId` are that one uuid (checked on every live frame of the
-- reproduction), so storing what each frame says — rather than the catalog name
-- `MaintenanceStore.repositoryName` would translate it to — makes the join independent of whether
-- this inventory had scanned the repository when either frame arrived. Translating one side and not
-- the other, or translating at a moment the catalog row did not exist yet, would be a join that
-- silently never matches.

-- One row per finalized (repository, version), written by `bus/ReleaseFinalizedListener` on
-- `ReleaseRequestStateChanged` with `state=FINALIZED`. qits-projects often announces FINALIZED
-- TWICE with distinct event ids milliseconds apart (66 of 263 in one week), so the event-id dedup
-- the bus already does is not enough: the insert is `on conflict do nothing` against the unique
-- index below.
create table mt_finalized_release (
    id uuid not null,

    -- qits-projects' repository row id (`repoId`), never a catalog name. See the header.
    repository varchar(255) not null,

    -- The version the request released, and finalized.
    version varchar(255) not null,

    -- The publisher's moment (the frame's `occurredAt`, which is the payload's `changedAt`).
    finalized_at timestamp(6) with time zone not null,

    primary key (id)
);

create unique index uq_mt_finalized_release on mt_finalized_release (repository, version);

-- One row per artifact a `SoftwareRelease` announced, and whether it has been ADOPTED — moved into
-- `mt_latest` — yet. A row stays unadopted until a finalized row of its repository at or ABOVE its
-- version exists: "at or above" because an OBSOLETE request's tag is folded into the next one, and
-- qits-ci announces a `publish: if-changed` artifact only at a version where it changed, so an
-- artifact first published at a version that went OBSOLETE gets no announcement at the version that
-- finalizes. An artifact nothing ever finalizes past stays here unadopted for good; no expiry.
--
-- It is also the package → repository map the registry poll gates on: a package with at least one
-- row here is one whose repository is known, so `LatestResolver` admits only what that repository
-- finalized. A package with none keeps the ungated behaviour.
create table mt_published_artifact (
    id uuid not null,

    -- qits-projects' repository row id, verbatim (`SoftwareRelease.repository`). See the header.
    repository varchar(255) not null,

    -- `mt_pin`'s vocabulary, lower-cased as `Ecosystem.wireName` writes it: maven, npm or docker. A
    -- daemon binary has no `mt_latest` and so no row here.
    ecosystem varchar(32) not null,

    -- `mt_pin`'s spelling: `groupId:artifactId`, `@scope/name`, `qits/<name>`.
    name varchar(512) not null,

    version varchar(255) not null,

    -- The publisher's moment of the announcement.
    published_at timestamp(6) with time zone not null,

    -- When the artifact was adopted — handed to the forward-only `mt_latest` writer — or null while
    -- its repository has finalized nothing at or above its version.
    adopted_at timestamp(6) with time zone,

    primary key (id)
);

-- A redelivered `SoftwareRelease` aims at the same row.
create unique index uq_mt_published_artifact on mt_published_artifact (repository, ecosystem, name, version);

-- The poll gate's read: which repositories announced this package.
create index idx_mt_published_artifact_name on mt_published_artifact (ecosystem, name);

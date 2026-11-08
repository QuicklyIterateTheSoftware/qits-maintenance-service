-- THE PACTS A CONSUMER DECLARES, SO A PROVIDER CAN BE OFFERED THE FIRST PIN (qits-1952).
--
-- Maintenance only ever MOVED a pin a manifest already declared. A consumer's first published pact
-- jar `eu.wohlben.qits:<consumer>-pacts-<provider>` has no pin in the provider, so no bump ever added
-- one — and the provider's release gate drops an unpinned consumer's deployed pacts. Measured
-- 2026-11-07: eight declared and published pacts verified by nobody
-- (qits-maintenance-service-pacts-qits-system-service, qits-docs-service-pacts-qits-idp-service, …).
--
-- The consumer declares the edge in its own `.config/qits/release.yml`, `contracts.pacts`, keyed by
-- the provider's REPOSITORY name — the spelling qits-ci's CiContracts builds the coordinate from. A
-- scan of the consumer reads that file at the head it read the manifests at and REPLACES the
-- consumer's rows here wholesale, so an edge the consumer stopped declaring is gone at its next scan
-- and is never adopted (the qits-1765 rule). Nothing else writes the table.
--
-- One row per (consumer, provider). Pending adoption is computed from it, `mt_pin` and `mt_latest`,
-- never stored — see `pending/PactAdoptions`.
create table mt_pact_edge (
    id uuid not null,

    -- The consumer repository, as `mt_repository.name` spells it: the repository whose release.yml
    -- declares the pact, and whose release publishes the jar.
    consumer_repository varchar(255) not null,

    -- The provider repository, verbatim off the consumer's `contracts.pacts` key.
    provider_repository varchar(255) not null,

    primary key (id)
);

create unique index uq_mt_pact_edge on mt_pact_edge (consumer_repository, provider_repository);

-- The pending read: which consumers declare a pact against this provider.
create index idx_mt_pact_edge_provider on mt_pact_edge (provider_repository);

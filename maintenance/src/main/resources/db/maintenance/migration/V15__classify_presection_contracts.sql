-- PRE-qits-666 CONTRACT ROWS WERE NEVER TOLD APART FROM SOFTWARE (qits-621 follow-up to qits-668).
--
-- V14 added mt_artifact.section and left it null on every row announced before qits-ci's
-- SoftwareRelease started carrying it. The daily SBOM check (sbomcheck/SbomCheckService) counts a
-- null section as `artifacts`, which is right for an ordinary release predating the column — but a
-- provider's golden masters and a consumer's pacts never carry an SBOM at all, so the same null
-- makes every one of them MISSING on every run.
--
-- The coordinates below are not guessed: they are qits-ci's own derivation, the one rule in
-- CiContracts.coordinate (qits-ci-service, ci/src/main/java/eu/wohlben/qits/ci/control/
-- CiContracts.java):
--   golden-masters  maven  eu.wohlben.qits:<app>-golden-masters
--   golden-masters  npm    @qits/<short(app)>-golden-masters
--   pacts(provider) maven  eu.wohlben.qits:<app>-pacts-<provider>
--   pacts(provider) npm    @qits/<short(app)>-pacts-<provider>
-- `<app>` and `<short(app)>` are both `[a-z][a-z0-9-]*`, so the suffix/infix forms below are the
-- whole grammar regardless of which application or provider produced the row — matched by LIKE
-- rather than enumerated, because new applications adopt `contracts:` after this migration ships.
--
-- Only rows still null are touched; a section already set — by a later SoftwareRelease, or by this
-- migration running twice — is left exactly as it is.
update mt_artifact
   set section = 'contracts'
 where section is null
   and (
        (ecosystem = 'maven' and name like 'eu.wohlben.qits:%-golden-masters')
     or (ecosystem = 'maven' and name like 'eu.wohlben.qits:%-pacts-%')
     or (ecosystem = 'npm' and name like '@qits/%-golden-masters')
     or (ecosystem = 'npm' and name like '@qits/%-pacts-%')
   );

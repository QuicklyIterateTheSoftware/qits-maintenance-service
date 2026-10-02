-- DAEMON SBOMS ARE READ NOW, so the rows written terminal because they could not be are re-queued.
--
-- Since the qits CLI's version became a pom pin, every `daemon` SoftwareRelease leaves an
-- mt_artifact row (the GC derives a keep for the binary from it — control/CarriedDaemons). Those
-- rows were written FAILED at the write, with a fixed sentence in sbom_error, because the SBOM route
-- was then addressed through an Ecosystem and `daemon` is deliberately not one. qits-artifacts holds
-- a document for every one of them all the same: the route is keyed by the released artifact's TYPE
-- (`/artifacts/sboms/daemon/<name>/-/<version>`), and SbomClient now addresses a row by its stored
-- word. (This also corrects V3's comment on mt_artifact.ecosystem, which says a daemon is never a
-- row here; that comment cannot be edited — an applied migration's checksum must not move.)
--
-- ONLY the rows that rule wrote are moved: ecosystem `daemon` AND the rule's own sentence, matched
-- by its prefix (MaintenanceStore.DAEMON_SBOM_UNREAD, deleted in the same change). A daemon row that
-- FAILED for any other reason — none can exist today, but a FAILED is a real reading of a peer — is
-- left exactly as it is, and so is every other ecosystem.
--
-- PENDING is the outbox state, and nothing further is needed to drain it: RestartRecovery re-queues
-- every PENDING row at boot (this migration runs in the same boot, before it), and the hourly
-- SbomSweepSchedule is the belt behind that.
update mt_artifact
   set sbom_status = 'PENDING',
       sbom_error  = null
 where ecosystem = 'daemon'
   and sbom_status = 'FAILED'
   and sbom_error like 'a daemon binary''s bill of materials is not read%';

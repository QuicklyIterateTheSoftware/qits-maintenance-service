package eu.wohlben.qits.maintenance.api;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.DeskAdminParityRules;
import java.util.Set;

/**
 * Parity between {@code qits:admin-agent} and {@code qits:desk-admin} over this module's doors
 * (epic qits-1621, the task "qits-maintenance-service: admit qits:desk-admin wherever
 * qits:admin-agent is"). The epic's "Inventory" dossier page lists no refusal for this
 * service — including the release-request automation trigger and re-run, which stay closed to
 * {@code qits:desk} but open to {@code qits:admin-agent} and, now, {@code qits:desk-admin} — so
 * the refusal set is empty: every route admitting {@code qits:admin-agent} here must also admit
 * {@code qits:desk-admin}.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.maintenance.api",
    importOptions = ImportOption.DoNotIncludeTests.class)
class DeskAdminParityTest {

  private static final Set<String> REFUSED = Set.of();

  @ArchTest
  static final ArchRule PARITY = DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED);

  @ArchTest
  static final ArchRule REFUSALS = DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED);
}

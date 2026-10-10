package eu.wohlben.qits.maintenance.config;

import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import java.time.Duration;
import java.time.ZoneId;

/**
 * The shipped config with {@code qits.maintenance.pre-run.upstream.enabled} set one way or the other
 * — installed with {@code QuarkusMock} for one method and put back after it, the way {@code
 * BumpScheduleTest} flips its gate. Every other answer is the real bean's, so the rest of the
 * service runs as shipped.
 *
 * <p><b>The switch ships ON since the cutover (qits-1133 R2)</b>, so OFF is what the legacy
 * group-bump suites install: they pin the path an emergency {@code
 * QITS_MAINTENANCE_PRE_RUN_UPSTREAM_ENABLED=false} restores, until R5 removes it. A mock lives for
 * the whole run rather than the method, so every installer puts {@link #restore the real bean} back
 * in its {@code @AfterEach}.
 */
public final class UpstreamSwitch extends MaintenanceConfig {

  private final MaintenanceConfig real;

  private final boolean on;

  public UpstreamSwitch(MaintenanceConfig real) {
    this(real, true);
  }

  public UpstreamSwitch(MaintenanceConfig real, boolean on) {
    this.real = real;
    this.on = on;
  }

  /**
   * Installs the switch in this position over the bean behind {@code injected}, and answers that
   * bean — what {@link #restore} puts back.
   */
  public static MaintenanceConfig install(MaintenanceConfig injected, boolean on) {
    MaintenanceConfig real = ClientProxy.unwrap(injected);
    if (real instanceof UpstreamSwitch already) {
      real = already.real;
    }
    QuarkusMock.installMockForType(new UpstreamSwitch(real, on), MaintenanceConfig.class);
    return real;
  }

  /** The real bean back. */
  public static void restore(MaintenanceConfig real) {
    QuarkusMock.installMockForType(real, MaintenanceConfig.class);
  }

  @Override
  public boolean preRunUpstreamEnabled() {
    return on;
  }

  @Override
  public String environment() {
    return real.environment();
  }

  @Override
  public boolean scanEnabled() {
    return real.scanEnabled();
  }

  @Override
  public boolean bumpEnabled() {
    return real.bumpEnabled();
  }

  @Override
  public boolean bumpInternalAuto() {
    return real.bumpInternalAuto();
  }

  @Override
  public boolean bumpExternalAuto() {
    return real.bumpExternalAuto();
  }

  @Override
  public boolean bumpDispatchGated() {
    return real.bumpDispatchGated();
  }

  @Override
  public Duration bumpWindow() {
    return real.bumpWindow();
  }

  @Override
  public Duration bumpReleaseStateTtl() {
    return real.bumpReleaseStateTtl();
  }

  @Override
  public QuietHours bumpQuietHours() {
    return real.bumpQuietHours();
  }

  @Override
  public ZoneId timeZone() {
    return real.timeZone();
  }

  @Override
  public PinKind kindOf(ParsedPin pin) {
    return real.kindOf(pin);
  }

  @Override
  public PinKind kindOf(Ecosystem ecosystem, String name) {
    return real.kindOf(ecosystem, name);
  }
}

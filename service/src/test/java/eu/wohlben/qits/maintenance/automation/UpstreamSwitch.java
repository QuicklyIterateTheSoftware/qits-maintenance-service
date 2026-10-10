package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.config.QuietHours;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import java.time.Duration;
import java.time.ZoneId;

/**
 * The shipped config with {@code qits.maintenance.pre-run.upstream.enabled} turned ON — installed
 * with {@code QuarkusMock} for one method and put back after it, the way {@code BumpScheduleTest}
 * flips its gate. Every other answer is the real bean's, so the rest of the service runs as shipped.
 */
final class UpstreamSwitch extends MaintenanceConfig {

  private final MaintenanceConfig real;

  UpstreamSwitch(MaintenanceConfig real) {
    this.real = real;
  }

  @Override
  public boolean preRunUpstreamEnabled() {
    return true;
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

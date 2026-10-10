package eu.wohlben.qits.maintenance.automation;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** The {@code dependency-bump} switch on, which ships off (qits-1133); nothing else changed. */
public class DependencyBumpOn implements QuarkusTestProfile {
  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(DependencyBumpAutomation.SWITCH, "true");
  }
}

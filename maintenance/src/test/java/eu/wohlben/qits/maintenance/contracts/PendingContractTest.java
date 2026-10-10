package eu.wohlben.qits.maintenance.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Each call in {@link PendingContract}, reported as skipped with the provider state it waits for
 * (ticket qits-1149). Skipped, not passed: no pact binds these calls yet.
 */
class PendingContractTest {

  @TestFactory
  Stream<DynamicTest> everyCallWithoutAGoldenMasterWaitsForItsProviderState() {
    assertFalse(PendingContract.ROWS.isEmpty());
    return PendingContract.ROWS.stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.provider() + ": " + row.trigger().value() + ": " + row.operation(),
                    () -> Assumptions.abort(row.reason())));
  }
}

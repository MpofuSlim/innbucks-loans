package zw.co.innbucks.loans.core.getmore.simulator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorFaults.FaultKind;

/**
 * STAGING ONLY: a fault for the simulated store's next {@code times} calls of {@code operation} (1 when absent).
 */
public record SimFaultRequest(
        @NotNull(message = "operation is required") Operation operation,
        @NotNull(message = "kind is required") FaultKind kind,
        @Min(value = 1, message = "times must be 1 to " + SimulatorFaults.MAX_TIMES)
        @Max(value = SimulatorFaults.MAX_TIMES, message = "times must be 1 to " + SimulatorFaults.MAX_TIMES)
        Integer times) {

    /** How many calls the fault fires on: 1 when not given. */
    public int timesOrOne() {
        return times == null ? 1 : times;
    }
}

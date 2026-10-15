package zw.co.innbucks.loans.core.getmore.simulator;

import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorFaults.FaultKind;

import java.util.List;
import java.util.Map;

/** The faults queued for the simulated store's next calls, per operation, in firing order. */
public record SimFaultsResponse(Map<Operation, List<FaultKind>> pending, List<String> assumptions) {
}

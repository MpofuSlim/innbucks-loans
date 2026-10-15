package zw.co.innbucks.loans.core.getmore.simulator;

import lombok.Getter;

/** The simulated store refused, for {@link #getRefusal()}; nothing was applied. The message never holds a full code. */
@Getter
public class SimulatorRefusedException extends RuntimeException {

    private final SimulatorRefusal refusal;

    public SimulatorRefusedException(SimulatorRefusal refusal) {
        this(refusal, refusal.message());
    }

    public SimulatorRefusedException(SimulatorRefusal refusal, String message) {
        super(message);
        this.refusal = refusal;
    }
}

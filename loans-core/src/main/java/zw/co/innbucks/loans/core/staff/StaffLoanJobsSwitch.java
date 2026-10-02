package zw.co.innbucks.loans.core.staff;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Whether the Staff Grocery Loan jobs run in this process ({@link StaffLoanJobs}), for what reports on them: the offer
 * schedule tells the portal whether the weekly run fires by itself or has to be started there.
 */
@Component
public class StaffLoanJobsSwitch {

    private final boolean on;

    public StaffLoanJobsSwitch(Environment environment) {
        this.on = StaffLoanJobsCondition.enabled(environment);
    }

    public boolean on() {
        return on;
    }
}

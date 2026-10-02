package zw.co.innbucks.loans.core.staff;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Whether the Staff Grocery Loan jobs run ({@link StaffLoanJobs}): where the {@code scheduled-tasks} profile is on, as
 * every loans job does, or where {@value #PROPERTY} ({@code STAFF_LOANS_JOBS_ENABLED}) is true on its own.
 *
 * <p>The setting exists so the cell can run these jobs without {@code scheduled-tasks}, which also starts the jobs
 * that book with InnBucks (which pays) and lodge Ndasenda deductions (which cannot be taken back). These move no money:
 * the weekly offer run and its messages (FR-SGL-015), retrying staff messages left pending, and voucher upkeep. It is a
 * setting rather than a profile because a profile can arrive under several names and the cell keeps the paying jobs'
 * profile behind a guard ({@code ScheduledTasksKubernetesGuard}); this is one key in loans' own Secret.</p>
 *
 * <p>Only {@code true} switches them on: a blank value (a {@code KEY=} line) reads as off, never as a failed boot.</p>
 */
public class StaffLoanJobsCondition implements Condition {

    public static final String PROPERTY = "loans.staff-loans.jobs-enabled";

    static final String PROFILE = "scheduled-tasks";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return enabled(context.getEnvironment());
    }

    /** Whether the jobs run in {@code environment}: the one rule, shared with {@link StaffLoanJobsSwitch}. */
    static boolean enabled(Environment environment) {
        return environment.acceptsProfiles(Profiles.of(PROFILE))
                || Boolean.TRUE.equals(environment.getProperty(PROPERTY, Boolean.class));
    }
}

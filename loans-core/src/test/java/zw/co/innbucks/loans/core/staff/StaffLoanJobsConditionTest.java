package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Staff Grocery Loan jobs run where the scheduled-tasks profile is on, as before, or where STAFF_LOANS_JOBS_ENABLED
 * is true on its own; nowhere else. {@link StaffLoanJobsSwitch} reports the same answer.
 */
class StaffLoanJobsConditionTest {

    /** Stands in for a Staff Grocery Loan job. */
    @StaffLoanJobs
    static class Job {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Job.class, StaffLoanJobsSwitch.class);

    @Test
    @DisplayName("api only, no setting (the cell today) → off")
    void offByDefault() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("api"))
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(Job.class);
                    assertThat(context.getBean(StaffLoanJobsSwitch.class).on()).isFalse();
                });
    }

    @Test
    @DisplayName("STAFF_LOANS_JOBS_ENABLED=true without scheduled-tasks → on")
    void onWithTheSetting() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("api"))
                .withPropertyValues(StaffLoanJobsCondition.PROPERTY + "=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Job.class);
                    assertThat(context.getBean(StaffLoanJobsSwitch.class).on()).isTrue();
                });
    }

    @Test
    @DisplayName("the setting false → off")
    void offWhenFalse() {
        runner.withPropertyValues(StaffLoanJobsCondition.PROPERTY + "=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(Job.class));
    }

    @Test
    @DisplayName("the setting blank (a KEY= line in the Secret) → off, not a failed boot")
    void offWhenBlank() {
        runner.withPropertyValues(StaffLoanJobsCondition.PROPERTY + "=")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(Job.class);
                    assertThat(context.getBean(StaffLoanJobsSwitch.class).on()).isFalse();
                });
    }

    @Test
    @DisplayName("scheduled-tasks on, no setting → on, as before")
    void onWithTheProfile() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("api", "scheduled-tasks"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Job.class);
                    assertThat(context.getBean(StaffLoanJobsSwitch.class).on()).isTrue();
                });
    }
}

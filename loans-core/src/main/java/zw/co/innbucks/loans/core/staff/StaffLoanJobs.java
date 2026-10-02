package zw.co.innbucks.loans.core.staff;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Staff Grocery Loan job: it runs where the {@code scheduled-tasks} profile is on, or where
 * {@code STAFF_LOANS_JOBS_ENABLED} is true without it ({@link StaffLoanJobsCondition}). Only for jobs that move no
 * money; a job that pays, books or lodges stays {@code @Profile("scheduled-tasks")}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(StaffLoanJobsCondition.class)
public @interface StaffLoanJobs {
}

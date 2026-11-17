package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanArrearsReportJob;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatusJob;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationSweepJob;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunJob;
import zw.co.innbucks.loans.core.voucher.VoucherExpiryJob;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every scheduled job in loans is behind one of two gates, and the Staff Grocery Loan switch reaches only the jobs that
 * move no money. STAFF_LOANS_JOBS_ENABLED is a key in loans' own Secret, so a job that books with InnBucks (which pays)
 * or lodges with Ndasenda (which cannot be taken back) must never be put behind it: those stay
 * {@code @Profile("scheduled-tasks")}, where {@code ScheduledTasksKubernetesGuard} also stands. A job added with
 * neither gate would run in every process, the cell included, so it fails here too.
 *
 * <p>One job has a gate of its own: the cash payout's status check runs wherever Staff Grocery Loans are paid in cash
 * (loans.staff-loans.payout.method=CASH), never behind STAFF_LOANS_JOBS_ENABLED, which also starts the Monday offer
 * run. It only reads the bank's status; {@code StaffLoanPayoutWiringTest} pins that it cannot reach a paying class.
 */
class StaffLoanJobsWiringTest {

    /** The jobs STAFF_LOANS_JOBS_ENABLED may switch on. Add one only if it moves no money. */
    private static final Set<Class<?>> STAFF_LOAN_JOBS = Set.of(
            StaffOfferRunJob.class, StaffNotificationSweepJob.class, VoucherExpiryJob.class,
            StaffLoanArrearsReportJob.class);

    /** Read-only jobs gated on the cash payout setting itself. Add one only if it moves no money. */
    private static final Set<Class<?>> CASH_PAYOUT_JOBS = Set.of(StaffLoanPayoutStatusJob.class);

    @Test
    @DisplayName("the Staff Grocery Loan jobs are behind the switch, and not behind the scheduled-tasks profile alone")
    void staffLoanJobsUseTheSwitch() {
        assertThat(scheduledJobs()).as("the scan finds them").containsAll(STAFF_LOAN_JOBS);
        for (Class<?> job : STAFF_LOAN_JOBS) {
            assertThat(AnnotatedElementUtils.hasAnnotation(job, StaffLoanJobs.class)).as(job.getSimpleName()).isTrue();
            assertThat(AnnotatedElementUtils.hasAnnotation(job, Profile.class)).as(job.getSimpleName()).isFalse();
        }
    }

    @Test
    @DisplayName("the cash payout status check runs only where payouts are paid in cash, behind neither other gate")
    void cashPayoutJobsUseTheirOwnSwitch() {
        assertThat(scheduledJobs()).as("the scan finds them").containsAll(CASH_PAYOUT_JOBS);
        for (Class<?> job : CASH_PAYOUT_JOBS) {
            ConditionalOnProperty condition = AnnotatedElementUtils.findMergedAnnotation(job,
                    ConditionalOnProperty.class);
            assertThat(condition).as(job.getSimpleName() + " has no gate").isNotNull();
            assertThat(condition.prefix()).isEqualTo("loans.staff-loans.payout");
            assertThat(condition.name()).containsExactly("method");
            assertThat(condition.havingValue()).isEqualTo("CASH");
            assertThat(condition.matchIfMissing()).isFalse();
            assertThat(AnnotatedElementUtils.hasAnnotation(job, StaffLoanJobs.class)).as(job.getSimpleName()).isFalse();
            assertThat(AnnotatedElementUtils.hasAnnotation(job, Profile.class)).as(job.getSimpleName()).isFalse();
        }
    }

    @Test
    @DisplayName("every other scheduled job stays scheduled-tasks only, so the switch can never start a paying job")
    void everyOtherJobStaysBehindTheProfile() {
        Set<Class<?>> others = scheduledJobs().stream()
                .filter(job -> !STAFF_LOAN_JOBS.contains(job) && !CASH_PAYOUT_JOBS.contains(job))
                .collect(Collectors.toSet());

        assertThat(others).as("the booking, lodgement and saga jobs at least").hasSizeGreaterThanOrEqualTo(3);
        for (Class<?> job : others) {
            Profile profile = AnnotatedElementUtils.findMergedAnnotation(job, Profile.class);
            assertThat(profile).as(job.getSimpleName() + " has no gate").isNotNull();
            assertThat(profile.value()).as(job.getSimpleName()).containsExactly("scheduled-tasks");
            assertThat(AnnotatedElementUtils.hasAnnotation(job, StaffLoanJobs.class))
                    .as(job.getSimpleName() + " must not be behind STAFF_LOANS_JOBS_ENABLED").isFalse();
        }
    }

    /**
     * Every component in loans with a {@code @Scheduled} method or a {@link SchedulingConfigurer}, whatever its gate:
     * the scanner would otherwise apply the very conditions under test and find nothing.
     */
    private static Set<Class<?>> scheduledJobs() {
        AnnotationTypeFilter components = new AnnotationTypeFilter(Component.class);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                return components.match(reader, getMetadataReaderFactory());
            }
        };
        return scanner.findCandidateComponents("zw.co.innbucks.loans").stream()
                .map(definition -> ClassUtils.resolveClassName(definition.getBeanClassName(), null))
                .filter(type -> SchedulingConfigurer.class.isAssignableFrom(type)
                        || Arrays.stream(type.getDeclaredMethods())
                                .anyMatch(method -> method.isAnnotationPresent(Scheduled.class)))
                .collect(Collectors.toSet());
    }
}

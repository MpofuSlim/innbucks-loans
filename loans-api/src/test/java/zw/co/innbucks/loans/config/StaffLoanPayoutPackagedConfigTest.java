package zw.co.innbucks.loans.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.InnbucksParameters;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutDestination;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMetrics;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatusJob;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatusService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanProperties;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The Staff Grocery Loan payout settings as the packaged {@code application.yml} binds them: VOUCHER unless the cell's
 * Secret says otherwise, so a merge changes nothing; every key the docs name ({@code STAFF_LOANS_PAYOUT_*},
 * {@code INNBUCKS_TOKEN_TTL}) reaching its setting; and the read-only status job present only where loans are paid in
 * cash.
 */
class StaffLoanPayoutPackagedConfigTest {

    @EnableConfigurationProperties({StaffLoanProperties.class, InnbucksParameters.class})
    static class Settings {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(applicationYaml()))
            .withUserConfiguration(Settings.class, StaffLoanPayoutStatusJob.class)
            .withBean(StaffLoanPayoutStatusService.class, () -> mock(StaffLoanPayoutStatusService.class))
            .withBean(StaffLoanRepository.class, () -> mock(StaffLoanRepository.class))
            .withBean(StaffLoanPayoutMetrics.class, () -> new StaffLoanPayoutMetrics(new SimpleMeterRegistry()))
            .withBean(MarketTimeZone.class, () -> new MarketTimeZone("ZW"));

    @Test
    @DisplayName("packaged: VOUCHER, no destination, the defaults the docs give, and no status job")
    void packagedDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            StaffLoanProperties.Payout payout = context.getBean(StaffLoanProperties.class).getPayout();
            assertThat(payout.getMethod()).isEqualTo(StaffLoanPayoutMethod.VOUCHER);
            assertThat(payout.getDestination()).isNull();
            assertThat(payout.getProduct()).isBlank();
            assertThat(payout.effectiveLoanType()).isNull();
            assertThat(payout.getEmployerName()).isEqualTo("InnBucks MicroBank");
            assertThat(payout.getStaleClaimMinutes()).isEqualTo(30);
            assertThat(payout.getStatusCheckInterval()).isEqualTo(Duration.ofMinutes(3));
            assertThat(payout.getMinDaysBeforeDue()).isEqualTo(7);
            assertThat(payout.getNotBookedMinAge()).isEqualTo(Duration.ofHours(24));
            assertThat(payout.getFailedWatch()).isEqualTo(Duration.ofDays(14));
            assertThat(payout.getPaidStatuses()).containsExactly("SETTLED");
            assertThat(payout.getPendingStatuses()).containsExactly("PROCESSING", "PENDING", "APPROVED", "BOOKED");
            assertThat(context.getBean(InnbucksParameters.class).getTokenTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(context).doesNotHaveBean(StaffLoanPayoutStatusJob.class);
        });
    }

    @Test
    @DisplayName("each documented environment key reaches its setting; CASH starts the status job")
    void environmentKeys() {
        runner.withPropertyValues(
                        "STAFF_LOANS_PAYOUT_METHOD=CASH",
                        "STAFF_LOANS_PAYOUT_DESTINATION=BR_ACCOUNT",
                        "STAFF_LOANS_PAYOUT_PRODUCT=STAFFGROC",
                        "STAFF_LOANS_PAYOUT_LOAN_TYPE=CONSUMER_FINANCE",
                        "STAFF_LOANS_PAYOUT_EMPLOYER_NAME=InnBucks Microfinance Bank",
                        "STAFF_LOANS_PAYOUT_STALE_CLAIM_MINUTES=45",
                        "STAFF_LOANS_PAYOUT_STATUS_CHECK_INTERVAL=PT5M",
                        "STAFF_LOANS_PAYOUT_MIN_DAYS_BEFORE_DUE=10",
                        "STAFF_LOANS_PAYOUT_NOT_BOOKED_MIN_AGE=PT48H",
                        "STAFF_LOANS_PAYOUT_FAILED_WATCH=P30D",
                        "STAFF_LOANS_PAYOUT_PAID_STATUSES=SETTLED,DISBURSED",
                        "STAFF_LOANS_PAYOUT_PENDING_STATUSES=PROCESSING",
                        "INNBUCKS_TOKEN_TTL=PT8M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    StaffLoanProperties.Payout payout = context.getBean(StaffLoanProperties.class).getPayout();
                    assertThat(payout.getMethod()).isEqualTo(StaffLoanPayoutMethod.CASH);
                    assertThat(payout.getDestination()).isEqualTo(StaffLoanPayoutDestination.BR_ACCOUNT);
                    assertThat(payout.getProduct()).isEqualTo("STAFFGROC");
                    assertThat(payout.effectiveLoanType()).isEqualTo("CONSUMER_FINANCE");
                    assertThat(payout.getEmployerName()).isEqualTo("InnBucks Microfinance Bank");
                    assertThat(payout.getStaleClaimMinutes()).isEqualTo(45);
                    assertThat(payout.getStatusCheckInterval()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(payout.getMinDaysBeforeDue()).isEqualTo(10);
                    assertThat(payout.getNotBookedMinAge()).isEqualTo(Duration.ofHours(48));
                    assertThat(payout.getFailedWatch()).isEqualTo(Duration.ofDays(30));
                    assertThat(payout.getPaidStatuses()).containsExactly("SETTLED", "DISBURSED");
                    assertThat(payout.getPendingStatuses()).containsExactly("PROCESSING");
                    assertThat(context.getBean(InnbucksParameters.class).getTokenTtl())
                            .isEqualTo(Duration.ofMinutes(8));
                    assertThat(context).hasSingleBean(StaffLoanPayoutStatusJob.class);
                });
    }

    @Test
    @DisplayName("CASH with the destination or product unset refuses to start, naming the settings")
    void cashHalfSet() {
        runner.withPropertyValues("STAFF_LOANS_PAYOUT_METHOD=CASH").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(
                    "loans.staff-loans.payout.method CASH needs loans.staff-loans.payout.destination");
        });
    }

    private static PropertySource<?> applicationYaml() {
        try {
            List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            return documents.get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

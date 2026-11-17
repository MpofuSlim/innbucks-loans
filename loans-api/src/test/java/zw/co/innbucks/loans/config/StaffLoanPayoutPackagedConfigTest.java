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
 * The Staff Grocery Loan payout settings as the packaged {@code application.yml} binds them: the defaults the docs give
 * (product GETMOR, the bank's statuses), every key the docs name ({@code STAFF_LOANS_PAYOUT_*},
 * {@code INNBUCKS_TOKEN_TTL}) reaching its setting, and the read-only status job always present.
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
    @DisplayName("packaged: the defaults the docs give, and the status job")
    void packagedDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            StaffLoanProperties.Payout payout = context.getBean(StaffLoanProperties.class).getPayout();
            assertThat(payout.getProduct()).isEqualTo("GETMOR");
            assertThat(context.getBean(StaffLoanProperties.class).getMinimumDraw()).isEqualByComparingTo("20.00");
            assertThat(payout.getEmployerName()).isEqualTo("InnBucks MicroBank");
            assertThat(payout.getStaleClaimMinutes()).isEqualTo(30);
            assertThat(payout.getStatusCheckInterval()).isEqualTo(Duration.ofMinutes(3));
            assertThat(payout.getMinDaysBeforeDue()).isEqualTo(7);
            assertThat(payout.getNotBookedMinAge()).isEqualTo(Duration.ofHours(24));
            assertThat(payout.getFailedWatch()).isEqualTo(Duration.ofDays(14));
            assertThat(payout.getPaidStatuses()).containsExactly("SETTLED");
            assertThat(payout.getPendingStatuses()).containsExactly("PROCESSING", "PENDING", "APPROVED", "BOOKED");
            assertThat(payout.getBusinessLine()).isEqualTo("Services");
            assertThat(payout.getLoanPurpose()).isEqualTo("Groceries");
            assertThat(payout.isTestPlaceholders()).isFalse();
            assertThat(context.getBean(InnbucksParameters.class).getTokenTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(context).hasSingleBean(StaffLoanPayoutStatusJob.class);
        });
    }

    @Test
    @DisplayName("each documented environment key reaches its setting")
    void environmentKeys() {
        runner.withPropertyValues(
                        "STAFF_LOANS_PAYOUT_PRODUCT=STAFFGROC",
                        "STAFF_LOANS_PAYOUT_EMPLOYER_NAME=InnBucks Microfinance Bank",
                        "STAFF_LOANS_PAYOUT_STALE_CLAIM_MINUTES=45",
                        "STAFF_LOANS_PAYOUT_STATUS_CHECK_INTERVAL=PT5M",
                        "STAFF_LOANS_PAYOUT_MIN_DAYS_BEFORE_DUE=10",
                        "STAFF_LOANS_PAYOUT_NOT_BOOKED_MIN_AGE=PT48H",
                        "STAFF_LOANS_PAYOUT_FAILED_WATCH=P30D",
                        "STAFF_LOANS_PAYOUT_PAID_STATUSES=SETTLED,DISBURSED",
                        "STAFF_LOANS_PAYOUT_PENDING_STATUSES=PROCESSING",
                        "STAFF_LOANS_PAYOUT_BUSINESS_LINE=Retail",
                        "STAFF_LOANS_PAYOUT_LOAN_PURPOSE=Household",
                        "STAFF_LOANS_PAYOUT_TEST_PLACEHOLDERS=true",
                        "INNBUCKS_TOKEN_TTL=PT8M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    StaffLoanProperties.Payout payout = context.getBean(StaffLoanProperties.class).getPayout();
                    assertThat(payout.getProduct()).isEqualTo("STAFFGROC");
                    assertThat(payout.getEmployerName()).isEqualTo("InnBucks Microfinance Bank");
                    assertThat(payout.getStaleClaimMinutes()).isEqualTo(45);
                    assertThat(payout.getStatusCheckInterval()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(payout.getMinDaysBeforeDue()).isEqualTo(10);
                    assertThat(payout.getNotBookedMinAge()).isEqualTo(Duration.ofHours(48));
                    assertThat(payout.getFailedWatch()).isEqualTo(Duration.ofDays(30));
                    assertThat(payout.getPaidStatuses()).containsExactly("SETTLED", "DISBURSED");
                    assertThat(payout.getPendingStatuses()).containsExactly("PROCESSING");
                    assertThat(payout.getBusinessLine()).isEqualTo("Retail");
                    assertThat(payout.getLoanPurpose()).isEqualTo("Household");
                    assertThat(payout.isTestPlaceholders()).isTrue();
                    assertThat(context.getBean(InnbucksParameters.class).getTokenTtl())
                            .isEqualTo(Duration.ofMinutes(8));
                    assertThat(context).hasSingleBean(StaffLoanPayoutStatusJob.class);
                });
    }

    @Test
    @DisplayName("a blank product refuses to start, naming the setting")
    void blankProduct() {
        runner.withPropertyValues("STAFF_LOANS_PAYOUT_PRODUCT= ").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("loans.staff-loans.payout.product is required");
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

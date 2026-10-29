package zw.co.innbucks.loans.core.staff.loan;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import zw.co.innbucks.loans.core.borrower.BorrowerProperties;
import zw.co.innbucks.loans.core.config.HttpClientConfig;
import zw.co.innbucks.loans.core.disbursements.InnbucksParameters;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cash payout's settings (loans.staff-loans.payout) as the application binds them, and what refuses to start. The
 * default, VOUCHER, is the behaviour before the cash payout existed and needs nothing else set; CASH is refused at boot
 * unless everything a booking needs is there, so a misconfigured cell fails at deploy rather than on a borrower.
 */
class StaffLoanPayoutSettingsTest {

    private static final String[] INNBUCKS = {
            "innbucks.api-key=key", "innbucks.username=svc", "innbucks.password=pass",
            "innbucks.auth-endpoint=https://bank.test/auth/third-party",
            "innbucks.create-loan-account-endpoint=https://bank.test/bank/api/loan/apply/pre-approved",
            "innbucks.loan-inquiry-endpoint=https://bank.test/bank/api/loan/inquiry/{participantReference}"};

    private static final String[] CASH_TO_WALLET = {
            "loans.staff-loans.payout.method=CASH", "loans.staff-loans.payout.destination=VEENGU_WALLET",
            "loans.staff-loans.payout.product=STAFFGROC"};

    @Configuration
    @EnableConfigurationProperties({StaffLoanProperties.class, InnbucksParameters.class, BorrowerProperties.class,
            HttpClientConfig.class})
    @Import(StaffLoanPayoutBootCheck.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    private ListAppender<ILoggingEvent> logged;

    @BeforeEach
    void captureLog() {
        logged = new ListAppender<>();
        logged.start();
        ((Logger) LoggerFactory.getLogger(StaffLoanPayoutBootCheck.class)).addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        ((Logger) LoggerFactory.getLogger(StaffLoanPayoutBootCheck.class)).detachAppender(logged);
    }

    @Test
    @DisplayName("the defaults: VOUCHER, as before; nothing else needed, not even the InnBucks credentials")
    void defaultsAreVoucher() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            StaffLoanProperties.Payout payout = context.getBean(StaffLoanProperties.class).getPayout();
            assertThat(payout.getMethod()).isEqualTo(StaffLoanPayoutMethod.VOUCHER);
            assertThat(payout.cash()).isFalse();
            assertThat(payout.getDestination()).isNull();
            assertThat(payout.getProduct()).isEmpty();
            assertThat(payout.getLoanType()).isNull();
            assertThat(payout.effectiveLoanType()).isNull();
            assertThat(payout.getEmployerName()).isEqualTo("InnBucks MicroBank");
            assertThat(payout.getStaleClaimMinutes()).isEqualTo(30);
            assertThat(payout.getStatusCheckInterval()).isEqualTo(Duration.ofMinutes(3));
            assertThat(payout.getMinDaysBeforeDue()).isEqualTo(7);
            assertThat(payout.getNotBookedMinAge()).isEqualTo(Duration.ofHours(24));
            assertThat(payout.getFailedWatch()).isEqualTo(Duration.ofDays(14));
            assertThat(payout.getPaidStatuses()).containsExactly("SETTLED");
            assertThat(payout.getPendingStatuses()).containsExactly("PROCESSING", "PENDING", "APPROVED", "BOOKED");
        });
        assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("paid by voucher"));
    }

    @Test
    @DisplayName("blank values, as the cell's env file writes an unset key: a blank destination and loan type are unset")
    void blankValuesAreUnset() {
        runner.withPropertyValues("loans.staff-loans.payout.destination=", "loans.staff-loans.payout.loan-type=",
                "loans.staff-loans.payout.product=").run(context -> {
            assertThat(context).hasNotFailed();
            StaffLoanProperties.Payout payout = context.getBean(StaffLoanProperties.class).getPayout();
            assertThat(payout.getDestination()).isNull();
            assertThat(payout.effectiveLoanType()).isNull();
        });
    }

    @Test
    @DisplayName("a blank method is the default, VOUCHER: the binder leaves a blank enum unset, never CASH")
    void aBlankMethodIsVoucher() {
        runner.withPropertyValues("loans.staff-loans.payout.method=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StaffLoanProperties.class).getPayout().getMethod())
                    .isEqualTo(StaffLoanPayoutMethod.VOUCHER);
        });
        runner.withPropertyValues("loans.staff-loans.payout.method=CHEQUE")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("CASH to a wallet with the InnBucks credentials starts; its type is PERSONAL unless set")
    void cashToAWalletStarts() {
        runner.withPropertyValues(CASH_TO_WALLET).withPropertyValues(INNBUCKS).run(context -> {
            assertThat(context).hasNotFailed();
            StaffLoanProperties.Payout payout = context.getBean(StaffLoanProperties.class).getPayout();
            assertThat(payout.cash()).isTrue();
            assertThat(payout.effectiveLoanType()).isEqualTo("PERSONAL");
        });
        assertThat(messages(Level.INFO)).anyMatch(m -> m.contains("paid in cash into the borrower's InnBucks wallet")
                && m.contains("STAFFGROC") && m.contains("PERSONAL"));
        assertThat(messages(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("CASH without a destination, or without a product, refuses to start, naming the setting")
    void cashNeedsADestinationAndAProduct() {
        runner.withPropertyValues("loans.staff-loans.payout.method=CASH", "loans.staff-loans.payout.product=STAFFGROC")
                .withPropertyValues(INNBUCKS).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("loans.staff-loans.payout.method CASH needs"
                                    + " loans.staff-loans.payout.destination");
                });
        runner.withPropertyValues("loans.staff-loans.payout.method=CASH",
                        "loans.staff-loans.payout.destination=VEENGU_WALLET", "loans.staff-loans.payout.product= ")
                .withPropertyValues(INNBUCKS).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("CASH to an account needs its loan type set: what the bank books an account payout as is undecided")
    void cashToAnAccountNeedsALoanType() {
        runner.withPropertyValues("loans.staff-loans.payout.method=CASH",
                        "loans.staff-loans.payout.destination=BR_ACCOUNT", "loans.staff-loans.payout.product=STAFFGROC")
                .withPropertyValues(INNBUCKS).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("BR_ACCOUNT needs loans.staff-loans.payout.loan-type");
                });
        runner.withPropertyValues("loans.staff-loans.payout.method=CASH",
                        "loans.staff-loans.payout.destination=BR_ACCOUNT", "loans.staff-loans.payout.product=STAFFGROC",
                        "loans.staff-loans.payout.loan-type=CONSUMER_FINANCE")
                .withPropertyValues(INNBUCKS).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StaffLoanProperties.class).getPayout().effectiveLoanType())
                            .isEqualTo("CONSUMER_FINANCE");
                });
    }

    @Test
    @DisplayName("a loan type the bank does not take is refused")
    void anUnknownLoanTypeIsRefused() {
        runner.withPropertyValues("loans.staff-loans.payout.loan-type=MORTGAGE").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("loans.staff-loans.payout.loan-type must be PERSONAL or CONSUMER_FINANCE");
        });
    }

    @Test
    @DisplayName("CASH without the InnBucks credentials refuses to start, naming each one missing")
    void cashNeedsTheInnbucksCredentials() {
        runner.withPropertyValues(CASH_TO_WALLET).withPropertyValues(INNBUCKS)
                .withPropertyValues("innbucks.api-key=", "innbucks.password= ").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("innbucks.api-key (INNBUCKS_API_KEY)")
                            .hasMessageContaining("innbucks.password (INNBUCKS_PASSWORD)")
                            .hasMessageNotContaining("innbucks.username");
                });
    }

    @Test
    @DisplayName("CASH with an inquiry endpoint that does not name the reference refuses to start")
    void cashNeedsTheInquiryToNameTheReference() {
        runner.withPropertyValues(CASH_TO_WALLET).withPropertyValues(INNBUCKS)
                .withPropertyValues("innbucks.loan-inquiry-endpoint=https://bank.test/bank/api/loan/inquiry")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("innbucks.loan-inquiry-endpoint with {participantReference}");
                });
    }

    @Test
    @DisplayName("CASH with staging's test assertions on starts, and says at ERROR that anyone with the key is paid")
    void cashWithTestAssertionsIsShouted() {
        runner.withPropertyValues(CASH_TO_WALLET).withPropertyValues(INNBUCKS)
                .withPropertyValues("loans.borrower.test-assertions.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(messages(Level.ERROR)).anyMatch(m -> m.contains("test-assertions.enabled"));
    }

    @Test
    @DisplayName("VOUCHER never looks at the InnBucks credentials, or the test assertions")
    void voucherIgnoresTheBank() {
        runner.withPropertyValues("loans.borrower.test-assertions.enabled=true",
                "loans.staff-loans.payout.destination=BR_ACCOUNT").run(context -> assertThat(context).hasNotFailed());

        assertThat(messages(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("the bank's statuses: read in any case; paid must name one, and none may also be pending")
    void bankStatuses() {
        runner.withPropertyValues("loans.staff-loans.payout.paid-statuses=settled, Disbursed").run(context -> {
            StaffLoanProperties.Payout payout = context.getBean(StaffLoanProperties.class).getPayout();
            assertThat(payout.isPaidStatus("SETTLED")).isTrue();
            assertThat(payout.isPaidStatus(" disbursed ")).isTrue();
            assertThat(payout.isPaidStatus("PROCESSING")).isFalse();
            assertThat(payout.isPendingStatus("processing")).isTrue();
            assertThat(payout.isPaidStatus(null)).isFalse();
        });
        runner.withPropertyValues("loans.staff-loans.payout.paid-statuses=SETTLED,PROCESSING")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("loans.staff-loans.payout.paid-statuses=")
                .run(context -> assertThat(context).hasFailed());
        runner.run(context -> {
            StaffLoanProperties.Payout payout = context.getBean(StaffLoanProperties.class).getPayout();
            assertThat(payout.isFailedStatus("rejected")).isTrue();
            assertThat(payout.isFailedStatus("CANCELLED")).isTrue();
            assertThat(payout.isFailedStatus("FAILED")).isTrue();
            assertThat(payout.isFailedStatus("SETTLED")).isFalse();
        });
        runner.withPropertyValues("loans.staff-loans.payout.failed-statuses=REJECTED,SETTLED")
                .run(context -> assertThat(context).as("a paid status that is also failed").hasFailed());
        runner.withPropertyValues("loans.staff-loans.payout.failed-statuses=REJECTED,PENDING")
                .run(context -> assertThat(context).as("a pending status that is also failed").hasFailed());
    }

    @Test
    @DisplayName("CASH refuses to start with stale-claim-minutes shorter than a booking can take (two logins and two"
            + " posts at the http.client timeouts); VOUCHER does not look")
    void staleClaimMustOutlastABooking() {
        assertThat(StaffLoanPayoutBootCheck.longestBooking(new HttpClientConfig())).as("10s + 60s: 2 x 70s + 2 x 72s")
                .hasSeconds(284);
        runner.withPropertyValues(CASH_TO_WALLET).withPropertyValues(INNBUCKS)
                .withPropertyValues("loans.staff-loans.payout.stale-claim-minutes=4")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("stale-claim-minutes").hasMessageContaining("284s")
                        .hasMessageContaining("at least 5"));
        runner.withPropertyValues(CASH_TO_WALLET).withPropertyValues(INNBUCKS)
                .withPropertyValues("loans.staff-loans.payout.stale-claim-minutes=5")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(CASH_TO_WALLET).withPropertyValues(INNBUCKS)
                .withPropertyValues("loans.staff-loans.payout.stale-claim-minutes=5", "http.client.read-timeout=120000")
                .run(context -> assertThat(context).as("a longer read timeout needs a longer hold").hasFailed());
        runner.withPropertyValues("loans.staff-loans.payout.stale-claim-minutes=1")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("durations must be positive, the stale claim at least a minute, the due-date guard 0 to 31 days")
    void boundsAreChecked() {
        runner.withPropertyValues("loans.staff-loans.payout.failed-watch=PT0S")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("loans.staff-loans.payout.stale-claim-minutes=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("loans.staff-loans.payout.min-days-before-due=32")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("loans.staff-loans.payout.min-days-before-due=0",
                "loans.staff-loans.payout.status-check-interval=PT30S")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private List<String> messages(Level level) {
        return logged.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}

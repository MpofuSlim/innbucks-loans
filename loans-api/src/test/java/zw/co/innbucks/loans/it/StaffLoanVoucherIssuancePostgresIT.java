package zw.co.innbucks.loans.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.getmore.Deadline;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedTill;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorFaults;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCancellation;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanJourneyService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanRepository;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanView;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer.Outcome;
import zw.co.innbucks.loans.core.voucher.VoucherCodeSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issuing a Staff Grocery Loan's voucher at acceptance, and cancelling the loan, against real Postgres and the
 * simulated GetMore store: every row the issuance, the loan and the voucher go through must pass V37's constraints
 * (CALLING only with a claim, never more generates than allowed, a loan DISBURSED as GETMORE-SIM-, CANCELLED after
 * that payout), a lost answer to a generate must be adopted rather than minted twice, and a cancelled loan's coupon
 * must be gone at the store. The loans are written by SQL as acceptance leaves them; the rest is the application's
 * code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "it"})
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
@TestPropertySource(properties = {
        "loans.getmore.mode=SIMULATED",
        "loans.getmore.simulator.api-key=it-sim-3f9a1c7e5b2d4f6a8c0e1b3d5f7a9c2e4b6d8f0a1c3e",
        "loans.vouchers.code-hmac-key=it-voucher-hmac-key-0123456789abcdef0123456789",
        "loans.vouchers.code-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
class StaffLoanVoucherIssuancePostgresIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = ItPostgres.createDatabase("loans_getmore_issuance_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ItPostgres::user);
        registry.add("spring.datasource.password", ItPostgres::password);
    }

    @Autowired private StaffLoanVoucherIssuer issuer;
    @Autowired private StaffLoanCancellation cancellation;
    @Autowired private StaffLoanJourneyService journey;
    @Autowired private StaffLoanRepository loans;
    @Autowired private SimulatedTill till;
    @Autowired private SimulatorFaults faults;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearFaults() {
        faults.clear();
    }

    /** An accepted loan, as acceptance leaves it: AWAITING_DISBURSEMENT, its voucher's issuance queued. */
    private record Accepted(long loanId, long memberId, String reference) {
    }

    private Accepted accepted(String employee, String msisdn, String reference) {
        long member = StaffLoanRows.member(jdbc, employee, msisdn);
        long loan = StaffLoanRows.loan(jdbc, member, reference, "AWAITING_DISBURSEMENT", null, null);
        jdbc.update("UPDATE staff_loans SET due_date = current_date + 40 WHERE id = ?", loan);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                issuer.queue(loans.findById(loan).orElseThrow()).orElseThrow());
        return new Accepted(loan, member, reference);
    }

    private Outcome attempt(Accepted loan, StaffLoanVoucherIssuer.Trigger trigger) {
        return issuer.attempt(loan.reference(), trigger, Deadline.none());
    }

    private Map<String, Object> issuance(Accepted loan) {
        return jdbc.queryForMap("SELECT id, status, coupon_prefix, attempts, generate_calls, claimed_at, voucher_id,"
                + " last_outcome FROM staff_loan_voucher_issuances WHERE staff_loan_id = ?", loan.loanId());
    }

    private Map<String, Object> loanRow(Accepted loan) {
        return jdbc.queryForMap("SELECT status, disbursed_at, disbursement_reference, cancelled_at FROM staff_loans"
                + " WHERE id = ?", loan.loanId());
    }

    private int couponsOnPrefix(Accepted loan, boolean live) {
        return jdbc.queryForObject("SELECT count(*) FROM getmore_sim_coupons WHERE code LIKE ?"
                        + (live ? " AND deleted_at IS NULL" : ""), Integer.class,
                issuance(loan).get("coupon_prefix") + "%");
    }

    private String code(Accepted loan) {
        return jdbc.queryForObject("SELECT code FROM getmore_sim_coupons WHERE code LIKE ?", String.class,
                issuance(loan).get("coupon_prefix") + "%");
    }

    @Test
    @DisplayName("tried at acceptance: one coupon made, kept as the voucher, the loan DISBURSED as it and shown")
    void issuedAtAcceptance() {
        Accepted loan = accepted("E1064", "263771640064", "SGL-2026-000164");
        assertThat(issuance(loan)).containsEntry("status", "PENDING").containsEntry("generate_calls", 0);

        assertThat(issuer.attempt(loan.reference(), StaffLoanVoucherIssuer.Trigger.ACCEPTANCE,
                Deadline.after(java.time.Clock.systemUTC(), Duration.ofSeconds(10)))).isEqualTo(Outcome.ISSUED);

        Map<String, Object> issuance = issuance(loan);
        assertThat(issuance).containsEntry("status", "ISSUED").containsEntry("attempts", 1)
                .containsEntry("generate_calls", 1).containsEntry("last_outcome", "ISSUED");
        assertThat(issuance.get("claimed_at")).isNull();
        Map<String, Object> row = loanRow(loan);
        assertThat(row).containsEntry("status", "DISBURSED");
        assertThat(row.get("disbursed_at")).isNotNull();
        long voucherId = ((Number) issuance.get("voucher_id")).longValue();
        assertThat(row.get("disbursement_reference")).isEqualTo(jdbc.queryForObject(
                "SELECT disbursement_reference FROM vouchers WHERE id = ?", String.class, voucherId))
                .asString().startsWith("GETMORE-SIM-");
        assertThat(couponsOnPrefix(loan, true)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT coupon_expiry_set_at IS NOT NULL FROM vouchers WHERE id = ?",
                Boolean.class, voucherId)).as("the expiry told to the store").isTrue();
        assertThat(jdbc.queryForObject("SELECT expiration_date IS NOT NULL FROM getmore_sim_coupons WHERE code = ?",
                Boolean.class, code(loan))).isTrue();

        StaffLoanView view = journey.loanView(loan.memberId(), loan.reference());
        assertThat(view.status()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(view.voucherIssuance()).isNull();
        assertThat(view.voucher().store()).isEqualTo(VoucherCodeSource.GETMORE);
        assertThat(view.voucher().code()).as("the code exactly as GetMore made it").isEqualTo(code(loan));
        assertThat(view.statusMessage()).isEqualTo("Your voucher is ready to spend at GetMore Groceries.");
        assertThat(attempt(loan, StaffLoanVoucherIssuer.Trigger.OPERATOR)).isEqualTo(Outcome.CLOSED);
    }

    @Test
    @DisplayName("the generate's answer lost: waits PENDING, then adopts the coupon; never a second one")
    void lostAnswerAdopted() {
        Accepted loan = accepted("E1065", "263771640065", "SGL-2026-000165");
        faults.inject(Operation.GENERATE, SimulatorFaults.FaultKind.LOSE_RESPONSE, 1);

        assertThat(attempt(loan, StaffLoanVoucherIssuer.Trigger.ACCEPTANCE)).isEqualTo(Outcome.PENDING);
        assertThat(issuance(loan)).containsEntry("status", "PENDING").containsEntry("generate_calls", 1)
                .containsEntry("last_outcome", "OUTCOME_UNKNOWN");
        assertThat(couponsOnPrefix(loan, true)).as("made at the store, answer lost").isEqualTo(1);
        assertThat(journey.loanView(loan.memberId(), loan.reference()).voucherIssuance())
                .isEqualTo(StaffLoanView.VoucherIssuance.PENDING);

        assertThat(attempt(loan, StaffLoanVoucherIssuer.Trigger.OPERATOR)).isEqualTo(Outcome.ISSUED);
        assertThat(issuance(loan)).containsEntry("generate_calls", 1).containsEntry("attempts", 2);
        assertThat(couponsOnPrefix(loan, false)).isEqualTo(1);
        assertThat(loanRow(loan)).containsEntry("status", "DISBURSED");
    }

    @Test
    @DisplayName("a paid-out loan whose voucher was never used is cancelled with it, its coupon gone at the store")
    void cancelUntouched() {
        Accepted loan = accepted("E1066", "263771640066", "SGL-2026-000166");
        attempt(loan, StaffLoanVoucherIssuer.Trigger.ACCEPTANCE);

        StaffLoanCancellation.Result result = cancellation.cancel(loan.loanId(), "Accepted in error");

        assertThat(result.message()).isEqualTo("Cancelled. The voucher's coupon is withdrawn at GetMore.");
        Map<String, Object> row = loanRow(loan);
        assertThat(row).containsEntry("status", "CANCELLED");
        assertThat(row.get("disbursed_at")).as("V37 keeps a GETMORE- payout on a cancelled loan").isNotNull();
        assertThat(row.get("cancelled_at")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT status, coupon_withdrawn_at FROM vouchers WHERE staff_loan_id = ?",
                loan.loanId())).containsEntry("status", "CANCELLED").extractingByKey("coupon_withdrawn_at")
                .isNotNull();
        assertThat(couponsOnPrefix(loan, true)).isZero();
        assertThat(journey.loanView(loan.memberId(), loan.reference()).statusMessage())
                .isEqualTo("This loan was cancelled and its voucher withdrawn. Nothing is owed.");
    }

    @Test
    @DisplayName("a sale held at the till (ordered, not invoiced) keeps the loan: 409, the coupon still live")
    void cancelRefusedWhenHeld() {
        Accepted loan = accepted("E1067", "263771640067", "SGL-2026-000167");
        attempt(loan, StaffLoanVoucherIssuer.Trigger.ACCEPTANCE);
        till.sell(code(loan), new BigDecimal("20.00"), null, null, "GM-AVD", "GetMore Avondale", null, false);

        assertThatThrownBy(() -> cancellation.cancel(loan.loanId(), "Accepted in error"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("The voucher of SGL-2026-000167 has been used (USD 0.00 spent, USD 20.00 held at the"
                        + " till); a loan whose voucher was used cannot be cancelled.");

        assertThat(loanRow(loan)).containsEntry("status", "DISBURSED");
        assertThat(couponsOnPrefix(loan, true)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT held_amount FROM vouchers WHERE staff_loan_id = ?", BigDecimal.class,
                loan.loanId())).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("a loan still awaiting its voucher is cancelled, and its issuance, never claimed, ABANDONED")
    void cancelAwaiting() {
        Accepted loan = accepted("E1068", "263771640068", "SGL-2026-000168");

        StaffLoanCancellation.Result result = cancellation.cancel(loan.loanId(), "Accepted in error");

        assertThat(result.loan().status()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(result.message()).isEqualTo("Cancelled. The voucher's coupon is withdrawn at GetMore.");
        assertThat(issuance(loan)).containsEntry("status", "ABANDONED").containsEntry("last_outcome", "WITHDRAWN");
        assertThat(loanRow(loan).get("disbursed_at")).isNull();
        assertThat(attempt(loan, StaffLoanVoucherIssuer.Trigger.OPERATOR)).isEqualTo(Outcome.CLOSED);
    }

    @Test
    @DisplayName("Credit's retry of a loan accepted while GetMore was OFF makes its issuance and issues the voucher")
    void retryOfALoanAcceptedWhileOff() {
        long member = StaffLoanRows.member(jdbc, "E1069", "263771640069");
        long loanId = StaffLoanRows.loan(jdbc, member, "SGL-2026-000169", "AWAITING_DISBURSEMENT", null, null);
        jdbc.update("UPDATE staff_loans SET due_date = current_date + 40 WHERE id = ?", loanId);

        var result = issuer.retry(loanId, "accepted while GetMore was off", false);

        assertThat(result.issuance().status().name()).isEqualTo("ISSUED");
        assertThat(result.loan().status()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(result.loan().disbursementReference()).startsWith("GETMORE-SIM-");
    }
}

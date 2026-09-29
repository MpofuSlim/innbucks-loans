package zw.co.innbucks.loans.core.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.loan.Loan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A loan's payout used to be posted as one pair at the net payout: the receivable was understated
 * by the admin fee and the fee was never recorded. These run the real {@link LedgerService} over an
 * in-memory ledger, so what they read back is what would be in {@code ledger_entries}.
 */
class DisbursementLedgerTest {

    private final List<LedgerEntry> ledger = new ArrayList<>();
    private DisbursementLedger disbursementLedger;
    private Loan loan;

    @BeforeEach
    void setUp() {
        LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
        when(repository.save(any())).thenAnswer(inv -> {
            ledger.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repository.existsByTransactionRef(anyString())).thenAnswer(inv ->
                ledger.stream().anyMatch(e -> e.getTransactionRef().equals(inv.getArgument(0))));
        when(repository.findByTransactionRef(anyString())).thenAnswer(inv ->
                ledger.stream().filter(e -> e.getTransactionRef().equals(inv.getArgument(0))).toList());
        disbursementLedger = new DisbursementLedger(new LedgerService(repository), repository);

        // 500 borrowed, a 10% admin fee withheld, 450 paid out.
        loan = Loan.builder()
                .principal(new BigDecimal("500.00"))
                .feeAmount(new BigDecimal("50.00"))
                .disbursedAmount(new BigDecimal("450.00"))
                .disbursementReference("000000042")
                .build();
        loan.setId(42L);
    }

    private BigDecimal balance(LedgerAccount account) {
        return ledger.stream().filter(e -> e.getAccount() == account)
                .map(e -> e.getEntryType() == LedgerEntryType.DEBIT ? e.getAmount() : e.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<LedgerEntry> posting(String ref) {
        return ledger.stream().filter(e -> e.getTransactionRef().equals(ref)).toList();
    }

    @Test
    @DisplayName("the receivable is the principal, the payout leaves clearing, and the fee is income")
    void principalIsReceivableAndFeeIsIncome() {
        assertThat(disbursementLedger.recordPayout(loan, "job")).isTrue();

        assertThat(posting("DISB-000000042")).hasSize(3);
        assertThat(balance(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE)).isEqualByComparingTo("500.00");
        assertThat(balance(LedgerAccount.DISBURSEMENT_CLEARING)).isEqualByComparingTo("-450.00");
        assertThat(balance(LedgerAccount.FEE_INCOME)).isEqualByComparingTo("-50.00");
        assertThat(ledger).allSatisfy(e -> {
            assertThat(e.getLoanId()).isEqualTo(42L);
            assertThat(e.getCurrency()).isEqualTo("USD");
            assertThat(e.getCreatedBy()).isEqualTo("job");
        });
    }

    @Test
    @DisplayName("a loan with no fee posts a plain pair, never a zero fee leg")
    void noFeeIsAPlainPair() {
        loan.setPrincipal(new BigDecimal("450.00"));
        loan.setFeeAmount(BigDecimal.ZERO);

        disbursementLedger.recordPayout(loan, "job");

        assertThat(posting("DISB-000000042")).extracting(LedgerEntry::getAccount)
                .containsExactlyInAnyOrder(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerAccount.DISBURSEMENT_CLEARING);
        assertThat(balance(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE)).isEqualByComparingTo("450.00");
    }

    @Test
    @DisplayName("posting the same payout again changes nothing")
    void replayIsANoOp() {
        disbursementLedger.recordPayout(loan, "job");

        assertThat(disbursementLedger.recordPayout(loan, "saga-orchestrator")).isFalse();

        assertThat(ledger).hasSize(3);
    }

    @Test
    @DisplayName("a recovery payout is posted under its own reference, DISB-MD-<loan reference>")
    void recoveryPayoutHasItsOwnReference() {
        loan.setDisbursementReference("MD-000000042");

        disbursementLedger.recordPayout(loan, "manual-payout");

        assertThat(posting("DISB-MD-000000042")).hasSize(3);
        assertThat(posting("DISB-000000042")).isEmpty();
        assertThat(DisbursementLedger.transactionRef(loan)).isEqualTo("DISB-MD-000000042");
    }

    @Test
    @DisplayName("the booking's reference is the loan's own, whatever InnBucks echoed or if nothing was")
    void bookingReferenceIsTheLoans() {
        loan.setDisbursementReference(null);
        assertThat(DisbursementLedger.transactionRef(loan)).isEqualTo("DISB-000000042");
        loan.setDisbursementReference("INNBUCKS-7781");
        assertThat(DisbursementLedger.transactionRef(loan)).isEqualTo("DISB-000000042");
    }

    @Test
    @DisplayName("the fee booked is the principal less the payout; a stored fee that disagrees does not unbalance it")
    void feeIsDerivedFromThePrincipal() {
        loan.setFeeAmount(new BigDecimal("49.99"));

        disbursementLedger.recordPayout(loan, "job");

        assertThat(balance(LedgerAccount.FEE_INCOME)).isEqualByComparingTo("-50.00");
        assertThat(balance(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a loan with no principal recorded falls back to payout plus the stored fee")
    void noPrincipalUsesTheStoredFee() {
        loan.setPrincipal(null);

        disbursementLedger.recordPayout(loan, "job");

        assertThat(balance(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE)).isEqualByComparingTo("500.00");
        assertThat(balance(LedgerAccount.FEE_INCOME)).isEqualByComparingTo("-50.00");
    }

    @Test
    @DisplayName("a principal below the payout is booked at the payout, with no negative fee")
    void principalBelowPayoutIsBookedAtThePayout() {
        loan.setPrincipal(new BigDecimal("400.00"));

        disbursementLedger.recordPayout(loan, "job");

        assertThat(balance(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE)).isEqualByComparingTo("450.00");
        assertThat(balance(LedgerAccount.FEE_INCOME)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a paid loan with no payout amount is skipped, never refused")
    void noPayoutAmountIsSkipped() {
        loan.setDisbursedAmount(null);
        assertThat(disbursementLedger.recordPayout(loan, "job")).isFalse();
        loan.setDisbursedAmount(BigDecimal.ZERO);
        assertThat(disbursementLedger.recordPayout(loan, "job")).isFalse();

        assertThat(ledger).isEmpty();
    }

    @Test
    @DisplayName("a reversal mirrors the payout leg for leg, fee included, and nets every account to zero")
    void reversalMirrorsTheFeePosting() {
        disbursementLedger.recordPayout(loan, "job");

        String outcome = disbursementLedger.reverseBookingPayout(loan, "saga-orchestrator", "Compensation");

        assertThat(outcome).isEqualTo("ledger reversed via DISB-REV-000000042");
        assertThat(posting("DISB-REV-000000042")).hasSize(3);
        for (LedgerAccount account : LedgerAccount.values()) {
            assertThat(balance(account)).as(account.name()).isEqualByComparingTo("0");
        }
        assertThat(disbursementLedger.reverseBookingPayout(loan, "saga-orchestrator", "Compensation"))
                .isEqualTo("ledger reversal already posted (DISB-REV-000000042)");
        assertThat(ledger).hasSize(6);
    }

    @Test
    @DisplayName("a payout posted before the fee was recorded (a plain pair) reverses as that pair")
    void reversalMirrorsALegacyPair() {
        new LedgerService(mockBackedBy()).postDoubleEntry("DISB-000000042", 42L, "USD", "legacy", "saga-orchestrator",
                LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerAccount.DISBURSEMENT_CLEARING, new BigDecimal("450.00"));

        disbursementLedger.reverseBookingPayout(loan, "saga-orchestrator", "Compensation");

        assertThat(posting("DISB-REV-000000042")).hasSize(2);
        assertThat(balance(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE)).isEqualByComparingTo("0");
        assertThat(balance(LedgerAccount.DISBURSEMENT_CLEARING)).isEqualByComparingTo("0");
        assertThat(balance(LedgerAccount.FEE_INCOME)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("nothing posted for the booking means nothing to reverse, and a recovery payout is not the booking's")
    void nothingToReverse() {
        loan.setDisbursementReference("MD-000000042");
        disbursementLedger.recordPayout(loan, "manual-payout");

        assertThat(disbursementLedger.reverseBookingPayout(loan, "saga-orchestrator", "Compensation"))
                .isEqualTo("no ledger movement to reverse (funds never left clearing)");
        assertThat(ledger).hasSize(3);
    }

    /** A second repository over the same in-memory ledger, for writing a legacy posting directly. */
    private LedgerEntryRepository mockBackedBy() {
        LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
        when(repository.save(any())).thenAnswer(inv -> {
            ledger.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        return repository;
    }
}

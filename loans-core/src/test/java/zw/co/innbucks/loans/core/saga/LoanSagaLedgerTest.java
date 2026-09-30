package zw.co.innbucks.loans.core.saga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.ledger.DisbursementLedger;
import zw.co.innbucks.loans.core.ledger.LedgerAccount;
import zw.co.innbucks.loans.core.ledger.LedgerEntry;
import zw.co.innbucks.loans.core.ledger.LedgerEntryRepository;
import zw.co.innbucks.loans.core.ledger.LedgerEntryType;
import zw.co.innbucks.loans.core.ledger.LedgerService;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanPublicReferenceService;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.workflow.WorkAssignmentGuard;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The saga used to be the only thing that posted a payout, so a payout it never saw as DISBURSED
 * was never posted: a recovery payout of a loan whose saga had already compensated. The jobs that
 * record a payout now post it themselves; the saga's posting is a backstop that must agree with
 * them, and its compensation must undo the fee leg as well as the payout.
 */
class LoanSagaLedgerTest {

    private final List<LedgerEntry> ledger = new ArrayList<>();
    private LoanSagaRepository sagaRepository;
    private LoanSagaTransitionService transitions;
    private DisbursementLedger disbursementLedger;
    private Loan loan;

    @BeforeEach
    void setUp() {
        LedgerEntryRepository ledgerRepository = mock(LedgerEntryRepository.class);
        when(ledgerRepository.save(any())).thenAnswer(inv -> {
            ledger.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(ledgerRepository.existsByTransactionRef(anyString())).thenAnswer(inv ->
                ledger.stream().anyMatch(e -> e.getTransactionRef().equals(inv.getArgument(0))));
        when(ledgerRepository.findByTransactionRef(anyString())).thenAnswer(inv ->
                ledger.stream().filter(e -> e.getTransactionRef().equals(inv.getArgument(0))).toList());
        disbursementLedger = new DisbursementLedger(new LedgerService(ledgerRepository), ledgerRepository);

        LoanRepository loanRepository = mock(LoanRepository.class);
        AuditService auditService = mock(AuditService.class);
        sagaRepository = mock(LoanSagaRepository.class);
        LoanPublicReferenceService publicReferences = mock(LoanPublicReferenceService.class);
        when(publicReferences.next()).thenReturn("LN-2026-00042");
        transitions = new LoanSagaTransitionService(loanRepository, sagaRepository, disbursementLedger, auditService,
                publicReferences, new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class),
                        mock(WorkAssignmentGuard.class)));

        // 500 borrowed, 50 admin fee withheld, 450 paid out; booked and waiting on InnBucks.
        loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .loanAccountStatus(LoanAccountStatus.CREATED)
                .disbursementStatus(LoanDisbursementStatus.PENDING)
                .principal(new BigDecimal("500.00"))
                .feeAmount(new BigDecimal("50.00"))
                .disbursedAmount(new BigDecimal("450.00"))
                .disbursementReference("000000042")
                .build();
        loan.setId(42L);
        when(loanRepository.findById(42L)).thenReturn(Optional.of(loan));
    }

    private LoanSaga saga(LoanSagaState state) {
        LoanSaga saga = LoanSaga.builder().id(1L).loanId(42L).currentState(state)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).lastTransitionAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        when(sagaRepository.findByLoanId(42L)).thenReturn(Optional.of(saga));
        return saga;
    }

    private BigDecimal balance(LedgerAccount account) {
        return ledger.stream().filter(e -> e.getAccount() == account)
                .map(e -> e.getEntryType() == LedgerEntryType.DEBIT ? e.getAmount() : e.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("a payout its job already posted is not posted again when the saga sees it DISBURSED")
    void theBackstopAgreesWithTheWriter() {
        LoanSaga saga = saga(LoanSagaState.DISBURSEMENT_PENDING);
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        disbursementLedger.recordPayout(loan, "loan-disbursement-status-job");

        transitions.reconcileLoan(42L);

        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.DISBURSED);
        assertThat(ledger).hasSize(3).allSatisfy(e -> assertThat(e.getCreatedBy()).isEqualTo("loan-disbursement-status-job"));
        assertThat(loan.getPublicReference()).isEqualTo("LN-2026-00042");
    }

    @Test
    @DisplayName("a payout nobody posted is posted by the saga, fee and all")
    void theBackstopPostsAMissedPayout() {
        saga(LoanSagaState.DISBURSEMENT_PENDING);
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);

        transitions.reconcileLoan(42L);

        assertThat(balance(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE)).isEqualByComparingTo("500.00");
        assertThat(balance(LedgerAccount.DISBURSEMENT_CLEARING)).isEqualByComparingTo("-450.00");
        assertThat(balance(LedgerAccount.FEE_INCOME)).isEqualByComparingTo("-50.00");
        assertThat(ledger).allSatisfy(e -> assertThat(e.getTransactionRef()).isEqualTo("DISB-000000042"));
    }

    @Test
    @DisplayName("a recovery payout the saga reaches before its compensation is posted once, under DISB-MD-<ref>")
    void aRecoveryPayoutSeenByTheSagaIsPostedOnce() {
        saga(LoanSagaState.DISBURSEMENT_PENDING);
        loan.setDisbursementReference("MD-000000042");
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        disbursementLedger.recordPayout(loan, "manual-payout");

        transitions.reconcileLoan(42L);

        assertThat(ledger).hasSize(3)
                .allSatisfy(e -> assertThat(e.getTransactionRef()).isEqualTo("DISB-MD-000000042"));
    }

    @Test
    @DisplayName("a compensated saga never posts a later recovery payout: its writer is the only one that can")
    void aTerminalSagaPostsNothing() {
        LoanSaga saga = saga(LoanSagaState.COMPENSATED);
        loan.setDisbursementReference("MD-000000042");
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);

        transitions.reconcileLoan(42L);

        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.COMPENSATED);
        assertThat(ledger).isEmpty();
    }

    @Test
    @DisplayName("compensation undoes a posted payout leg for leg: receivable, clearing and fee income all back to zero")
    void compensationUndoesTheFeeToo() {
        disbursementLedger.recordPayout(loan, "loan-disbursement-status-job");
        LoanSaga saga = saga(LoanSagaState.DISBURSEMENT_PENDING);
        loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);

        transitions.reconcileLoan(42L);

        assertThat(saga.getCurrentState()).isEqualTo(LoanSagaState.COMPENSATED);
        assertThat(ledger).hasSize(6);
        for (LedgerAccount account : LedgerAccount.values()) {
            assertThat(balance(account)).as(account.name()).isEqualByComparingTo("0");
        }
    }
}

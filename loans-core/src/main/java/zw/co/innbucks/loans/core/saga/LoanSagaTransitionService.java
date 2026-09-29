package zw.co.innbucks.loans.core.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.ledger.DisbursementLedger;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanPublicReferenceService;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Transactional saga transitions, separated from the scheduled orchestrator so
 * {@code REQUIRES_NEW} goes through the Spring proxy (self-invocation would
 * silently skip transaction demarcation). Each loan's transition — including
 * its ledger posting and audit trail — commits or rolls back atomically and
 * independently of its neighbours.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanSagaTransitionService {

    static final String SYSTEM_ACTOR = "saga-orchestrator";

    private final LoanRepository loanRepository;
    private final LoanSagaRepository sagaRepository;
    private final DisbursementLedger disbursementLedger;
    private final AuditService auditService;
    private final LoanPublicReferenceService publicReferenceService;
    private final DeductionCancellationService deductionCancellationService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileLoan(Long loanId) {
        Loan loan = loanRepository.findById(loanId).orElse(null);
        if (loan == null) {
            return;
        }
        LoanSaga saga = sagaRepository.findByLoanId(loan.getId()).orElseGet(() -> newSaga(loan));

        if (saga.getCurrentState().isTerminal()) {
            return; // terminal states never regress
        }

        LoanSagaState observed = LoanSagaStateResolver.resolve(loan);
        if (observed == saga.getCurrentState()) {
            return; // no movement since last tick
        }

        LoanSagaState from = saga.getCurrentState();
        if (!from.canTransitionTo(observed)) {
            if (observed.name().equals(saga.getFlaggedAnomaly())) {
                return; // already reported; the loan has not moved since
            }
            log.warn("SAGA ANOMALY loan {}: illegal transition {} -> {} (recorded, not applied)",
                    loan.getId(), from, observed);
            auditService.recordTransition("LOAN_SAGA", String.valueOf(loan.getId()), SYSTEM_ACTOR,
                    "system", from.name(), observed.name(),
                    "ILLEGAL TRANSITION — flagged for operator review", correlationId(loan));
            // Remembered so the next tick does not report it again: once a minute, forever, it
            // buried the audit trail and the log under one stuck loan.
            saga.setFlaggedAnomaly(observed.name());
            sagaRepository.save(saga);
            return;
        }

        // ── FORWARD EXECUTION ───────────────────────────────────────────────
        saga.setFlaggedAnomaly(null);
        saga.setCurrentState(observed);
        saga.setLastTransitionAt(LocalDateTime.now(ZoneOffset.UTC));
        sagaRepository.save(saga);
        log.info("SAGA FORWARD loan {} [{}]: {} -> {}", loan.getId(), loan.getReference(), from, observed);
        auditService.recordTransition("LOAN_SAGA", String.valueOf(loan.getId()), SYSTEM_ACTOR,
                "system", from.name(), observed.name(), null, correlationId(loan));

        switch (observed) {
            case DISBURSED -> onDisbursed(loan);
            case DISBURSEMENT_FAILED -> {
                saga.setFailureReason(loan.getDisbursementStatusMessage());
                sagaRepository.save(saga);
                log.error("SAGA FORWARD EXECUTION HALTED loan {} [{}]: DISBURSEMENT_FAILED after {} attempt(s)"
                                + " — compensation routed. reason={}",
                        loan.getId(), loan.getReference(), loan.getDisbursementAttempts(),
                        loan.getDisbursementStatusMessage());
                // Compensation runs inside THIS transaction: the failure
                // transition and its compensation commit together; a crash
                // in between replays both on the next reconciler tick.
                applyCompensation(saga, loan);
            }
            default -> { /* intermediate states need no entry action */ }
        }
    }

    /** Retry entry point for sagas stuck in DISBURSEMENT_FAILED (e.g. crash before compensation committed). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensate(Long loanId) {
        LoanSaga saga = sagaRepository.findByLoanId(loanId).orElse(null);
        Loan loan = loanRepository.findById(loanId).orElse(null);
        if (saga == null || loan == null || saga.getCurrentState() != LoanSagaState.DISBURSEMENT_FAILED) {
            return;
        }
        applyCompensation(saga, loan);
    }

    /**
     * Entry action for DISBURSED: assign the public reference, and post the payout if its writer
     * did not. The jobs that record a loan as paid post it in the same transaction, so this is a
     * backstop for a loan paid before they did; idempotent per reference, so a loan its writer
     * already posted is a no-op here. A payout that reaches the saga only after it is terminal (a
     * recovery payout of a compensated loan) is posted by its writer alone.
     */
    private void onDisbursed(Loan loan) {
        disbursementLedger.recordPayout(loan, SYSTEM_ACTOR);

        if (loan.getPublicReference() == null) {
            loan.setPublicReference(publicReferenceService.next());
            loanRepository.save(loan);
        }
    }

    /**
     * COMPENSATION ROUTING for {@code DISBURSEMENT_FAILED} — keeps the books
     * consistent without ever mutating history:
     * <ol>
     *   <li><b>Ledger:</b> if the booking's payout was posted (a success later
     *       contradicted by the rail — the dangerous case), post its mirror image
     *       {@code DISB-REV-*}, leg for leg. The failed movement and its reversal
     *       both remain in the immutable history.</li>
     *   <li><b>Saga:</b> transition to COMPENSATED with a full audit trail.
     *       The loan's status columns are untouched — failed loans keep
     *       surfacing in the existing back-office workflow exactly as before.</li>
     *   <li><b>Payroll deduction:</b> a backstop flag. It was lodged with
     *       Ndasenda before credit and booking, and stays live on the customer's
     *       salary. The jobs that write the failure flag it first with the reason
     *       they know; this catches one no writer classified (a payout that
     *       exhausted its retries), and says it is in doubt.</li>
     * </ol>
     */
    private void applyCompensation(LoanSaga saga, Loan loan) {
        // FAILED is the booking's outcome (a recovery payout never writes it), so it is the
        // booking's posting that is reversed.
        String ledgerOutcome = disbursementLedger.reverseBookingPayout(loan, SYSTEM_ACTOR,
                "Compensation: reversal of failed disbursement");

        LoanSagaState from = saga.getCurrentState();
        saga.setCurrentState(LoanSagaState.COMPENSATED);
        saga.setCompensatedAt(LocalDateTime.now(ZoneOffset.UTC));
        saga.setLastTransitionAt(LocalDateTime.now(ZoneOffset.UTC));
        sagaRepository.save(saga);

        log.info("SAGA COMPENSATED loan {} [{}]: {} -> COMPENSATED ({})",
                loan.getId(), loan.getReference(), from, ledgerOutcome);
        auditService.recordTransition("LOAN_SAGA", String.valueOf(loan.getId()), SYSTEM_ACTOR, "system",
                from.name(), LoanSagaState.COMPENSATED.name(), ledgerOutcome, correlationId(loan));

        // The saga sees only disbursementStatus FAILED, not why: a timeout or 5xx lands there too, and
        // the customer may hold the loan, so it never asserts a definitive failure. Idempotent: a loan
        // the booking job already flagged keeps that job's reason.
        if (deductionCancellationService.markRequired(loan, DeductionCancellationService.REASON_BOOKING_IN_DOUBT,
                SYSTEM_ACTOR, "system")) {
            loanRepository.save(loan);
        }
    }

    private LoanSaga newSaga(Loan loan) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LoanSaga saga = sagaRepository.save(LoanSaga.builder()
                .loanId(loan.getId())
                .currentState(LoanSagaState.SSB_VERIFICATION_PENDING)
                .createdAt(now)
                .lastTransitionAt(now)
                .build());
        auditService.recordTransition("LOAN_SAGA", String.valueOf(loan.getId()), SYSTEM_ACTOR, "system",
                "NONE", LoanSagaState.SSB_VERIFICATION_PENDING.name(), "saga opened", correlationId(loan));
        return saga;
    }

    private String correlationId(Loan loan) {
        return "SAGA-" + loan.getId();
    }
}

package zw.co.reikan.loans.core.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Scheduled driver for the loan lifecycle saga:
 * <b>SSB (Ndasenda) Verification → Credit Assessment → Disbursement</b>.
 *
 * <p>Same reconciliation pattern (and {@code scheduled-tasks} profile) as the
 * existing approval/disbursement jobs — which remain the unchanged workers.
 * Each tick projects active loans onto the state machine via
 * {@link LoanSagaTransitionService}, whose per-loan {@code REQUIRES_NEW}
 * transactions give every loan an isolated blast radius. A crash mid-tick is
 * harmless: transitions are optimistic-locked and ledger postings idempotent,
 * so the next tick simply replays whatever did not commit.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Profile("scheduled-tasks")
public class LoanSagaOrchestrator {

    private final LoanRepository loanRepository;
    private final LoanSagaRepository sagaRepository;
    private final LoanSagaTransitionService transitionService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reconcile() {
        // 1. Advance (or open) sagas for recently active loans.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Loan> recentLoans = loanRepository.findByCreatedDateBetween(now.minusDays(30), now);
        for (Loan loan : recentLoans) {
            try {
                transitionService.reconcileLoan(loan.getId());
            } catch (Exception ex) {
                // One loan's saga trouble never stalls the fleet.
                log.error("Saga reconciliation failed for loan {} — continuing with the rest",
                        loan.getId(), ex);
            }
        }

        // 2. Drive any compensation that did not complete (crash between the
        //    DISBURSEMENT_FAILED transition and its compensation commit).
        for (LoanSaga saga : sagaRepository.findByCurrentState(LoanSagaState.DISBURSEMENT_FAILED)) {
            try {
                transitionService.compensate(saga.getLoanId());
            } catch (Exception ex) {
                log.error("Compensation retry failed for loan {} — will retry next tick",
                        saga.getLoanId(), ex);
            }
        }
    }
}

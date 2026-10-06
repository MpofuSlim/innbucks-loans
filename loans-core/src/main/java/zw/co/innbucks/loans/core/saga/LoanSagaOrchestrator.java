package zw.co.innbucks.loans.core.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.innbucks.loans.core.jobs.IdChunks;
import zw.co.innbucks.loans.core.loan.LoanStatusSnapshot;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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

    /** How far back a loan with no saga yet is picked up. An open saga is followed at any age. */
    static final int NEW_LOAN_WINDOW_DAYS = 30;

    private final LoanSagaRepository sagaRepository;
    private final LoanSagaTransitionService transitionService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reconcile() {
        // 1. Advance (or open) sagas. A loan whose saga is still open is followed however old it is:
        //    this used to look only at loans created in the last 30 days, so a loan that took longer
        //    (Ndasenda answering late, credit deciding late) dropped out of its saga mid-flow. Only
        //    status columns are read here, and only a loan that moved is loaded in full; this used
        //    to load 30 days of complete rows, documents and images included, every minute.
        //    A chunk of loans at a time, in id order (IdChunks), each with its sagas read for that chunk alone: this
        //    used to read every candidate and every open saga at once.
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(NEW_LOAN_WINDOW_DAYS);
        Set<LoanSagaState> terminal = LoanSagaState.terminalStates();
        IdChunks.forEach((after, chunk) -> sagaRepository.findReconcileCandidates(since, terminal, after, chunk),
                LoanStatusSnapshot::id, candidates -> {
                    Map<Long, LoanSaga> sagas = new HashMap<>();
                    for (LoanSaga saga : sagaRepository.findByLoanIdIn(
                            candidates.stream().map(LoanStatusSnapshot::id).toList())) {
                        sagas.put(saga.getLoanId(), saga);
                    }
                    for (LoanStatusSnapshot loan : candidates) {
                        if (!moved(loan, sagas.get(loan.id()))) {
                            continue;
                        }
                        try {
                            transitionService.reconcileLoan(loan.id());
                        } catch (Exception ex) {
                            // One loan's saga trouble never stalls the fleet.
                            log.error("Saga reconciliation failed for loan {} — continuing with the rest",
                                    loan.id(), ex);
                        }
                    }
                    return true;
                });

        // 2. Drive any compensation that did not complete (crash between the
        //    DISBURSEMENT_FAILED transition and its compensation commit). A chunk of sagas at a time.
        IdChunks.forEach((after, chunk) -> sagaRepository.findByCurrentStateAndIdGreaterThanOrderByIdAsc(
                LoanSagaState.DISBURSEMENT_FAILED, after, chunk), LoanSaga::getId, failed -> {
            for (LoanSaga saga : failed) {
                try {
                    transitionService.compensate(saga.getLoanId());
                } catch (Exception ex) {
                    log.error("Compensation retry failed for loan {} — will retry next tick",
                            saga.getLoanId(), ex);
                }
            }
            return true;
        });
    }

    /**
     * Whether the saga has anything to do for this loan: none opened yet, or its status columns now
     * resolve to a state the saga is not in and has not already reported as an anomaly. A terminal
     * saga never moves.
     */
    static boolean moved(LoanStatusSnapshot loan, LoanSaga saga) {
        if (saga == null) {
            return true;
        }
        if (saga.getCurrentState().isTerminal()) {
            return false;
        }
        LoanSagaState observed = LoanSagaStateResolver.resolve(loan);
        return observed != saga.getCurrentState() && !observed.name().equals(saga.getFlaggedAnomaly());
    }
}

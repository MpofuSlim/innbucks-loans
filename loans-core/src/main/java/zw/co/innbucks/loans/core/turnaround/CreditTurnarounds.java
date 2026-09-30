package zw.co.innbucks.loans.core.turnaround;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.workflow.SystemStage;
import zw.co.innbucks.loans.core.workflow.WorkItem;
import zw.co.innbucks.loans.core.workflow.WorkItemRepository;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;
import zw.co.innbucks.loans.core.workflow.WorkflowStageRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The credit turnaround of each loan in a view that is waiting on Credit, read in one pass for a page of loans. */
@Component
@RequiredArgsConstructor
public class CreditTurnarounds {

    private final WorkflowStageRepository workflowStageRepository;
    private final WorkItemRepository workItemRepository;

    /** By loan id; loans not waiting on Credit are absent. */
    @Transactional(readOnly = true)
    public Map<Long, CreditTurnaround> of(Collection<Loan> loans) {
        List<Loan> awaiting = loans.stream()
                .filter(loan -> CreditTurnaround.awaitingDecision(loan) && loan.creditQueueEnteredAt() != null)
                .toList();
        if (awaiting.isEmpty()) {
            return new HashMap<>();
        }
        String code = SystemStage.CREDIT_DECISION.name();
        WorkflowStage stage = workflowStageRepository.findById(code)
                .orElseThrow(() -> new IllegalStateException("No workflow stage " + code + " is configured"));
        Map<Long, LocalDateTime> entered = new HashMap<>();
        awaiting.forEach(loan -> entered.put(loan.getId(), loan.creditQueueEnteredAt()));
        Map<Long, WorkItem> items = new HashMap<>();
        workItemRepository.findByStageCodeAndLoanIdIn(code, entered.keySet()).stream()
                .filter(item -> item.getEnteredAt().equals(entered.get(item.getLoanId())))
                .forEach(item -> items.put(item.getLoanId(), item));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<Long, CreditTurnaround> turnarounds = new HashMap<>();
        awaiting.forEach(loan -> turnarounds.put(loan.getId(),
                CreditTurnaround.of(loan, stage, items.get(loan.getId()), now)));
        return turnarounds;
    }
}

package zw.co.reikan.loans.core.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.time.LocalDateTime;

@Slf4j
@RequiredArgsConstructor
@Service
public class InternalApprovalServiceImpl implements InternalApprovalService {
    private final LoanRepository loanRepository;
    private final AuthService authService;
    private final LoanMapper loanMapper;
    private final NotificationService notificationService;

    @Override
    public InternalApprovalResponse approveLoan(InternalApprovalRequest request, Long id) {

        Loan loan = loanRepository.findById(id).orElseThrow();

        if (request.getStatus() == InternalApprovalStatus.PENDING) {
            throw new RuntimeException("Invalid status");
        }

        if (loan.getInternalApprovalStatus() != InternalApprovalStatus.PENDING
                && loan.getInternalApprovalStatus() != null) {
            throw new RuntimeException("Loan already approved");
        }

        if (loan.getLoanApprovalStatus() != LoanApprovalStatus.APPROVED) {
            throw new RuntimeException(String.format("Loan with status %s cannot be approved",
                    loan.getLoanApprovalStatus()));
        }

        loan.setInternalApprovalStatus(request.getStatus());
        loan.setInternalApprovalDate(LocalDateTime.now());
        loan.setInternalApprovalComment(request.getComment());
        String username = authService.getLoggedInUsername();
        loan.setInternalApprovalBy(username);
        Loan savedLoan = loanRepository.save(loan);

        String loanReference = String.format("%09d", loan.getId());
        String text = String.format(request.getStatus() == InternalApprovalStatus.APPROVED ?
                        SmsMessages.APPROVED_LOAN : SmsMessages.REJECTED_LOAN,
                loanReference, loan.getDisbursedAmount(), request.getComment());

        notificationService.sendSms(loan.getMobileNumber(), text);

        InternalApprovalResponse response = new InternalApprovalResponse("Approved successfully");
        response.setLoan(loanMapper.fromLoan(savedLoan));
        return response;
    }
}

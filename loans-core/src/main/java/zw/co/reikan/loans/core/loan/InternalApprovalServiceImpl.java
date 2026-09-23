package zw.co.reikan.loans.core.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.exception.LoanApprovalException;
import zw.co.reikan.loans.core.exception.NotFoundException;
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

        Loan loan = loanRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Loan " + id + " not found"));

        if (request.getStatus() == InternalApprovalStatus.PENDING) {
            throw new LoanApprovalException("Invalid status: a decision must be APPROVED or REJECTED");
        }

        // Names the decision actually held — the old "Loan already approved" was
        // also the message for a loan that had been REJECTED.
        if (loan.getInternalApprovalStatus() != InternalApprovalStatus.PENDING
                && loan.getInternalApprovalStatus() != null) {
            throw new LoanApprovalException(String.format("Loan has already been %s",
                    loan.getInternalApprovalStatus().name().toLowerCase()));
        }

        if (loan.getLoanApprovalStatus() != LoanApprovalStatus.APPROVED) {
            throw new LoanApprovalException(String.format("Loan with status %s cannot be approved",
                    loan.getLoanApprovalStatus()));
        }

        loan.setInternalApprovalStatus(request.getStatus());
        loan.setInternalApprovalDate(LocalDateTime.now());
        loan.setInternalApprovalComment(request.getComment());
        String username = authService.getLoggedInUsername();
        loan.setInternalApprovalBy(username);
        Loan savedLoan = loanRepository.save(loan);

        // Reference and amount only. The comment is the reviewer's internal note,
        // stored above for staff; it used to be pasted into the customer's decline.
        String loanReference = String.format("%09d", loan.getId());
        String text = String.format(request.getStatus() == InternalApprovalStatus.APPROVED ?
                        SmsMessages.APPROVED_LOAN : SmsMessages.REJECTED_LOAN,
                loanReference, loan.getDisbursedAmount());

        notificationService.sendSms(loan.getMobileNumber(), text);

        // Names the decision recorded — a rejection used to answer "Approved successfully".
        InternalApprovalResponse response = new InternalApprovalResponse(
                request.getStatus() == InternalApprovalStatus.APPROVED
                        ? "Approved successfully" : "Rejected successfully");
        response.setLoan(loanMapper.fromLoan(savedLoan));
        return response;
    }
}

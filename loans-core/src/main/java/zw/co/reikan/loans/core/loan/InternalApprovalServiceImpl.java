package zw.co.reikan.loans.core.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.exception.LoanApprovalException;
import zw.co.reikan.loans.core.exception.NotFoundException;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.time.LocalDateTime;

import static zw.co.reikan.loans.core.merchant.MerchantService.maskAccountNumber;

@Slf4j
@RequiredArgsConstructor
@Service
public class InternalApprovalServiceImpl implements InternalApprovalService {

    static final String CREDIT_APPROVED = "CREDIT_APPROVED";

    private final LoanRepository loanRepository;
    private final AuthService authService;
    private final LoanMapper loanMapper;
    private final NotificationService notificationService;
    private final DeductionCancellationService deductionCancellationService;
    private final AuditService auditService;

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

        String username = authService.getLoggedInUsername();
        PayoutDestination payee = null;
        if (request.getStatus() == InternalApprovalStatus.APPROVED) {
            requireNotOriginator(loan, username);
            payee = requirePayee(loan);
            // Frozen with the decision: booking and any recovery payout pay this, not whatever the
            // merchant row says by the time the loan is booked.
            loan.setApprovedDisbursementType(payee.type());
            loan.setApprovedSettlementAccount(payee.merchantAccount());
        }

        loan.setInternalApprovalStatus(request.getStatus());
        loan.setInternalApprovalDate(LocalDateTime.now());
        loan.setInternalApprovalComment(request.getComment());
        loan.setInternalApprovalBy(username);
        if (request.getStatus() == InternalApprovalStatus.REJECTED) {
            // Lodged with Ndasenda before this decision (only an Ndasenda-APPROVED loan reaches it),
            // so the refusal leaves a live payroll deduction for a loan that will never be paid.
            deductionCancellationService.markRequired(loan, DeductionCancellationService.REASON_CREDIT_REJECTED,
                    username, DeductionCancellationService.PORTAL_CHANNEL);
        }
        Loan savedLoan = loanRepository.save(loan);
        if (payee != null) {
            auditApproval(savedLoan, username, payee);
        }

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

    /**
     * Maker-checker: whoever originated a loan cannot also be the one who approves it, since that one
     * person would then decide both who is paid and that they are paid. Any role can originate, and a
     * credit manager or admin originating one is ordinary; approving it takes somebody else. A refusal
     * is not held to it: rejecting pays nothing.
     */
    private static void requireNotOriginator(Loan loan, String approver) {
        boolean originated = StringUtils.equalsIgnoreCase(approver, loan.getCreatedBy())
                || (loan.getCreatedByUser() != null
                && StringUtils.equalsIgnoreCase(approver, loan.getCreatedByUser().getUsername()));
        if (originated) {
            throw new AccessDeniedException(String.format(
                    "Loan %09d was originated by %s, who cannot also approve it; another credit officer must",
                    loan.getId(), approver));
        }
    }

    /** Refused before anything is recorded: a loan with nowhere to pay would only fail at booking. */
    private static PayoutDestination requirePayee(Loan loan) {
        Merchant merchant = loan.getMerchant();
        PayoutDestination payee = PayoutDestination.live(merchant);
        String reference = String.format("%09d", loan.getId());
        if (payee.type() == null) {
            throw new LoanApprovalException(String.format(
                    "Loan %s has no merchant payout type, so there is nowhere to pay it", reference));
        }
        if (payee.paysMerchant() && StringUtils.isBlank(payee.merchantAccount())) {
            throw new LoanApprovalException(String.format(
                    "Merchant %s has no settlement account, so loan %s cannot be paid", merchant.getCompanyName(), reference));
        }
        if (!payee.paysMerchant() && StringUtils.isBlank(loan.getMobileNumber())) {
            throw new LoanApprovalException(String.format(
                    "Loan %s has no customer mobile number to pay", reference));
        }
        return payee;
    }

    private void auditApproval(Loan loan, String approver, PayoutDestination payee) {
        try {
            // Who approved, and the destination frozen with it: the account masked, as everywhere else.
            auditService.record(AuditLog.builder()
                    .eventType(CREDIT_APPROVED)
                    .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                    .actorId(approver).channelUsed(DeductionCancellationService.PORTAL_CHANNEL)
                    .detail("reference=" + loan.getReference() + " originator=" + loan.getCreatedBy()
                            + " payoutType=" + payee.type()
                            + (payee.paysMerchant() ? " settlementAccount=" + maskAccountNumber(payee.merchantAccount())
                            + " merchant=" + (loan.getMerchant() == null ? null : loan.getMerchant().getMerchantCode()) : "")
                            + " amount=" + loan.getDisbursedAmount())
                    .correlationId(loan.getReference()));
        } catch (Exception ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while
            // opening the transaction; the approval itself is saved and must stand.
            log.error("Audit of loan {} ({}) failed", loan.getId(), CREDIT_APPROVED, ex);
        }
    }
}

package zw.co.innbucks.loans.core.staff.loan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.Deadline;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.instrument.SigningContext;

/**
 * Accepting a Staff Grocery Loan in the SuperApp, end to end (D13): the acceptance commits first
 * ({@link StaffLoanJourneyService#accept}, which queues a GetMore voucher's issuance with the loan), then the voucher
 * is tried at once, with no transaction open and within {@code loans.getmore.issuance.inline-budget} between calls,
 * and the borrower is answered with the loan as it then stands. In the normal case that is DISBURSED with its voucher
 * and code; when GetMore is slow or unavailable it is AWAITING_DISBURSEMENT with the voucher "being prepared", and
 * the borrower's next read tries again. Not transactional: nothing is held while GetMore is called, and a GetMore
 * failure never undoes the acceptance.
 */
@Service
@RequiredArgsConstructor
public class StaffLoanAcceptance {

    private final StaffLoanJourneyService journeyService;
    private final StaffLoanVoucherIssuer voucherIssuer;
    private final GetMoreProperties properties;
    private final MarketTimeZone marketTimeZone;

    /**
     * Accepts the loan as {@link StaffLoanJourneyService#accept} does, then tries its GetMore voucher inline.
     *
     * @return the loan once its voucher has been tried: DISBURSED with it, or still waiting for it
     */
    public StaffLoanView accept(Long staffMemberId, AcceptStaffLoanRequest request, SigningContext signing) {
        StaffLoanView accepted = journeyService.accept(staffMemberId, request, signing);
        if (accepted.voucherIssuance() == null) {
            return accepted;   // no GetMore voucher was queued: GetMore is OFF, or the merchant is not a GetMore store
        }
        voucherIssuer.attempt(accepted.reference(), StaffLoanVoucherIssuer.Trigger.ACCEPTANCE,
                Deadline.after(marketTimeZone.clock(), properties.getIssuance().getInlineBudget()));
        return journeyService.loanView(staffMemberId, accepted.reference());
    }
}

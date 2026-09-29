package zw.co.innbucks.loans.core.disbursements;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import zw.co.innbucks.loans.core.loan.DeductionCancellationStatus;
import zw.co.innbucks.loans.core.loan.Loan;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A loan held as booked while InnBucks has not confirmed paying it: what an operator needs to
 * look it up with InnBucks. Identifiers and amounts only — no customer contact or ID details.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HeldBookingDto {

    private Long id;
    private String reference;
    private LoanAccountStatus loanAccountStatus;
    private LoanDisbursementStatus disbursementStatus;
    /** AMBIGUOUS when the booking call's outcome was never known; null when InnBucks accepted it. */
    private BookingFailureKind bookingFailureKind;
    /** When the inquiry first reported no loan under the reference; null if it never has. */
    private LocalDateTime bookingNotFoundAt;
    private LocalDateTime dateApproved;
    private BigDecimal disbursedAmount;
    private String disbursementStatusMessage;
    private DeductionCancellationStatus deductionCancellationStatus;

    public static HeldBookingDto from(Loan loan) {
        return HeldBookingDto.builder()
                .id(loan.getId())
                .reference(loan.getReference())
                .loanAccountStatus(loan.getLoanAccountStatus())
                .disbursementStatus(loan.getDisbursementStatus())
                .bookingFailureKind(loan.getBookingFailureKind())
                .bookingNotFoundAt(loan.getBookingNotFoundAt())
                .dateApproved(loan.getDateApproved())
                .disbursedAmount(loan.getDisbursedAmount())
                .disbursementStatusMessage(loan.getDisbursementStatusMessage())
                .deductionCancellationStatus(loan.getDeductionCancellationStatus())
                .build();
    }
}

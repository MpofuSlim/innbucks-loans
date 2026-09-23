package zw.co.reikan.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import zw.co.reikan.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What an operator needs to find a lodged deduction on Ndasenda's portal and cancel it. The EC
 * number keeps only its last 3 characters, as everywhere else the deduction is reported.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeductionCancellationDto {
    /** The loan id. */
    private Long id;
    /** The reference the deduction was lodged under at Ndasenda. */
    private String reference;
    private String ecNumber;
    /** The monthly amount lodged for deduction. */
    private BigDecimal instalmentLodged;
    private String batchNumber;
    /** Ndasenda's own id for the deduction, once it has answered. */
    private String ndasendaDeductionId;
    private String reason;
    private LocalDateTime requestedAt;
    private DeductionCancellationStatus status;
    private String note;
    private String cancelledBy;
    private LocalDateTime cancelledAt;

    public static DeductionCancellationDto from(Loan loan) {
        return DeductionCancellationDto.builder()
                .id(loan.getId())
                .reference(loan.getReference())
                .ecNumber(NdasendaLoanApprovalServiceImpl.maskEcNumber(loan.getEcNumber()))
                .instalmentLodged(loan.getGrossedMonthlyDeduction())
                .batchNumber(loan.getBatchNumber())
                .ndasendaDeductionId(loan.getApprovalReference())
                .reason(loan.getDeductionCancellationReason())
                .requestedAt(loan.getDeductionCancellationRequestedAt())
                .status(loan.getDeductionCancellationStatus())
                .note(loan.getDeductionCancellationNote())
                .cancelledBy(loan.getDeductionCancelledBy())
                .cancelledAt(loan.getDeductionCancelledAt())
                .build();
    }
}

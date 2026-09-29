package zw.co.innbucks.loans.core.ndasenda;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

import static zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl.maskEcNumber;

/**
 * A payroll-deduction batch this system lodged with SSB (through Ndasenda), as staff see it.
 * Amounts are in dollars like everywhere else in the API (Ndasenda's own are cents). What stays at
 * Ndasenda: its batch security token, and each deduction's national ID and full EC number.
 *
 * @param creationDate as Ndasenda reports it
 * @param deductions   only on a single batch; a list of batches leaves them out
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeductionBatchResponse(
        String id,
        DeductionBatchStatus status,
        String deductionCode,
        String creationDate,
        Integer recordsCount,
        BigDecimal totalAmount,
        List<Deduction> deductions) {

    /** @param reference the loan reference the deduction was lodged under */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Deduction(
            String id,
            String reference,
            String ecNumber,
            String firstName,
            String lastName,
            NdasendaDeductionType type,
            String startDate,
            String endDate,
            BigDecimal amount,
            NdasendaDeductionStatus status,
            String message) {

        static Deduction from(NdasendaDeduction deduction) {
            return new Deduction(deduction.getId(), deduction.getReference(), maskEcNumber(deduction.getEcNumber()),
                    deduction.getFirstName(), deduction.getLastName(), deduction.getType(),
                    deduction.getStartDate(), deduction.getEndDate(), dollars(deduction.getAmountInCents()),
                    deduction.getStatus(), deduction.getMessage());
        }
    }

    public static DeductionBatchResponse summary(NdasendaDeductionBatch batch) {
        return of(batch, null);
    }

    public static DeductionBatchResponse detail(NdasendaDeductionBatch batch) {
        List<NdasendaDeduction> deductions = batch.getDeductions() == null ? List.of() : batch.getDeductions();
        return of(batch, deductions.stream().map(Deduction::from).toList());
    }

    private static DeductionBatchResponse of(NdasendaDeductionBatch batch, List<Deduction> deductions) {
        return new DeductionBatchResponse(batch.getId(), batch.getStatus(), batch.getDeductionCode(),
                batch.getCreationDate(), batch.getRecordsCount(), dollars(batch.getTotalAmountInCents()), deductions);
    }

    private static BigDecimal dollars(Integer cents) {
        return cents == null ? null : BigDecimal.valueOf(cents, 2);
    }
}

package zw.co.innbucks.loans.core.staff.loan;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * The Staff Grocery Loan's terms as a borrower takes it up in the SuperApp. The partial draw (FR-SGL-012, OQ-03), the
 * due date (BRD 3.7's design conflict, OQ-02) and the unredeemed-voucher treatment (OQ-09) are open questions for the
 * business, so each is a setting with the working answer as its default.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "loans.staff-loans")
public class StaffLoanProperties {

    /**
     * The least a borrower may take (FR-SGL-012). An offer below it can only be taken in full.
     */
    @NotNull
    @DecimalMin(value = "0.01", message = "loans.staff-loans.minimum-draw must be at least 0.01")
    @Digits(integer = 17, fraction = 2, message = "loans.staff-loans.minimum-draw has at most 2 decimals")
    private BigDecimal minimumDraw = new BigDecimal("10.00");

    /**
     * The steps an amount below the full offer is chosen in (FR-SGL-012): a multiple of this. The full offer can always
     * be taken, whatever it is a multiple of.
     */
    @NotNull
    @DecimalMin(value = "0.01", message = "loans.staff-loans.draw-increment must be at least 0.01")
    @Digits(integer = 17, fraction = 2, message = "loans.staff-loans.draw-increment has at most 2 decimals")
    private BigDecimal drawIncrement = new BigDecimal("5.00");

    /**
     * The day of the month after acceptance the loan is due and collected from salary. The 20th, the day salary is
     * credited (BRD 3.7 recommends it over the 19th, which would put every loan a day in arrears before the money to
     * repay it exists). At most the 28th, so every month has it.
     */
    @Min(value = 1, message = "loans.staff-loans.repayment-day must be between 1 and 28")
    @Max(value = 28, message = "loans.staff-loans.repayment-day must be between 1 and 28")
    private int repaymentDay = 20;

    /** Days after the due date before an unpaid loan counts as in arrears (FR-SGL-013, FR-SGL-014). */
    @Min(value = 0, message = "loans.staff-loans.arrears-grace-days must be 0 or more")
    @Max(value = 60, message = "loans.staff-loans.arrears-grace-days must be at most 60")
    private int arrearsGraceDays = 0;

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "loans.staff-loans.currency must be a 3-letter ISO code, such as USD")
    private String currency = "USD";

    /** Where the voucher can be spent, as the borrower is told (FR-SGL-026). */
    @NotBlank
    private String merchantName = "GetMore Groceries";

    /** OQ-09, until Finance and Legal decide. */
    @NotNull
    private UnredeemedVoucherTreatment unredeemedVoucherTreatment = UnredeemedVoucherTreatment.DEBT_STANDS;

    @AssertTrue(message = "loans.staff-loans.minimum-draw must be a multiple of loans.staff-loans.draw-increment")
    public boolean isMinimumDrawAStep() {
        return minimumDraw == null || drawIncrement == null || drawIncrement.signum() <= 0
                || minimumDraw.remainder(drawIncrement).signum() == 0;
    }
}

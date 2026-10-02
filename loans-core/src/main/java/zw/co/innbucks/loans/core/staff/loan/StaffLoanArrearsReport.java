package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The daily arrears and exception report for Credit and Human Capital (FR-SGL-045): every paid-out Staff Grocery Loan
 * not recovered in full, as loans' own records stand. That is one past its due date and still unpaid, one written off,
 * or one whose borrower is no longer ACTIVE on the register, due or not. Until the core banking collection reports
 * recoveries, a loan still DISBURSED after its due date is taken as not recovered.
 *
 * @param asOf           the market day it describes
 * @param graceDays      days after the due date before an unpaid loan counts as in arrears
 * @param escalationDays days after the due date before an unpaid loan is escalated to Credit (BRD 3.8)
 * @param totals         per currency
 * @param lines          most days past due first
 */
public record StaffLoanArrearsReport(LocalDate asOf, LocalDateTime generatedAt, int graceDays, int escalationDays,
                                     List<Totals> totals, List<Line> lines) {

    /**
     * One currency's figures, each a count of the lines it names.
     *
     * @param overdue           past their due date, written off or not
     * @param inArrears         past their due date and the grace, or written off
     * @param escalated         unpaid for {@code escalationDays} or more after their due date, and not written off
     * @param employmentFlagged whose borrower is no longer ACTIVE on the register
     */
    public record Totals(String currency, int loans, BigDecimal outstanding, int overdue, int inArrears,
                         int escalated, int writtenOff, BigDecimal writtenOffOutstanding, int employmentFlagged,
                         List<BucketTotals> buckets) {
    }

    public record BucketTotals(StaffLoanArrearsBucket bucket, int loans, BigDecimal outstanding) {
    }

    /**
     * One loan.
     *
     * @param fullName         the borrower as the register held them when they accepted it
     * @param department       their department on the register now
     * @param employmentStatus their status on the register now
     * @param msisdn           masked
     * @param outstanding      what is owed, as loans knows it
     * @param daysPastDue      0 on or before the due date
     * @param inArrears        past its due date and the grace, or written off
     * @param escalated        unpaid for the escalation days or more after its due date, and not written off
     * @param employmentFlag   set when the borrower is no longer ACTIVE
     */
    public record Line(Long staffLoanId, String reference, String employeeNumber, String fullName, String department,
                       StaffEmploymentStatus employmentStatus, String grade, String msisdn, String merchantCode,
                       StaffLoanStatus status, String currency, BigDecimal amount, BigDecimal outstanding,
                       LocalDateTime disbursedAt, LocalDate dueDate, long daysPastDue, StaffLoanArrearsBucket bucket,
                       boolean inArrears, boolean escalated, StaffLoanEmploymentFlag employmentFlag) {
    }
}

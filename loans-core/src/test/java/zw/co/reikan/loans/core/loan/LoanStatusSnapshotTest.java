package zw.co.reikan.loans.core.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what "a pending loan application" means for the duplicate check: every
 * status combination the jobs leave a loan in, and whether it still blocks a
 * second application. The old check blocked on {@code NEW} only, so a loan
 * lodged with Ndasenda ({@code PROCESSING}) a minute earlier let a second one
 * through — two stop orders on one salary.
 */
class LoanStatusSnapshotTest {

    @ParameterizedTest(name = "{0} / credit {1} / account {2} / disbursement {3}")
    @DisplayName("an application that has not reached an outcome blocks another")
    @CsvSource(nullValues = "null", value = {
            // Waiting for LoanApprovalServiceJob to lodge it.
            "NEW,        PENDING,  PENDING, PENDING",
            // Lodged with Ndasenda, awaiting the deduction response.
            "PROCESSING, PENDING,  PENDING, PENDING",
            // SSB approved; credit has not decided.
            "APPROVED,   PENDING,  PENDING, PENDING",
            "APPROVED,   null,     null,    null",
            // Credit approved; InnBucks account, then disbursement, under way.
            "APPROVED,   APPROVED, PENDING, PENDING",
            "APPROVED,   APPROVED, CREATED, PENDING",
            "APPROVED,   APPROVED, null,    null",
    })
    void inFlight(LoanApprovalStatus approval, InternalApprovalStatus credit,
                  LoanAccountStatus account, LoanDisbursementStatus disbursement) {
        assertThat(new LoanStatusSnapshot(1L, approval, credit, account, disbursement).isInFlight()).isTrue();
    }

    @ParameterizedTest(name = "{0} / credit {1} / account {2} / disbursement {3}")
    @DisplayName("an application with an outcome does not block another")
    @CsvSource(nullValues = "null", value = {
            // Refused by SSB, or the lodging failed.
            "REJECTED,   PENDING,  PENDING, PENDING",
            "FAILED,     PENDING,  PENDING, PENDING",
            // Refused by credit.
            "APPROVED,   REJECTED, PENDING, PENDING",
            // InnBucks loan account could not be created.
            "APPROVED,   APPROVED, FAILED,  FAILED",
            // Disbursement failed for good.
            "APPROVED,   APPROVED, CREATED, FAILED",
            // Disbursed: a repaying loan is a separate rule, not this check's.
            "APPROVED,   APPROVED, CREATED, SUCCESS",
            // Money out, even with an approval column that never caught up.
            "PROCESSING, PENDING,  PENDING, SUCCESS",
            // Nothing sets PAID, and no job picks up a loan with no approval status.
            "PAID,       APPROVED, CREATED, PENDING",
            "null,       null,     null,    null",
    })
    void notInFlight(LoanApprovalStatus approval, InternalApprovalStatus credit,
                     LoanAccountStatus account, LoanDisbursementStatus disbursement) {
        assertThat(new LoanStatusSnapshot(1L, approval, credit, account, disbursement).isInFlight()).isFalse();
    }
}

package zw.co.reikan.loans.core.bulk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.reikan.loans.core.loan.BankingDetail;
import zw.co.reikan.loans.core.loan.LoanRequest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The BULK_ITEM_REJECTED audit row's payload hash must still fingerprint the WHOLE item,
 * including the fields {@link LoanRequest#toString()} now leaves out for the log's sake.
 */
class BulkLoanPayloadHashTest {

    private static LoanRequest application(String nationalId, String payslip, String accountNumber) {
        BankingDetail bankingDetail = new BankingDetail();
        bankingDetail.setAccountNumber(accountNumber);
        return LoanRequest.builder()
                .amount(new BigDecimal("500.00"))
                .ecnumber("1234567A")
                .tenor(12)
                .dateOfBirth(LocalDate.of(1990, 1, 31))
                .nationalId(nationalId)
                .payslipPicture(payslip)
                .bankingDetail(bankingDetail)
                .build();
    }

    @Test
    @DisplayName("the same application hashes the same; a SHA-256 hex digest")
    void deterministic() {
        String hash = BulkLoanIngestionService.payloadHash(application("63-123456A63", "iVBORw0K", "110022"));

        assertThat(hash).matches("[0-9a-f]{64}")
                .isEqualTo(BulkLoanIngestionService.payloadHash(application("63-123456A63", "iVBORw0K", "110022")));
    }

    @Test
    @DisplayName("applications differing only in a field toString omits still hash differently")
    void coversFieldsTheLogOmits() {
        String base = BulkLoanIngestionService.payloadHash(application("63-123456A63", "iVBORw0K", "110022"));

        assertThat(BulkLoanIngestionService.payloadHash(application("08-765432B08", "iVBORw0K", "110022")))
                .isNotEqualTo(base);
        assertThat(BulkLoanIngestionService.payloadHash(application("63-123456A63", "R0lGODlh", "110022")))
                .isNotEqualTo(base);
        assertThat(BulkLoanIngestionService.payloadHash(application("63-123456A63", "iVBORw0K", "990088")))
                .isNotEqualTo(base);
    }
}

package zw.co.innbucks.loans.core.draft;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** How long a saved application is kept (FR-SSB-002). */
@Data
@ConfigurationProperties(prefix = "loans.drafts")
public class LoanApplicationDraftProperties {

    /**
     * Days an open draft is kept after it was last saved. It holds the applicant's ID and payslip, so one
     * nobody comes back to is deleted rather than kept indefinitely.
     */
    private int expiryDays = 30;

    /** When expired drafts are deleted: daily, in the server's timezone (UTC in the container). */
    private String expiryCron = "0 30 1 * * *";
}

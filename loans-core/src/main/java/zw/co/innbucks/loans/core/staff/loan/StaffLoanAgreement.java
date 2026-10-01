package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;
import zw.co.innbucks.loans.core.instrument.InstrumentType;

import java.time.LocalDateTime;

/**
 * The agreement a Staff Grocery Loan was accepted under (FR-SGL-027, FR-SGL-028): the exact text the borrower was
 * shown, with its fingerprint, and the evidence of the acceptance: when, from which device and address, authenticated
 * how and by which middleware assertion. Sealed by a hash over all of it, and never changed (the table refuses it).
 */
@Entity
@Immutable
@Table(name = "staff_loan_agreements")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "staffLoanId", "templateVersion"})
public class StaffLoanAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_loan_id", nullable = false)
    private Long staffLoanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", length = 32, nullable = false)
    private InstrumentType instrumentType;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "content_sha256", length = 64, nullable = false)
    private String contentSha256;

    /** borrower:E1012 */
    @Column(name = "accepted_by", nullable = false)
    private String acceptedBy;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt;

    @Column(name = "device_id", length = 128, nullable = false)
    private String deviceId;

    @Column(name = "ip_address", length = 64, nullable = false)
    private String ipAddress;

    @Column(name = "forwarded_for", length = 512)
    private String forwardedFor;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** The middleware's amr for the acceptance: "pin", "fpt", "face". */
    @Column(name = "authentication_method", length = 64, nullable = false)
    private String authenticationMethod;

    /** The middleware assertion's jti. */
    @Column(name = "assertion_id", length = 128, nullable = false)
    private String assertionId;

    @Column(name = "evidence_sha256", length = 64, nullable = false)
    private String evidenceSha256;
}

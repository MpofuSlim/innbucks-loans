package zw.co.innbucks.loans.core.instrument;

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

import java.time.LocalDateTime;

/**
 * An instrument as the applicant signed it (FR-SSB-013): the exact text, the signature it was signed with,
 * and the evidence of the signing. Never changed once written; {@code evidenceSha256} seals every field, so a
 * row altered outside the application no longer matches it.
 */
@Entity
@Immutable
@Table(name = "loan_signed_instruments")
@Getter
@ToString(exclude = {"content", "userAgent", "forwardedFor", "ipAddress", "deviceId"})
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SignedInstrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", length = 32, nullable = false)
    private InstrumentType instrumentType;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "content_sha256", length = 64, nullable = false)
    private String contentSha256;

    @Column(name = "signature_sha256", length = 64, nullable = false)
    private String signatureSha256;

    @Column(name = "witness_signature_sha256", length = 64)
    private String witnessSignatureSha256;

    @Column(name = "signed_by", nullable = false)
    private String signedBy;

    @Column(name = "signed_at", nullable = false)
    private LocalDateTime signedAt;

    @Column(name = "device_id", length = 128, nullable = false)
    private String deviceId;

    @Column(name = "ip_address", length = 64, nullable = false)
    private String ipAddress;

    @Column(name = "forwarded_for", length = 512)
    private String forwardedFor;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "authentication_method", length = 64, nullable = false)
    private String authenticationMethod;

    @Column(name = "signer_authentication", length = 64)
    private String signerAuthentication;

    @Column(name = "evidence_sha256", length = 64, nullable = false)
    private String evidenceSha256;
}

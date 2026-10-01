package zw.co.innbucks.loans.core.voucher;

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

import java.time.LocalDateTime;

/** One attempt to send a voucher to its customer, on one channel (FR-GEN-004). Never holds the code. */
@Entity
@Table(name = "voucher_deliveries")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class VoucherDelivery {

    /** Sent and failed, as the dispatch log records them. */
    public enum Status { SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 16, nullable = false)
    private VoucherChannel channel;

    @Column(name = "recipient", length = 16, nullable = false)
    private String recipient;

    @Column(name = "template", length = 32, nullable = false)
    private String template;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    /** Our reference for an SMS at the notification API; the WhatsApp gateway takes none. */
    @Column(name = "gateway_reference", length = 64)
    private String gatewayReference;

    @Column(name = "failure_reason")
    private String failureReason;

    /** Who asked for it to be sent: {@code system} at issue, the staff member for a resend. */
    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}

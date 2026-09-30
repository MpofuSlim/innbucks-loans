package zw.co.innbucks.loans.core.notice;

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
 * A message the lender tried to send the applicant about their loan (FR-SSB-016), and whether the gateway
 * took it. Never changed once written.
 */
@Entity
@Immutable
@Table(name = "loan_notifications")
@Getter
@ToString(exclude = {"recipient", "message"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanNotification {

    /** The only channel today; the column allows others to be added. */
    public static final String SMS = "SMS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notice", length = 32, nullable = false)
    private LoanNotice notice;

    @Column(name = "channel", length = 16, nullable = false)
    private String channel;

    @Column(name = "recipient")
    private String recipient;

    @Column(name = "message", columnDefinition = "text", nullable = false)
    private String message;

    @Column(name = "gateway_reference", length = 64, nullable = false)
    private String gatewayReference;

    @Column(name = "sent", nullable = false)
    private boolean sent;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}

package zw.co.innbucks.loans.core.voucher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A grocery voucher: one per disbursement, worth what was disbursed, spent at GetMore's tills until it runs out or
 * expires (FR-SGL-035). Its code is held only as a keyed fingerprint, a sealed copy and its last four digits
 * ({@link VoucherCodeVault}), and never appears in {@link #toString()}.
 */
@Entity
@Table(name = "vouchers")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "product", "loanAccount", "status", "deliveryStatus"})
public class Voucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "product", length = 32, nullable = false)
    private VoucherProduct product;

    /** The payout this voucher is; one voucher per disbursement. */
    @Column(name = "disbursement_reference", length = 64, nullable = false, unique = true)
    private String disbursementReference;

    @Column(name = "loan_account", length = 64, nullable = false)
    private String loanAccount;

    /** The staff member it was issued to, for a Staff Grocery Loan. */
    @Column(name = "staff_member_id")
    private Long staffMemberId;

    /** Who the customer is in the product's own terms: the employee number for a Staff Grocery Loan. */
    @Column(name = "customer_reference", length = 64, nullable = false)
    private String customerReference;

    @Column(name = "customer_name", length = 160, nullable = false)
    private String customerName;

    /** Where the voucher is sent, in E.164. */
    @Column(name = "customer_msisdn", length = 16, nullable = false)
    private String customerMsisdn;

    @Column(name = "code_hmac", length = 64, nullable = false, unique = true)
    private String codeHmac;

    @Column(name = "code_ciphertext", nullable = false)
    private String codeCiphertext;

    @Column(name = "code_last4", length = 4, nullable = false)
    private String codeLast4;

    @Column(name = "code_length", nullable = false)
    private int codeLength;

    @Column(name = "face_value", precision = 19, scale = 2, nullable = false)
    private BigDecimal faceValue;

    @Column(name = "redeemed_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal redeemedAmount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    /** When it lapses: the end of its last market day. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private VoucherStatus status;

    @Column(name = "last_redeemed_at")
    private LocalDateTime lastRedeemedAt;

    @Column(name = "last_redeemed_outlet", length = 120)
    private String lastRedeemedOutlet;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 16, nullable = false)
    private VoucherDeliveryStatus deliveryStatus;

    /** The channel that took it, once SENT. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivered_channel", length = 16)
    private VoucherChannel deliveredChannel;

    @Column(name = "delivery_updated_at", nullable = false)
    private LocalDateTime deliveryUpdatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** What is left to spend. */
    public BigDecimal balance() {
        return faceValue.subtract(redeemedAmount);
    }

    /** Whether it has lapsed by {@code now}, whether or not the expiry job has marked it yet. */
    public boolean lapsed(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /** Its status as of {@code now}: an open voucher past its expiry reads EXPIRED before the job marks it. */
    public VoucherStatus statusAt(LocalDateTime now) {
        return status.isOpen() && lapsed(now) ? VoucherStatus.EXPIRED : status;
    }

    /** The code as staff without the entitlement see it (FR-SGL-040). */
    public String maskedCode() {
        return VoucherCodes.masked(codeLast4, codeLength);
    }

    void redeem(BigDecimal amount, String outlet, LocalDateTime at) {
        this.redeemedAmount = redeemedAmount.add(amount);
        this.status = redeemedAmount.compareTo(faceValue) == 0 ? VoucherStatus.REDEEMED
                : VoucherStatus.PARTIALLY_REDEEMED;
        this.lastRedeemedAt = at;
        this.lastRedeemedOutlet = outlet;
    }

    void cancel(String by, String reason, LocalDateTime at) {
        this.status = VoucherStatus.CANCELLED;
        this.cancelledBy = by;
        this.cancellationReason = reason;
        this.cancelledAt = at;
    }

    void expire() {
        this.status = VoucherStatus.EXPIRED;
    }

    /** Queued to be sent (again). */
    void queueDelivery(LocalDateTime at) {
        this.deliveryStatus = VoucherDeliveryStatus.PENDING;
        this.deliveredChannel = null;
        this.deliveryUpdatedAt = at;
    }

    /** Sent on {@code channel}, or FAILED on every channel when {@code channel} is null. */
    void finishDelivery(VoucherChannel channel, LocalDateTime at) {
        this.deliveryStatus = channel == null ? VoucherDeliveryStatus.FAILED : VoucherDeliveryStatus.SENT;
        this.deliveredChannel = channel;
        this.deliveryUpdatedAt = at;
    }
}

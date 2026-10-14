package zw.co.innbucks.loans.core.voucher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.merchant.Merchant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * A grocery voucher: one per disbursement, worth what was disbursed, spent at its merchant's tills, and no other's,
 * until it runs out or expires (FR-SGL-035). Its code is held only as a keyed fingerprint, a sealed copy and its last
 * four characters ({@link VoucherCodeVault}), and never appears in {@link #toString()}.
 *
 * <p>A GETMORE voucher ({@link VoucherCodeSource#GETMORE}) is a GetMore Groceries coupon: its code was made by
 * GetMore's store, it is spent there, and what was spent is read back from GetMore's orders and credit memos by
 * {@link GetMoreVoucherSync}, never from a till. For one of those, {@code redeemedAmount} is what is spent NET of
 * refunds, {@code refundedAmount} what was given back, and {@code heldAmount} what is ordered at the store but not yet
 * invoiced. The rows behind them are append-only.</p>
 */
@Entity
@Table(name = "vouchers")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "product", "loanAccount", "status", "deliveryStatus", "codeSource", "storeMode", "stoppedReason"})
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

    /** The merchant whose tills alone may take it, and who is paid for what is spent. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

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

    /** Who made the code: loans, or GetMore's store. */
    @Enumerated(EnumType.STRING)
    @Column(name = "code_source", length = 16, nullable = false)
    @Builder.Default
    private VoucherCodeSource codeSource = VoucherCodeSource.LOANS;

    /** GETMORE only: the store the coupon lives in, LIVE or SIMULATED. Every row of it carries the same. */
    @Enumerated(EnumType.STRING)
    @Column(name = "store_mode", length = 16)
    private GetMoreMode storeMode;

    /** GETMORE only: the Staff Grocery Loan the coupon was issued at acceptance for; one voucher per loan. */
    @Column(name = "staff_loan_id")
    private Long staffLoanId;

    /** GETMORE only: Magento's {@code coupon_id}. */
    @Column(name = "external_coupon_id")
    private Long externalCouponId;

    /** GETMORE only: the cart price rule the coupon was generated under. */
    @Column(name = "external_rule_id")
    private Long externalRuleId;

    /** GETMORE only: the issuance's prefix, {@code <configured prefix><nonce>-}; every coupon on it is the loan's. */
    @Column(name = "code_prefix", length = 48)
    private String codePrefix;

    /** GETMORE only: given back by GetMore's credit memos; {@code redeemedAmount} is already net of it. */
    @Column(name = "refunded_amount", precision = 19, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal refundedAmount = zero();

    /** GETMORE only: ordered at GetMore but not yet invoiced, so not yet spent, as the last sync saw it. */
    @Column(name = "held_amount", precision = 19, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal heldAmount = zero();

    /** GETMORE only: why it was stopped before its own expiry; null when it lapsed on its own, or never stopped. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stopped_reason", length = 24)
    private VoucherStopReason stoppedReason;

    /** GETMORE only: when the GetMore snapshot the last sync applied was started. */
    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    /** GETMORE only: the blocking issue the last sync stopped at; null when it ran to the end. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_blocked_by", length = 32)
    private VoucherSyncIssueKind syncBlockedBy;

    /** GETMORE only: when GetMore confirmed the coupon's {@code expiration_date}. */
    @Column(name = "coupon_expiry_set_at")
    private LocalDateTime couponExpirySetAt;

    /** GETMORE only: when the code was confirmed gone at GetMore (withdrawn by loans, or deleted by GetMore). */
    @Column(name = "coupon_withdrawn_at")
    private LocalDateTime couponWithdrawnAt;

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Whether it is a GetMore coupon rather than one of loans' own codes. */
    public boolean isGetMore() {
        return codeSource == VoucherCodeSource.GETMORE;
    }

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

    /**
     * The code as staff without the entitlement see it (FR-SGL-040): loans' grouped digits, or GetMore's one masked
     * form, {@code ****8B1C}.
     */
    public String maskedCode() {
        return isGetMore() ? GetMoreCodes.masked(codeLast4) : VoucherCodes.masked(codeLast4, codeLength);
    }

    /**
     * GETMORE: what can still be spent at GetMore's tills, as the last sync saw it: the balance less what is ordered
     * and not yet invoiced, never below zero.
     */
    public BigDecimal spendableAtStore() {
        BigDecimal spendable = balance().subtract(heldAmount == null ? BigDecimal.ZERO : heldAmount);
        return spendable.signum() > 0 ? spendable : zero();
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

    /**
     * GETMORE: what a sync read back from GetMore. {@code net} is spent less refunded; the status follows it, unless
     * the voucher was cancelled: all of it spent is REDEEMED, lapsed is EXPIRED, some spent is PARTIALLY_REDEEMED, none
     * is ISSUED (a refund back to nothing). The time and outlet of the last spend move only when a later one was
     * applied.
     */
    void applyGetMoreSync(BigDecimal net, BigDecimal refunded, BigDecimal held, LocalDateTime lastSpentAt,
                          String lastOutlet, LocalDateTime snapshotAt, VoucherSyncIssueKind blockedBy,
                          LocalDateTime now) {
        this.redeemedAmount = net;
        this.refundedAmount = refunded;
        this.heldAmount = held;
        if (lastSpentAt != null) {
            this.lastRedeemedAt = lastSpentAt;
            this.lastRedeemedOutlet = lastOutlet;
        }
        if (status != VoucherStatus.CANCELLED) {
            if (net.compareTo(faceValue) == 0) {
                this.status = VoucherStatus.REDEEMED;
            } else if (lapsed(now)) {
                this.status = VoucherStatus.EXPIRED;
            } else if (net.signum() > 0) {
                this.status = VoucherStatus.PARTIALLY_REDEEMED;
            } else {
                this.status = VoucherStatus.ISSUED;
            }
        }
        this.syncedAt = snapshotAt;
        this.syncBlockedBy = blockedBy;
    }

    /**
     * GETMORE: stops an open voucher now, for {@code reason}: it lapses at this moment (never later than it would have,
     * and always after it was issued) and nothing more can be spent. A voucher already closed, or already lapsed on its
     * own, is left as it is.
     *
     * @return whether it was stopped
     */
    boolean stopNow(VoucherStopReason reason, LocalDateTime now) {
        if (!statusAt(now).isOpen()) {
            return false;
        }
        LocalDateTime earliest = issuedAt.plus(1, ChronoUnit.MICROS);
        this.expiresAt = (now.isAfter(earliest) ? now : earliest).truncatedTo(ChronoUnit.MICROS);
        this.stoppedReason = reason;
        this.status = redeemedAmount.compareTo(faceValue) == 0 ? VoucherStatus.REDEEMED : VoucherStatus.EXPIRED;
        return true;
    }

    /** GETMORE: GetMore confirmed the coupon's expiry date. The first confirmation is kept. */
    void couponExpirySet(LocalDateTime at) {
        if (couponExpirySetAt == null) {
            this.couponExpirySetAt = at;
        }
    }

    /** GETMORE: the code is gone at GetMore. The first confirmation is kept. */
    void couponWithdrawn(LocalDateTime at) {
        if (couponWithdrawnAt == null) {
            this.couponWithdrawnAt = at;
        }
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

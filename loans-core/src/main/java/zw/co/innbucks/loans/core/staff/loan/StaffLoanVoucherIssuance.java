package zw.co.innbucks.loans.core.staff.loan;

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
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Issuing one Staff Grocery Loan's GetMore voucher (D13): written in the acceptance's own transaction, worked after it
 * commits by {@link StaffLoanVoucherIssuer}, which calls GetMore with no transaction open. Its coupon prefix carries a
 * random nonce drawn when it was written, known only to loans' database, so nothing on that prefix that predates the
 * first claim was made by loans.
 *
 * <p>An attempt claims it (CALLING) before it calls GetMore, and counts a generate before it is sent, never more than
 * {@code generateAllowance} of them (Magento's generate has no idempotency key; only an operator raises the allowance).
 * Codes GetMore returned that loans could not bind and could not confirm withdrawn are kept here, sealed by the voucher
 * vault, one per line, so a cleanup can withdraw them. No code is ever in plain text here, nor in {@code toString}.</p>
 */
@Entity
@Table(name = "staff_loan_voucher_issuances")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "staffLoanId", "status", "storeMode", "couponPrefix", "attempts", "generateCalls",
        "lastOutcome"})
public class StaffLoanVoucherIssuance {

    static final int OUTCOME_LENGTH = 48;
    static final int ERROR_LENGTH = 255;
    private static final String CODE_SEPARATOR = "\n";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_loan_id", nullable = false, updatable = false)
    private Long staffLoanId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "store_mode", length = 16, nullable = false, updatable = false)
    private GetMoreMode storeMode;

    /** {@code <configured prefix><8 random base32 characters>-}, e.g. {@code SIM-K7Q2M3XW-}. */
    @Column(name = "coupon_prefix", length = 48, nullable = false, updatable = false)
    private String couponPrefix;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private Long ruleId;

    @Column(name = "face_value", precision = 19, scale = 2, nullable = false, updatable = false)
    private BigDecimal faceValue;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffLoanVoucherIssuanceStatus status;

    /** Claims made, each one attempt. */
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    /** Generates that may have reached GetMore: counted before each is sent, given back only if provably not. */
    @Column(name = "generate_calls", nullable = false)
    @Builder.Default
    private int generateCalls = 0;

    /** How many generates may ever be sent: 1, raised only by an operator's "generate again". */
    @Column(name = "generate_allowance", nullable = false)
    @Builder.Default
    private int generateAllowance = 1;

    /** When the attempt now holding it claimed it; set only while CALLING. */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    /** The first claim: the adoption floor, less the clock skew. */
    @Column(name = "first_claimed_at")
    private LocalDateTime firstClaimedAt;

    /** The latest claim: a cleanup waits the stale-claim time past it before calling the prefix empty for good. */
    @Column(name = "last_claimed_at")
    private LocalDateTime lastClaimedAt;

    /** Codes GetMore returned that were not bound to the voucher, each sealed by the voucher vault, one per line. */
    @Column(name = "minted_codes_ciphertext", columnDefinition = "text")
    private String mintedCodesCiphertext;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    /** What the last attempt or cleanup ended with: ISSUED, UNAVAILABLE_NOT_REACHED, PAST_DUE, WITHDRAWN... */
    @Column(name = "last_outcome", length = OUTCOME_LENGTH)
    private String lastOutcome;

    /** Masked, never a code. */
    @Column(name = "last_error", length = ERROR_LENGTH)
    private String lastError;

    @Column(name = "voucher_id")
    private Long voucherId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Whether the attempt that claimed it at {@code claimedAt} still holds it. */
    public boolean isClaimedBy(LocalDateTime claimedAt) {
        return status == StaffLoanVoucherIssuanceStatus.CALLING && claimedAt != null
                && claimedAt.equals(this.claimedAt);
    }

    /** CALLING, and claimed less than {@code staleClaim} before {@code now}: its attempt may still be running. */
    public boolean isClaimFresh(LocalDateTime now, Duration staleClaim) {
        return status == StaffLoanVoucherIssuanceStatus.CALLING && claimedAt != null
                && claimedAt.isAfter(now.minus(staleClaim));
    }

    /** Whether no attempt ever claimed it, so no generate can ever have been sent for it. */
    public boolean neverClaimed() {
        return firstClaimedAt == null;
    }

    /** How many unbound codes it keeps for withdrawal. */
    public int mintedCodeCount() {
        return sealedCodes().size();
    }

    /** The sealed codes it keeps, one each. */
    List<String> sealedCodes() {
        if (mintedCodesCiphertext == null || mintedCodesCiphertext.isBlank()) {
            return List.of();
        }
        return Arrays.stream(mintedCodesCiphertext.split(CODE_SEPARATOR)).filter(line -> !line.isBlank()).toList();
    }

    void claim(LocalDateTime now) {
        this.status = StaffLoanVoucherIssuanceStatus.CALLING;
        this.claimedAt = now;
        this.lastClaimedAt = now;
        if (firstClaimedAt == null) {
            this.firstClaimedAt = now;
        }
        this.attempts++;
        this.updatedAt = now;
    }

    void countGenerate(LocalDateTime now) {
        this.generateCalls++;
        this.updatedAt = now;
    }

    void giveGenerateBack(LocalDateTime now) {
        if (generateCalls > 0) {
            this.generateCalls--;
            this.updatedAt = now;
        }
    }

    void issued(Long voucherId, LocalDateTime now) {
        this.status = StaffLoanVoucherIssuanceStatus.ISSUED;
        this.voucherId = voucherId;
        this.claimedAt = null;
        this.mintedCodesCiphertext = null;
        this.lastOutcome = "ISSUED";
        this.lastError = null;
        this.updatedAt = now;
    }

    /** PENDING again, for an attempt at {@code nextAttemptAt}. */
    void retryAt(LocalDateTime nextAttemptAt, String outcome, String error, LocalDateTime now) {
        this.status = StaffLoanVoucherIssuanceStatus.PENDING;
        this.claimedAt = null;
        this.nextAttemptAt = nextAttemptAt;
        this.lastOutcome = cut(outcome, OUTCOME_LENGTH);
        this.lastError = cut(error, ERROR_LENGTH);
        this.updatedAt = now;
    }

    /** Left PENDING with nothing tried: GetMore is OFF on this host. */
    void notTried(String outcome, LocalDateTime now) {
        this.status = StaffLoanVoucherIssuanceStatus.PENDING;
        this.claimedAt = null;
        this.lastOutcome = cut(outcome, OUTCOME_LENGTH);
        this.updatedAt = now;
    }

    void park(String outcome, String error, LocalDateTime now) {
        this.status = StaffLoanVoucherIssuanceStatus.PARKED;
        this.claimedAt = null;
        this.lastOutcome = cut(outcome, OUTCOME_LENGTH);
        this.lastError = cut(error, ERROR_LENGTH);
        this.updatedAt = now;
    }

    /** Its loan was cancelled: what it may have made at GetMore is to be withdrawn. */
    void cancelling(LocalDateTime now) {
        this.status = StaffLoanVoucherIssuanceStatus.CANCELLING;
        this.claimedAt = null;
        this.updatedAt = now;
    }

    /** Still CANCELLING after a cleanup: {@code outcome} says why, {@code error} what failed (or null). */
    void cleanupLeft(String outcome, String error, LocalDateTime now) {
        this.lastOutcome = cut(outcome, OUTCOME_LENGTH);
        this.lastError = cut(error, ERROR_LENGTH);
        this.updatedAt = now;
    }

    /** Nothing of it is left live at GetMore. */
    void abandoned(LocalDateTime now) {
        this.status = StaffLoanVoucherIssuanceStatus.ABANDONED;
        this.claimedAt = null;
        this.mintedCodesCiphertext = null;
        this.lastOutcome = "WITHDRAWN";
        this.lastError = null;
        this.updatedAt = now;
    }

    /** An operator's retry: PARKED becomes PENDING, due now. */
    void requeue(LocalDateTime now) {
        this.status = StaffLoanVoucherIssuanceStatus.PENDING;
        this.claimedAt = null;
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }

    /** An operator's "generate again": GetMore holds no usable coupon on the prefix, so one more may be sent. */
    void allowAnotherGenerate(LocalDateTime now) {
        this.generateAllowance++;
        this.updatedAt = now;
    }

    /** Keeps more sealed codes for withdrawal. */
    void keepSealedCodes(List<String> sealed, LocalDateTime now) {
        if (sealed.isEmpty()) {
            return;
        }
        String joined = String.join(CODE_SEPARATOR, sealed);
        this.mintedCodesCiphertext = mintedCodesCiphertext == null || mintedCodesCiphertext.isBlank() ? joined
                : mintedCodesCiphertext + CODE_SEPARATOR + joined;
        this.updatedAt = now;
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}

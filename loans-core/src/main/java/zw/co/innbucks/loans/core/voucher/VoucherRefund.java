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
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A refund of a GetMore sale, read back from a credit memo in state 2 (refunded): what it gave back to the voucher, and
 * what was left on it after. Each memo is recorded once (its {@code GM:CM:<id>} reference, {@code GMS:} when
 * simulated); never changed, but for the payday it was settled in. Kept apart from {@link VoucherRedemption} so no reader of spends can take a refund for
 * one.
 */
@Entity
@Table(name = "voucher_refunds")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class VoucherRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** {@code GM:CM:<credit memo id>}, or {@code GMS:CM:...} for the simulated store. */
    @Column(name = "merchant_reference", length = 64, nullable = false)
    private String merchantReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "store_mode", length = 16, nullable = false)
    private GetMoreMode storeMode;

    /** The order the refund is against. */
    @Column(name = "external_order_id", nullable = false)
    private Long externalOrderId;

    @Column(name = "external_creditmemo_id", nullable = false)
    private Long externalCreditmemoId;

    /** GetMore's own number for the credit memo, e.g. {@code SIMCM00000003}. */
    @Column(name = "external_creditmemo_number", length = 50)
    private String externalCreditmemoNumber;

    /** Positive: what was given back. */
    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", precision = 19, scale = 2, nullable = false)
    private BigDecimal balanceAfter;

    /** When GetMore made the credit memo, UTC. */
    @Column(name = "refunded_at", nullable = false)
    private LocalDateTime refundedAt;

    /** When loans read it back. */
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "recorded_by", nullable = false)
    private String recordedBy;

    /**
     * The payday whose finalised collection cycle took it off what GetMore was paid; null until one did. Written only
     * by the finalisation's own update, never through the entity.
     */
    @Column(name = "settlement_cycle", insertable = false, updatable = false)
    private LocalDate settlementCycle;
}

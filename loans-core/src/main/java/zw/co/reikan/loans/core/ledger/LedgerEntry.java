package zw.co.reikan.loans.core.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One immutable leg of a double-entry posting. Money is NEVER updated in
 * place: balances are derived by summing credits/debits over history.
 *
 * <p>Three layers enforce immutability:</p>
 * <ol>
 *   <li>No setters — the class is {@code @Getter}-only with a builder.</li>
 *   <li>Hibernate {@link Immutable} — the ORM refuses UPDATE statements.</li>
 *   <li>A database trigger (see {@code docs/db/enterprise_hardening.sql})
 *       rejects UPDATE/DELETE at the engine level, covering raw SQL too.</li>
 * </ol>
 *
 * <p>The {@code (transaction_ref, account, entry_type)} unique constraint makes
 * postings naturally idempotent: replaying a saga step cannot double-post.</p>
 */
@Entity
@Immutable
@Table(name = "ledger_entries",
        uniqueConstraints = @UniqueConstraint(name = "uq_ledger_txref_account_type",
                columnNames = {"transaction_ref", "account", "entry_type"}),
        indexes = {
                @Index(name = "idx_ledger_loan_id", columnList = "loan_id"),
                @Index(name = "idx_ledger_account", columnList = "account"),
                @Index(name = "idx_ledger_tx_ref", columnList = "transaction_ref")
        })
@Getter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Groups the legs of one logical transaction, e.g. {@code DISB-000000123}. */
    @Column(name = "transaction_ref", length = 64, nullable = false)
    private String transactionRef;

    @Column(name = "loan_id")
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account", length = 48, nullable = false)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 8, nullable = false)
    private LedgerEntryType entryType;

    /** Always positive; direction is carried by {@code entry_type}. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

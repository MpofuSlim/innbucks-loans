package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.domain.Persistable;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A payday whose Staff Grocery Loan collections were finalised, for one GetMore store (LIVE, or staging's SIMULATED):
 * the cut-off it was taken at, who finalised it and when, and why, when anything on it was still not final. Its
 * existence is what stamps the GetMore sales and refunds it paid for ({@code settlement_cycle}), so a later sale lands
 * in the next payday. Written once by {@link StaffLoanCollectionService#finalise}, never changed: its primary key makes
 * a second finalisation of the same payday fail.
 */
@Entity
@Table(name = "staff_loan_collection_cycles")
@IdClass(StaffLoanCollectionCycle.Key.class)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"payday", "storeMode", "finalisedAt", "nonFinalLines", "staleVouchers"})
public class StaffLoanCollectionCycle implements Persistable<StaffLoanCollectionCycle.Key> {

    /** A cycle's identity: its payday in one store. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private LocalDate payday;
        private GetMoreMode storeMode;
    }

    @Id
    @Column(name = "payday", nullable = false, updatable = false)
    private LocalDate payday;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "store_mode", length = 16, nullable = false, updatable = false)
    private GetMoreMode storeMode;

    /** 00:00 market time on the payday, UTC: sales and refunds before it were paid for in this cycle. */
    @Column(name = "cutoff", nullable = false, updatable = false)
    private LocalDateTime cutoff;

    @Column(name = "finalised_at", nullable = false, updatable = false)
    private LocalDateTime finalisedAt;

    @Column(name = "finalised_by", nullable = false, updatable = false)
    private String finalisedBy;

    /** Lines that were provisional when it was finalised. */
    @Column(name = "non_final_lines", nullable = false, updatable = false)
    private int nonFinalLines;

    /** Vouchers the payday sync had still to reach when it was finalised. */
    @Column(name = "stale_vouchers", nullable = false, updatable = false)
    private int staleVouchers;

    /** Why it was finalised with anything not final; required then. */
    @Column(name = "reason", updatable = false)
    private String reason;

    /** Set on the one being written, so it is inserted (never merged over an existing payday). */
    @Transient
    @Builder.Default
    private boolean fresh = false;

    @Override
    public Key getId() {
        return new Key(payday, storeMode);
    }

    @Override
    public boolean isNew() {
        return fresh;
    }
}

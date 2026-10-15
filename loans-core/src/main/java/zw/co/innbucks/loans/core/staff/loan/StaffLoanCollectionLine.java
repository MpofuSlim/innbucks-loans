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
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One loan's line of a finalised payday, as it was sent to payroll: what was to be collected, under which treatment,
 * whether that was final, and the flags that said why not. Kept as it was at finalisation, whatever is synced after: a
 * later sale changes the next payday, never this one.
 */
@Entity
@Table(name = "staff_loan_collection_lines")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "payday", "storeMode", "staffLoanId", "amountToCollect", "finalAmount"})
public class StaffLoanCollectionLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payday", nullable = false, updatable = false)
    private LocalDate payday;

    @Enumerated(EnumType.STRING)
    @Column(name = "store_mode", length = 16, nullable = false, updatable = false)
    private GetMoreMode storeMode;

    @Column(name = "staff_loan_id", nullable = false, updatable = false)
    private Long staffLoanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "treatment", length = 32, nullable = false, updatable = false)
    private UnredeemedVoucherTreatment treatment;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "amount_to_collect", precision = 19, scale = 2, nullable = false, updatable = false)
    private BigDecimal amountToCollect;

    /** Whether the amount was final when the payday was finalised. */
    @Column(name = "final", nullable = false, updatable = false)
    private boolean finalAmount;

    /** The line's flags, comma-joined; empty when none. */
    @Column(name = "flags", nullable = false, updatable = false)
    private String flags;

    /** The flags as stored. */
    public Set<StaffLoanOwed.Flag> flagSet() {
        return flags == null || flags.isBlank() ? EnumSet.noneOf(StaffLoanOwed.Flag.class)
                : Arrays.stream(flags.split(",")).map(String::strip).map(StaffLoanOwed.Flag::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(StaffLoanOwed.Flag.class)));
    }

    /** Flags as stored: comma-joined in their own order, empty when none. */
    static String join(Collection<StaffLoanOwed.Flag> flags) {
        return flags.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }
}

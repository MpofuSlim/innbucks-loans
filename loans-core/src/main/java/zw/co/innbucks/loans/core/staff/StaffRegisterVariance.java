package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/** One line of a reconciliation's variance report (FR-SGL-008). Written once and never changed. */
@Entity
@Table(name = "staff_register_variances")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "reconciliationId", "kind", "employeeNumber"})
public class StaffRegisterVariance {

    /** Ids drawn per sequence call: V33's INCREMENT BY. Hibernate's schema validation checks the two agree. */
    public static final int ID_ALLOCATION = 50;

    /**
     * From the column's own sequence, {@value #ID_ALLOCATION} ids per round trip (pooled-lo, see JpaSchemaConfig), so
     * the inserts of one saveAll go out as JDBC batches: a reconciliation writes one variance per finding, and under
     * IDENTITY each was an INSERT of its own with its id read back. V33 set the sequence's INCREMENT BY to match and
     * moved it above every existing id. The column keeps its DEFAULT nextval on the same sequence, so a build from
     * before this still inserts without colliding: each of its inserts takes a whole block of its own.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "staff_register_variances_id_seq")
    @SequenceGenerator(name = "staff_register_variances_id_seq", sequenceName = "staff_register_variances_id_seq",
            allocationSize = ID_ALLOCATION)
    private Long id;

    @Column(name = "reconciliation_id", nullable = false)
    private Long reconciliationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 24, nullable = false)
    private StaffRegisterVarianceKind kind;

    /** Absent only on an UNREADABLE row whose employee number cell was blank. */
    @Column(name = "employee_number")
    private String employeeNumber;

    @Column(name = "full_name")
    private String fullName;

    /** On a LEFT_ON_PAYROLL or NOT_ON_PAYROLL variance only: whether they could borrow when the reconciliation ran. */
    @Column(name = "eligible")
    private Boolean eligible;

    /** The rest, as JSON: the payroll rows, the register's record, the payroll row as sent, the fields that differ. */
    @Column(name = "details", columnDefinition = "text", nullable = false)
    private String details;

    /** What {@link #details} holds; each part only on the kinds {@link StaffRegisterVarianceResponse} names. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Details(List<Integer> rowNumbers, Map<String, String> register, Map<String, String> payroll,
                          List<StaffRegisterVarianceResponse.Difference> differences, Map<String, String> errors) {
    }
}

package zw.co.innbucks.loans.core.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * One comparison of the staff register with the HR payroll master (FR-SGL-008), and how it came out. Its variances are
 * {@link StaffRegisterVariance} rows. Written once and never changed.
 */
@Entity
@Table(name = "staff_register_reconciliations")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "fileName", "runBy", "runAt"})
public class StaffRegisterReconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** SHA-256 of the payroll master as received, so the exact file can be identified later. */
    @Column(name = "file_sha256", length = 64, nullable = false)
    private String fileSha256;

    @Column(name = "run_by", nullable = false)
    private String runBy;

    @Column(name = "run_at", nullable = false)
    private LocalDateTime runAt;

    @Column(name = "comment")
    private String comment;

    /** The {@link StaffFields} the file had a column for, besides the employee number, comma-separated. */
    @Column(name = "compared_fields", nullable = false)
    private String comparedFields;

    @Column(name = "payroll_rows", nullable = false)
    private Integer payrollRows;

    /** How many staff members the register held when it ran. */
    @Column(name = "register_members", nullable = false)
    private Integer registerMembers;

    @Column(name = "matched", nullable = false)
    private Integer matched;

    @Column(name = "different", nullable = false)
    private Integer different;

    @Column(name = "left_on_payroll", nullable = false)
    private Integer leftOnPayroll;

    /** Of those the payroll says have left, how many could borrow when it ran. */
    @Column(name = "left_on_payroll_eligible", nullable = false)
    private Integer leftOnPayrollEligible;

    @Column(name = "not_on_register", nullable = false)
    private Integer notOnRegister;

    @Column(name = "not_on_payroll", nullable = false)
    private Integer notOnPayroll;

    /** Of those not on the payroll, how many could borrow when it ran. */
    @Column(name = "not_on_payroll_eligible", nullable = false)
    private Integer notOnPayrollEligible;

    @Column(name = "duplicates_on_payroll", nullable = false)
    private Integer duplicatesOnPayroll;

    @Column(name = "unreadable_rows", nullable = false)
    private Integer unreadableRows;
}

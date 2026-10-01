package zw.co.innbucks.loans.core.staff;

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
import lombok.Setter;
import lombok.ToString;

/**
 * One row of a batch, as submitted (FR-SGL-003). A REJECTED row keeps what was sent and why it was refused; a STAGED
 * row keeps the normalised values to apply. Values are text because a refused row may not parse.
 */
@Entity
@Table(name = "staff_register_rows")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "batchId", "rowNumber", "outcome", "action"})
public class StaffRegisterRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** The row's line in the file (the header is line 1); 1 for a change made on the admin screen. */
    @Column(name = "row_number", nullable = false)
    private Integer rowNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16, nullable = false)
    private StaffRegisterRowOutcome outcome;

    /** For a staged or applied row: whether it adds a staff member or changes one. */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16)
    private StaffRegisterRowAction action;

    @Column(name = "employee_number")
    private String employeeNumber;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "national_id")
    private String nationalId;

    @Column(name = "msisdn")
    private String msisdn;

    @Column(name = "grade")
    private String grade;

    @Column(name = "department")
    private String department;

    @Column(name = "employment_status")
    private String employmentStatus;

    @Column(name = "engagement_date")
    private String engagementDate;

    @Column(name = "wallet_account_number")
    private String walletAccountNumber;

    /** Why a REJECTED or SKIPPED row was refused: JSON, field to message. */
    @Column(name = "errors", columnDefinition = "text")
    private String errors;

    @Column(name = "staff_member_id")
    private Long staffMemberId;
}

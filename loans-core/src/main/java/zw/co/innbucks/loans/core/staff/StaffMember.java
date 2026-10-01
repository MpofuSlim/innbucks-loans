package zw.co.innbucks.loans.core.staff;

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
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One InnBucks employee on the Staff Register (FR-SGL-001), the master control for the Staff Grocery Loan. Changed only
 * by approving a batch (FR-SGL-004); every field it changes is kept in {@link StaffMemberChange}.
 */
@Entity
@Table(name = "staff_members")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "employeeNumber", "grade", "employmentStatus"})
public class StaffMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_number", length = 32, nullable = false, unique = true)
    private String employeeNumber;

    @Column(name = "full_name", length = 160, nullable = false)
    private String fullName;

    /** Upper case, letters and digits only. */
    @Column(name = "national_id", length = 20, nullable = false)
    private String nationalId;

    /** International form, 2637XXXXXXXX. */
    @Column(name = "msisdn", length = 12, nullable = false, unique = true)
    private String msisdn;

    @Column(name = "grade", length = 16, nullable = false)
    private String grade;

    @Column(name = "department", length = 120, nullable = false)
    private String department;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", length = 16, nullable = false)
    private StaffEmploymentStatus employmentStatus;

    @Column(name = "engagement_date", nullable = false)
    private LocalDate engagementDate;

    /** The InnBucks wallet (a mobile number, 2637XXXXXXXX) or account number. */
    @Column(name = "wallet_account_number", length = 20, nullable = false)
    private String walletAccountNumber;

    /** When the employment status last changed (or the record was created). */
    @Column(name = "status_changed_at", nullable = false)
    private LocalDateTime statusChangedAt;

    @Column(name = "created_batch_id", nullable = false)
    private Long createdBatchId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_batch_id", nullable = false)
    private Long updatedBatchId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** The record's values, as a register row carries them. */
    public StaffRecord record() {
        return new StaffRecord(employeeNumber, fullName, nationalId, msisdn, grade, department, employmentStatus,
                engagementDate, walletAccountNumber);
    }
}

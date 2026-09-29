package zw.co.innbucks.loans.core.loan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Embeddable
public class EmploymentDetail {

    @NotBlank(groups = LoanApplicationChecks.class, message = "Employer name is required")
    @Column(name = "employer_name")
    private String employerName;

    /** The ministry or department (FR-SSB-003); deduction performance is reported by it (FR-SSB-026). */
    @NotBlank(groups = LoanApplicationChecks.class, message = "Ministry or department is required")
    @Size(max = 255, message = "Ministry or department must be at most 255 characters")
    @Column(name = "ministry")
    private String ministry;

    @NotBlank(groups = LoanApplicationChecks.class, message = "Station is required")
    @Size(max = 255, message = "Station must be at most 255 characters")
    @Column(name = "station")
    private String station;

    /** Grade or notch, as on the payslip. */
    @NotBlank(groups = LoanApplicationChecks.class, message = "Grade or notch is required")
    @Size(max = 64, message = "Grade or notch must be at most 64 characters")
    @Column(name = "grade")
    private String grade;

    @NotNull(groups = LoanApplicationChecks.class, message = "Contract type is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type")
    private ContractType contractType;

    @Column(name = "employer_contact_number")
    private String employerContactNumber;

    @NotBlank(groups = LoanApplicationChecks.class, message = "Employee number is required")
    @Column(name = "employee_number")
    private String employeeNumber;

    @NotNull(groups = LoanApplicationChecks.class, message = "Gross salary is required")
    @Positive(groups = LoanApplicationChecks.class, message = "Gross salary must be greater than zero")
    @Column(name = "gross_salary")
    private BigDecimal grossSalary;

    /** Take-home pay after every deduction on the payslip; what the loan's deduction is measured against. */
    @NotNull(groups = LoanApplicationChecks.class, message = "Net salary is required")
    @PositiveOrZero(groups = LoanApplicationChecks.class, message = "Net salary cannot be negative")
    @Column(name = "net_salary")
    private BigDecimal netSalary;

    /** The date of engagement (FR-SSB-003). */
    @NotNull(groups = LoanApplicationChecks.class, message = "Employment start date is required")
    @PastOrPresent(groups = LoanApplicationChecks.class, message = "Employment start date cannot be in the future")
    @Column(name = "employment_start_date")
    private LocalDate employmentStartDate;

    @Column(name = "employment_end_date")
    private LocalDate employmentEndDate;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "street", column = @Column(name = "employer_street")),
            @AttributeOverride(name = "suburb", column = @Column(name = "employer_suburb")),
            @AttributeOverride(name = "city", column = @Column(name = "employer_city")),
            @AttributeOverride(name = "country", column = @Column(name = "employer_country"))
    })
    private Address address;
}

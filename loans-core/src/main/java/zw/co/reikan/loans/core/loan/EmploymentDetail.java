package zw.co.reikan.loans.core.loan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Embeddable
public class EmploymentDetail {

    @NotBlank(groups = LoanApplicationChecks.class, message = "Employer name is required")
    @Column(name = "employee_name")
    private String employerName;

    @Column(name = "employee_contact_number")
    private String employerContactNumber;

    @NotBlank(groups = LoanApplicationChecks.class, message = "Employee number is required")
    @Column(name = "employee_number")
    private String employeeNumber;

    @NotNull(groups = LoanApplicationChecks.class, message = "Gross salary is required")
    @Positive(groups = LoanApplicationChecks.class, message = "Gross salary must be greater than zero")
    @Column(name = "gross_salary")
    private BigDecimal grossSalary;

    @Column(name = "net_salary")
    private BigDecimal netSalary;

    @NotNull(groups = LoanApplicationChecks.class, message = "Employment start date is required")
    @PastOrPresent(groups = LoanApplicationChecks.class, message = "Employment start date cannot be in the future")
    @Column(name = "employment_start_date")
    private LocalDate employmentStartDate;

    @Column(name = "employment_end_date")
    private LocalDate employmentEndDate;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "street", column = @Column(name = "employee_street")),
            @AttributeOverride(name = "suburb", column = @Column(name = "employee_suburb")),
            @AttributeOverride(name = "city", column = @Column(name = "employee_city")),
            @AttributeOverride(name = "country", column = @Column(name = "employee_country"))
    })
    private Address address;
}

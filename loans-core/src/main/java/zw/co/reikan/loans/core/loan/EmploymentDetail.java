package zw.co.reikan.loans.core.loan;

import lombok.Data;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.Embedded;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Embeddable
public class EmploymentDetail {

    @Column(name = "employee_name")
    private String employerName;

    @Column(name = "employee_contact_number")
    private String employerContactNumber;

    @Column(name = "employee_number")
    private String employeeNumber;

    @Column(name = "gross_salary")
    private BigDecimal grossSalary;

    @Column(name = "net_salary")
    private BigDecimal netSalary;

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

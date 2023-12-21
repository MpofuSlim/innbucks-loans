package zw.co.reikan.loans.core.loan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoanRequest implements Serializable {
    private String signatureData;
    private BigDecimal amount;
    private String ecnumber;
    private String mobileNumber;
    private int tenor;
    private String nationalId;
    private LoanAmountType type = LoanAmountType.NET_OF_FEES;
    private String fname;
    private String lname;
    private LocalDate dateOfBirth;
    private int numberOfDependencies;
    private MaritalStatus maritalStatus;
    private String alternateContactNumber;
    private String placeOfBirth;
    private Title title;
    private String email;
    private EducationLevel educationLevel;
    private Address address;
    private EmploymentDetail employmentDetail;
    private NextOfKin nextOfKin;
    private Witness witness;

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("LoanRequest{");
        sb.append("signatureData='").append(signatureData).append('\'');
        sb.append(", amount=").append(amount);
        sb.append(", ecnumber='").append(ecnumber).append('\'');
        sb.append(", mobileNumber='").append(mobileNumber).append('\'');
        sb.append(", tenor=").append(tenor);
        sb.append(", nationalId='").append(nationalId).append('\'');
        sb.append(", type=").append(type);
        sb.append(", fname='").append(fname).append('\'');
        sb.append(", lname='").append(lname).append('\'');
        sb.append(", dob='").append(dateOfBirth).append('\'');
        sb.append('}');
        return sb.toString();
    }
}

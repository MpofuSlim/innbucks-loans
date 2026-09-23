package zw.co.reikan.loans.core.loan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import zw.co.reikan.loans.core.MsisdnUtil;

import jakarta.persistence.*;

@Embeddable
@Data
public class NextOfKin {

    @NotBlank(groups = LoanApplicationChecks.class, message = "Next of kin first name is required")
    @Column(name = "next_of_kin_first_name")
    private String firstName;

    @Column(name = "next_of_kin_last_name")
    private String lastName;

    @Column(name = "next_of_kin_id_number")
    private String nationalId;

    @Valid
    @NotNull(groups = LoanApplicationChecks.class, message = "Next of kin address is required")
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "street", column = @Column(name = "next_of_kin_street")),
            @AttributeOverride(name = "suburb", column = @Column(name = "next_of_kin_suburb")),
            @AttributeOverride(name = "city", column = @Column(name = "next_of_kin_city")),
            @AttributeOverride(name = "country", column = @Column(name = "next_of_kin_country"))
    })
    private Address address;

    @NotBlank(groups = LoanApplicationChecks.class, message = "Next of kin mobile number is required")
    @Pattern(groups = LoanApplicationChecks.class, regexp = MsisdnUtil.ZIMBABWE_MOBILE_REGEX,
            message = MsisdnUtil.ZIMBABWE_MOBILE_MESSAGE)
    @Column(name = "next_of_kin_mobile_number")
    private String mobileNumber;

    @NotNull(groups = LoanApplicationChecks.class, message = "Next of kin relationship is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "next_of_kin_relationship")
    private RelationshipType relationship;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_of_kin_gender")
    private Gender gender;
}

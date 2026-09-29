package zw.co.reikan.loans.core.loan;

import lombok.Data;
import lombok.ToString;

import jakarta.persistence.*;

@Embeddable
@Data
public class Customer {
    @Column(name = "mobile_number")
    private String mobileNumber;

    @Column(name = "ec_number")
    private String ecNumber;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @ToString.Exclude
    @Column(name = "national_id_number")
    private String nationalIdNumber;

    @ToString.Exclude
    @Lob
    @Column(name = "signature", columnDefinition = "text")
    private String signature;
}

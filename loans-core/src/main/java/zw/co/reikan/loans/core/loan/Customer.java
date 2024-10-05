package zw.co.reikan.loans.core.loan;

import lombok.Data;

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

    @Column(name = "national_id_number")
    private String nationalIdNumber;

    @Lob
    @Column(name = "signature", columnDefinition = "MEDIUMTEXT")
    private String signature;
}

package zw.co.reikan.loans.core.loan;

import lombok.Data;

import jakarta.persistence.*;

@Embeddable
@Data
public class NextOfKin {

    @Column(name = "next_of_kin_first_name")
    private String firstName;

    @Column(name = "next_of_kin_last_name")
    private String lastName;

    @Column(name = "next_of_kin_id_number")
    private String nationalId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "street", column = @Column(name = "next_of_kin_street")),
            @AttributeOverride(name = "suburb", column = @Column(name = "next_of_kin_suburb")),
            @AttributeOverride(name = "city", column = @Column(name = "next_of_kin_city")),
            @AttributeOverride(name = "country", column = @Column(name = "next_of_kin_country"))
    })
    private Address address;

    @Column(name = "next_of_kin_mobile_number")
    private String mobileNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_of_kin_relationship")
    private RelationshipType relationship;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_of_kin_gender")
    private Gender gender;
}

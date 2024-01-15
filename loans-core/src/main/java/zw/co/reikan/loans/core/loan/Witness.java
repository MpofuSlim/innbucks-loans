package zw.co.reikan.loans.core.loan;

import lombok.Data;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.Lob;
import java.time.LocalDate;

@Embeddable
@Data
public class Witness {

    @Column(name = "witness_full_name")
    private String fullName;

    @Column(name = "witness_place_of_signature")
    private String placeOfSignature;

    @Column(name = "witness_date_singed")
    private LocalDate dateSigned;

    @Lob
    @Column(name = "witness_signature", columnDefinition = "MEDIUMTEXT")
    private String signature;
}

package zw.co.innbucks.loans.core.loan;

import lombok.Data;
import lombok.ToString;

import jakarta.persistence.*;
import java.time.LocalDate;

@Embeddable
@Data
public class Witness {

    @Column(name = "witness_full_name")
    private String fullName;

    @Column(name = "witness_place_of_signature")
    private String placeOfSignature;

    @Column(name = "witness_date_signed")
    private LocalDate dateSigned;

    @ToString.Exclude
    @Lob
    @Column(name = "witness_signature", columnDefinition = "text")
    private String signature;
}

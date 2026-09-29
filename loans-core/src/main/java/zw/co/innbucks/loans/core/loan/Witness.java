package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * The witness's signature as the application sends it (base64). Kept as the loan's WITNESS_SIGNATURE
     * document, with version history, rather than on the loan; never persisted or returned here.
     */
    @ToString.Exclude
    @Transient
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String signature;
}

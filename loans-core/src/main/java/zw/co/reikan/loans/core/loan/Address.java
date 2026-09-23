package zw.co.reikan.loans.core.loan;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import jakarta.persistence.*;

@Data
@Embeddable
public class Address {
    @NotBlank(groups = LoanApplicationChecks.class, message = "Street is required")
    @Column(name = "street")
    private String street;

    @Column(name = "suburb")
    private String suburb;

    @NotBlank(groups = LoanApplicationChecks.class, message = "City is required")
    @Column(name = "city")
    private String city;

    @Column(name = "country")
    private String country;

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer();
        if (street != null) {
            sb.append(street);
        }
        if (suburb != null) {
            sb.append(',').append(suburb);
        }
        if (city != null) {
            sb.append(',').append(city);
        }
        if (country != null) {
            sb.append(',').append(country);
        }
        return sb.toString();
    }
}

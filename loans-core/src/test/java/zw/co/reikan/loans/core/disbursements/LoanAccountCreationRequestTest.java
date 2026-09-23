package zw.co.reikan.loans.core.disbursements;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the outbound wire contract for the InnBucks loan-account-creation request:
 * fields we do not have are OMITTED from the JSON (never sent as an explicit
 * {@code null} or a placeholder default), while the values we do have are sent.
 * Guards the {@code @JsonInclude(NON_NULL)} behaviour the disbursement path
 * relies on after the default-injection was removed.
 */
class LoanAccountCreationRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void absentFieldsAreOmitted_presentFieldsAreSent() throws Exception {
        // Only the values we actually have; every optional field left null.
        LoanAccountCreationRequest request = LoanAccountCreationRequest.builder()
                .firstName("James")
                .lastName("Mufambanaayo")
                .amount(50000)
                .currency("USD")
                .product("NANOUS")
                .tenureInMonths(6)
                .build();

        String json = objectMapper.writeValueAsString(request);

        // Present values are on the wire.
        assertThat(json)
                .contains("\"firstName\":\"James\"")
                .contains("\"amount\":50000")
                .contains("\"currency\":\"USD\"")
                .contains("\"tenureInMonths\":6");

        // No explicit nulls anywhere.
        assertThat(json).doesNotContain("null");

        // The previously-defaulted fields are omitted entirely when absent.
        assertThat(json)
                .doesNotContain("maritalStatus")
                .doesNotContain("placeOfBirth")
                .doesNotContain("businessLine")
                .doesNotContain("loanPurpose")
                .doesNotContain("grossSalary")
                .doesNotContain("employer")
                .doesNotContain("employmentStartDate")
                .doesNotContain("nextOfKin");
    }
}

package zw.co.innbucks.loans.core.instrument;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * New wording for an instrument, published as its next version.
 *
 * @param body the text, with {@code {{placeholders}}} for the loan's terms; see {@link InstrumentTerms}
 */
public record PublishInstrumentTemplateRequest(
        @NotNull(message = "Instrument type is required")
        @Schema(example = "LOAN_AGREEMENT")
        InstrumentType instrumentType,

        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must be at most 200 characters")
        @Schema(example = "SSB Loan Agreement")
        String title,

        @NotBlank(message = "Body is required")
        @Size(max = 100_000, message = "Body must be at most 100000 characters")
        @Schema(example = "This agreement is between InnBucks and {{applicantName}} (EC number {{ecNumber}}) ...")
        String body) {
}

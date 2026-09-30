package zw.co.innbucks.loans.core.instrument;

import java.time.LocalDateTime;

/** A published version of an instrument's wording, with its {@code {{placeholders}}} unfilled. */
public record InstrumentTemplateResponse(
        InstrumentType instrumentType,
        int version,
        String title,
        String body,
        String publishedBy,
        LocalDateTime publishedAt) {

    static InstrumentTemplateResponse of(InstrumentTemplate template) {
        return new InstrumentTemplateResponse(template.getInstrumentType(), template.getVersion(), template.getTitle(),
                template.getBody(), template.getPublishedBy(), template.getPublishedAt());
    }
}

package zw.co.innbucks.loans.core.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/** The created date as text, from one shared formatter: the same text the per-call formatter produced. */
class AbstractAuditingEntityTest {

    @Test
    @DisplayName("getFormatedCreatedDate is dd-MMM-yyyy HH:mm:ss, as before the formatter was shared")
    void formatsTheCreatedDate() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 6, 7, 5, 9);
        AbstractAuditingEntity entity = new AbstractAuditingEntity() {
        };
        entity.setCreatedDate(created);

        assertThat(entity.getFormatedCreatedDate())
                .isEqualTo(created.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss")))
                .startsWith("06-")
                .endsWith("-2026 07:05:09");
    }
}

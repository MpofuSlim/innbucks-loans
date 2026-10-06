package zw.co.innbucks.loans.core.loan;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Data
@MappedSuperclass
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public abstract class AbstractAuditingEntity implements Serializable {

    /** One formatter, not one per call: it is immutable and thread-safe. The default locale, as before. */
    private static final DateTimeFormatter CREATED_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "last_modified_date")
    private LocalDateTime lastModifiedDate = LocalDateTime.now(ZoneOffset.UTC);

    public String getFormatedCreatedDate() {
        return this.getCreatedDate().format(CREATED_DATE);
    }

}
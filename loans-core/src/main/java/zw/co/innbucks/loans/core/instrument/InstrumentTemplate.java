package zw.co.innbucks.loans.core.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * One published version of an instrument's wording (FR-SSB-013). Never changed once published: new wording
 * is the next version, so every signed instrument can be traced to the exact wording it was rendered from.
 */
@Entity
@Immutable
@Table(name = "instrument_templates")
@Getter
@ToString(exclude = "body")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", length = 32, nullable = false)
    private InstrumentType instrumentType;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "body", columnDefinition = "text", nullable = false)
    private String body;

    @Column(name = "published_by", nullable = false)
    private String publishedBy;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;
}

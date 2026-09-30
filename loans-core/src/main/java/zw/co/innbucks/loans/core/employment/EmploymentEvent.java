package zw.co.innbucks.loans.core.employment;

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

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Something that happened to a borrower's employment (FR-SSB-024), recorded against their EC number. */
@Entity
@Immutable
@Table(name = "employment_events")
@Getter
@ToString(exclude = {"ecNumber", "note"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmploymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ec_number", length = 16, nullable = false)
    private String ecNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 32, nullable = false)
    private EmploymentEventType eventType;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** When a secondment, suspension or unpaid leave ends, if known. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** The new ministry, for a transfer or secondment. */
    @Column(name = "ministry")
    private String ministry;

    /** The new station, for a transfer or secondment. */
    @Column(name = "station")
    private String station;

    /** The new grade or notch, for a promotion. */
    @Column(name = "grade", length = 64)
    private String grade;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "recorded_by", nullable = false)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
}

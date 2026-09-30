package zw.co.innbucks.loans.core.employment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/** The treatment of one type of employment event (FR-SSB-024), changed by an administrator without a release. */
@Entity
@Table(name = "employment_event_treatments")
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmploymentEventTreatment {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 32)
    private EmploymentEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_treatment", length = 16, nullable = false)
    private ApplicationTreatment applicationTreatment;

    @Enumerated(EnumType.STRING)
    @Column(name = "loan_treatment", length = 16, nullable = false)
    private LoanTreatment loanTreatment;

    /** Whether a declined applicant is sent the decline SMS; never after a death in service, by default. */
    @Column(name = "notify_on_decline", nullable = false)
    private boolean notifyOnDecline;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

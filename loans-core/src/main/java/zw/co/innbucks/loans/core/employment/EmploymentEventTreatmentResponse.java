package zw.co.innbucks.loans.core.employment;

import java.time.LocalDateTime;

/** The treatment configured for one type of employment event (FR-SSB-024). */
public record EmploymentEventTreatmentResponse(
        EmploymentEventType eventType,
        ApplicationTreatment applicationTreatment,
        LoanTreatment loanTreatment,
        boolean notifyOnDecline,
        String updatedBy,
        LocalDateTime updatedAt) {

    static EmploymentEventTreatmentResponse of(EmploymentEventTreatment treatment) {
        return new EmploymentEventTreatmentResponse(treatment.getEventType(), treatment.getApplicationTreatment(),
                treatment.getLoanTreatment(), treatment.isNotifyOnDecline(), treatment.getUpdatedBy(),
                treatment.getUpdatedAt());
    }
}

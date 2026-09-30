package zw.co.innbucks.loans.core.employment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.NotFoundException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * The treatment of each type of employment event (FR-SSB-024), configured without a release. A change applies to
 * events recorded after it; what an earlier event did to a loan stands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmploymentEventTreatmentService {

    static final String TREATMENT_CHANGED = "EMPLOYMENT_EVENT_TREATMENT_CHANGED";

    private final EmploymentEventTreatmentRepository treatmentRepository;
    private final AuthService authService;
    private final AuditService auditService;

    /** Every type's treatment, in the order the types are declared. */
    @Transactional(readOnly = true)
    public List<EmploymentEventTreatmentResponse> list() {
        return treatmentRepository.findAll().stream()
                .sorted(Comparator.comparing(EmploymentEventTreatment::getEventType))
                .map(EmploymentEventTreatmentResponse::of)
                .toList();
    }

    /** The treatment the next event of this type will get. */
    @Transactional(readOnly = true)
    public EmploymentEventTreatment treatmentFor(EmploymentEventType eventType) {
        return treatmentRepository.findById(eventType)
                .orElseThrow(() -> new IllegalStateException("No treatment is configured for " + eventType));
    }

    /** Replaces one type's treatment, for events recorded from now on. Audited with what it was. */
    @Transactional
    public EmploymentEventTreatmentResponse update(EmploymentEventType eventType,
                                                   UpdateEmploymentEventTreatmentRequest request) {
        EmploymentEventTreatment treatment = treatmentRepository.findById(eventType)
                .orElseThrow(() -> new NotFoundException("No treatment is configured for " + eventType));
        String before = describe(treatment);
        String username = authService.getLoggedInUsername();
        treatment.setApplicationTreatment(request.getApplicationTreatment());
        treatment.setLoanTreatment(request.getLoanTreatment());
        treatment.setNotifyOnDecline(Boolean.TRUE.equals(request.getNotifyOnDecline()));
        treatment.setUpdatedBy(username);
        treatment.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        EmploymentEventTreatment saved = treatmentRepository.save(treatment);
        String after = describe(saved);
        log.info("Employment event treatment for {} changed by {}: {} -> {}", eventType, username, before, after);
        auditService.record(AuditLog.builder()
                .eventType(TREATMENT_CHANGED)
                .entityType("EMPLOYMENT_EVENT_TREATMENT").entityId(eventType.name())
                .actorId(username).channelUsed(EmploymentEventService.PORTAL_CHANNEL)
                .detail("before=" + before + " after=" + after));
        return EmploymentEventTreatmentResponse.of(saved);
    }

    private static String describe(EmploymentEventTreatment treatment) {
        return "applications:" + treatment.getApplicationTreatment() + ",loans:" + treatment.getLoanTreatment()
                + ",notifyOnDecline:" + treatment.isNotifyOnDecline();
    }
}

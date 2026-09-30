package zw.co.innbucks.loans.core.turnaround;

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
 * The service level of each measured stage (FR-PBL-030), changed without a release. A change applies from then on,
 * to items already waiting as well: the target is how long an item should take, whenever it arrived.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceLevelService {

    static final String SERVICE_LEVEL_CHANGED = "SERVICE_LEVEL_CHANGED";

    private final ServiceLevelRepository serviceLevelRepository;
    private final AuthService authService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ServiceLevelResponse> list() {
        return serviceLevelRepository.findAll().stream()
                .sorted(Comparator.comparing(ServiceLevel::getStage))
                .map(ServiceLevelResponse::of)
                .toList();
    }

    /** The stage's service level, as it stands. */
    @Transactional(readOnly = true)
    public ServiceLevel serviceLevel(ServiceLevelStage stage) {
        return serviceLevelRepository.findById(stage)
                .orElseThrow(() -> new IllegalStateException("No service level is configured for " + stage));
    }

    /**
     * Replaces a stage's service level. Audited with what it was.
     *
     * @throws IllegalArgumentException the escalation point is before the target
     */
    @Transactional
    public ServiceLevelResponse update(ServiceLevelStage stage, UpdateServiceLevelRequest request) {
        if (request.getEscalationHours() < request.getTargetHours()) {
            throw new IllegalArgumentException("Escalation hours cannot be fewer than the target hours");
        }
        ServiceLevel level = serviceLevelRepository.findById(stage)
                .orElseThrow(() -> new NotFoundException("No service level is configured for " + stage));
        String before = describe(level);
        String username = authService.getLoggedInUsername();
        level.setTargetHours(request.getTargetHours());
        level.setEscalationHours(request.getEscalationHours());
        level.setUpdatedBy(username);
        level.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        ServiceLevel saved = serviceLevelRepository.save(level);
        String after = describe(saved);
        log.info("Service level for {} changed by {}: {} -> {}", stage, username, before, after);
        auditService.record(AuditLog.builder()
                .eventType(SERVICE_LEVEL_CHANGED)
                .entityType("SERVICE_LEVEL").entityId(stage.name())
                .actorId(username).channelUsed("admin-portal")
                .detail("before=" + before + " after=" + after));
        return ServiceLevelResponse.of(saved);
    }

    private static String describe(ServiceLevel level) {
        return "target:" + level.getTargetHours() + "h,escalation:" + level.getEscalationHours() + "h";
    }
}

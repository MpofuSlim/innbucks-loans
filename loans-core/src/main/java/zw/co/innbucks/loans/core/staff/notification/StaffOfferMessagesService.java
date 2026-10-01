package zw.co.innbucks.loans.core.staff.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;

import java.util.Locale;

/**
 * A member's choice about offer messages (FR-SGL-022), recorded on their behalf until the SuperApp lets them make it
 * themselves. Opted out, they are sent no SMS or WhatsApp about offers or the launch; their offers are still made and
 * still appear in their in-app inbox, so they can still apply. Takes effect for every message not yet sent, those
 * already queued included.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffOfferMessagesService {

    static final String OPTED_OUT = "STAFF_OFFER_MESSAGES_OPTED_OUT";
    static final String OPTED_IN = "STAFF_OFFER_MESSAGES_OPTED_IN";

    private final StaffNotificationPreferenceRepository preferenceRepository;
    private final StaffMemberRepository memberRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /** @throws NotFoundException no such employee */
    @Transactional(readOnly = true)
    public StaffOfferMessagesResponse get(String employeeNumber) {
        StaffMember member = memberOrThrow(employeeNumber);
        return StaffOfferMessagesResponse.of(member, preferenceRepository.findById(member.getId()).orElse(null));
    }

    /** @throws NotFoundException no such employee */
    @Transactional
    public StaffOfferMessagesResponse set(String employeeNumber, StaffOfferMessagesRequest request) {
        StaffMember member = memberOrThrow(employeeNumber);
        String username = authService.getLoggedInUsername();
        String reason = request.getReason().strip();
        StaffNotificationPreference preference = preferenceRepository.findById(member.getId())
                .orElseGet(() -> StaffNotificationPreference.builder().staffMemberId(member.getId()).build());
        preference.setOfferMessagesOptedOut(request.getOptedOut());
        preference.setReason(reason);
        preference.setUpdatedBy(username);
        preference.setUpdatedAt(marketTimeZone.nowUtc());
        preference = preferenceRepository.save(preference);
        String event = request.getOptedOut() ? OPTED_OUT : OPTED_IN;
        log.info("Employee {} {} of offer messages, recorded by {}", member.getEmployeeNumber(),
                request.getOptedOut() ? "opted out" : "opted back in", username);
        auditService.record(AuditLog.builder()
                .eventType(event)
                .entityType("STAFF_MEMBER").entityId(member.getEmployeeNumber())
                .actorId(username).channelUsed("admin-portal")
                .detail("reason:" + reason));
        return StaffOfferMessagesResponse.of(member, preference);
    }

    private StaffMember memberOrThrow(String employeeNumber) {
        String wanted = StringUtils.upperCase(StringUtils.strip(employeeNumber), Locale.ROOT);
        return memberRepository.findByEmployeeNumber(wanted)
                .orElseThrow(() -> new NotFoundException("Employee " + wanted + " is not on the staff register"));
    }
}

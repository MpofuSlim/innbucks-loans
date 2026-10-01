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
 * A member's choice about offer messages (FR-SGL-022): made by the member in the SuperApp, or recorded on their behalf
 * by staff when they ask another way. Opted out, they are sent no SMS or WhatsApp about offers or the launch; their
 * offers are still made and still appear in their in-app inbox, so they can still apply. Takes effect for every message
 * not yet sent, those already queued included.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffOfferMessagesService {

    static final String OPTED_OUT = "STAFF_OFFER_MESSAGES_OPTED_OUT";
    static final String OPTED_IN = "STAFF_OFFER_MESSAGES_OPTED_IN";
    /** The reason recorded when the member makes the choice themselves. */
    static final String CHOSEN_IN_APP = "Chosen in the SuperApp";

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
        StaffNotificationPreference preference = record(member, request.getOptedOut(), request.getReason().strip(),
                "admin-portal");
        return StaffOfferMessagesResponse.of(member, preference);
    }

    /** The borrower session's own choice: whether they receive offer messages. */
    @Transactional(readOnly = true)
    public BorrowerOfferMessages forMember(long staffMemberId) {
        return BorrowerOfferMessages.of(preferenceRepository.findById(memberOrThrow(staffMemberId).getId())
                .orElse(null));
    }

    /** The borrower session's own member choosing, in the SuperApp, whether to receive offer messages. */
    @Transactional
    public BorrowerOfferMessages chooseForMember(long staffMemberId, boolean optedOut) {
        StaffMember member = memberOrThrow(staffMemberId);
        return BorrowerOfferMessages.of(record(member, optedOut, CHOSEN_IN_APP, "superapp"));
    }

    private StaffNotificationPreference record(StaffMember member, boolean optedOut, String reason, String channel) {
        String username = authService.getLoggedInUsername();
        StaffNotificationPreference preference = preferenceRepository.findById(member.getId())
                .orElseGet(() -> StaffNotificationPreference.builder().staffMemberId(member.getId()).build());
        preference.setOfferMessagesOptedOut(optedOut);
        preference.setReason(reason);
        preference.setUpdatedBy(username);
        preference.setUpdatedAt(marketTimeZone.nowUtc());
        preference = preferenceRepository.save(preference);
        log.info("Employee {} {} of offer messages, recorded by {}", member.getEmployeeNumber(),
                optedOut ? "opted out" : "opted back in", username);
        auditService.record(AuditLog.builder()
                .eventType(optedOut ? OPTED_OUT : OPTED_IN)
                .entityType("STAFF_MEMBER").entityId(member.getEmployeeNumber())
                .actorId(username).channelUsed(channel)
                .detail("reason:" + reason));
        return preference;
    }

    private StaffMember memberOrThrow(long staffMemberId) {
        return memberRepository.findById(staffMemberId)
                .orElseThrow(() -> new NotFoundException("Staff member " + staffMemberId + " is not on the staff"
                        + " register"));
    }

    private StaffMember memberOrThrow(String employeeNumber) {
        String wanted = StringUtils.upperCase(StringUtils.strip(employeeNumber), Locale.ROOT);
        return memberRepository.findByEmployeeNumber(wanted)
                .orElseThrow(() -> new NotFoundException("Employee " + wanted + " is not on the staff register"));
    }
}

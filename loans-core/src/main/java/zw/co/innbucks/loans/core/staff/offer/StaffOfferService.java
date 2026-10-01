package zw.co.innbucks.loans.core.staff.offer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatusChanged;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pre-approved offers: what has been offered to whom, and taking an offer back the moment its holder leaves. */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffOfferService {

    static final String WITHDRAWN = "STAFF_OFFER_WITHDRAWN";

    private final StaffOfferRepository offerRepository;
    private final StaffMemberRepository memberRepository;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * Offers, newest first. {@code status} is read as a person would: an ACTIVE offer past its expiry counts as EXPIRED.
     */
    @Transactional(readOnly = true)
    public Page<StaffOfferResponse> offers(StaffOfferStatus status, String employeeNumber, Long runId,
                                           Pageable pageable) {
        LocalDateTime now = marketTimeZone.nowUtc();
        Specification<StaffOffer> filter = (root, query, cb) -> null;
        if (status == StaffOfferStatus.ACTIVE) {
            filter = filter.and((root, query, cb) -> cb.and(cb.equal(root.get("status"), StaffOfferStatus.ACTIVE),
                    cb.greaterThan(root.get("expiresAt"), now)));
        } else if (status == StaffOfferStatus.EXPIRED) {
            filter = filter.and((root, query, cb) -> cb.or(cb.equal(root.get("status"), StaffOfferStatus.EXPIRED),
                    cb.and(cb.equal(root.get("status"), StaffOfferStatus.ACTIVE),
                            cb.lessThanOrEqualTo(root.get("expiresAt"), now))));
        } else if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (StringUtils.isNotBlank(employeeNumber)) {
            String wanted = employeeNumber.strip().toUpperCase(Locale.ROOT);
            Long memberId = memberRepository.findByEmployeeNumber(wanted).map(StaffMember::getId).orElse(-1L);
            filter = filter.and((root, query, cb) -> cb.equal(root.get("staffMemberId"), memberId));
        }
        if (runId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("runId"), runId));
        }
        Page<StaffOffer> page = offerRepository.findAll(filter, PageRequest.of(pageable.getPageNumber(),
                pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id")));
        List<Long> memberIds = page.getContent().stream().map(StaffOffer::getStaffMemberId).distinct().toList();
        Map<Long, StaffMember> members = memberIds.isEmpty() ? Map.of()
                : memberRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(StaffMember::getId, Function.identity()));
        return page.map(offer -> StaffOfferResponse.of(offer, members.get(offer.getStaffMemberId()), now));
    }

    /**
     * A member who stops being ACTIVE loses their open offer at once (FR-SGL-007), in the same transaction as the
     * register change that caused it.
     */
    @EventListener
    public void onEmploymentStatusChanged(StaffEmploymentStatusChanged change) {
        if (change.to() == StaffEmploymentStatus.ACTIVE) {
            return;
        }
        offerRepository.findByStaffMemberIdAndStatus(change.staffMemberId(), StaffOfferStatus.ACTIVE)
                .ifPresent(offer -> {
                    String reason = "Employment status changed to " + change.to();
                    offer.close(StaffOfferStatus.WITHDRAWN, marketTimeZone.nowUtc(), reason);
                    offerRepository.save(offer);
                    log.info("Staff offer {} for employee {} withdrawn: {}", offer.getId(), change.employeeNumber(),
                            reason);
                    auditService.record(AuditLog.builder()
                            .eventType(WITHDRAWN)
                            .entityType("STAFF_OFFER").entityId(String.valueOf(offer.getId()))
                            .actorId(change.approvedBy()).channelUsed("admin-portal")
                            .detail("employee:" + change.employeeNumber() + ";batch:" + change.batchId()
                                    + ";reason:" + reason));
                });
    }
}

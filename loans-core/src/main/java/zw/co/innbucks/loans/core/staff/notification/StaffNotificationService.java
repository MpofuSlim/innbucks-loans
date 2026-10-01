package zw.co.innbucks.loans.core.staff.notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.offer.StaffOffer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Staff Grocery Loan's notifications (FR-SGL-019 to FR-SGL-024): the offer messages a weekly run raises, the
 * one-off launch broadcast, and the reports on what was sent to whom.
 *
 * <p>A notification is created in the same transaction as the offer or broadcast it is about, with its in-app copy
 * stored and logged at once, and at most once per offer and once per member per broadcast (the database enforces both),
 * so a run that is retried or topped up never tells anyone twice. The message to the member's phone goes out after that
 * transaction commits, through {@link StaffNotificationDispatcher}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffNotificationService {

    static final String LAUNCHED = "STAFF_NOTIFICATION_LAUNCH_BROADCAST";
    private static final List<StaffEmploymentStatus> LEFT = Arrays.stream(StaffEmploymentStatus.values())
            .filter(StaffEmploymentStatus::hasLeft).toList();

    private final StaffNotificationRepository notificationRepository;
    private final StaffNotificationDispatchRepository dispatchRepository;
    private final StaffNotificationBroadcastRepository broadcastRepository;
    private final StaffNotificationPreferenceRepository preferenceRepository;
    private final StaffMemberRepository memberRepository;
    private final StaffNotificationDispatcher dispatcher;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Tells each member a run has just issued an offer to (FR-SGL-019): OFFER_REFRESHED when it replaced one they still
     * held, OFFER_NEW otherwise. Runs inside the run's own transaction, so the notifications are kept, and sent, only if
     * the run is.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyOffers(Collection<StaffOffer> offers, Map<Long, StaffMember> members) {
        if (offers.isEmpty()) {
            return;
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        List<StaffNotification> notifications = offers.stream()
                .map(offer -> {
                    StaffNotificationTemplate template = offer.getReplacesOfferId() == null
                            ? StaffNotificationTemplate.OFFER_NEW : StaffNotificationTemplate.OFFER_REFRESHED;
                    return pending(offer.getStaffMemberId(), template, now)
                            .offerId(offer.getId())
                            .runId(offer.getRunId())
                            .message(template.offerText(offer.getAmount(),
                                    marketTimeZone.atMarketFromUtc(offer.getExpiresAt())))
                            .build();
                })
                .toList();
        storeInApp(notifications, members);
        log.info("{} offer notifications queued", notifications.size());
    }

    /** What the launch broadcast would send now, and to how many. */
    @Transactional(readOnly = true)
    public StaffLaunchPreviewResponse launchPreview() {
        List<StaffMember> members = memberRepository.findAll();
        int recipients = (int) members.stream().filter(member -> !member.getEmploymentStatus().hasLeft()).count();
        StaffNotificationTemplate template = StaffNotificationTemplate.LAUNCH;
        return new StaffLaunchPreviewResponse(template, template.version(), template.title(), template.text(),
                recipients, preferenceRepository.countOptedOut(LEFT), members.size() - recipients,
                broadcastRepository.findByKind(StaffNotificationBroadcastKind.LAUNCH)
                        .map(StaffNotificationBroadcast::getId).orElse(null));
    }

    /**
     * Announces the launch to everyone on the staff register who has not left, whether or not they hold an offer
     * (FR-SGL-020). Sent once. A member opted out of offer messages gets the in-app copy only: the announcement is
     * marketing, and their choice stands (FR-GEN-005).
     *
     * @throws ConflictException it was sent already, or the register no longer has {@code expectedRecipients} to tell
     */
    @Transactional
    public StaffNotificationBroadcastResponse launch(LaunchStaffBroadcastRequest request) {
        // The count checked against the preview stays true until this commits.
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        broadcastRepository.findByKind(StaffNotificationBroadcastKind.LAUNCH).ifPresent(earlier -> {
            throw alreadyLaunched(earlier);
        });
        List<StaffMember> members = memberRepository.findAll(Sort.by("employeeNumber"));
        List<StaffMember> recipients = members.stream()
                .filter(member -> !member.getEmploymentStatus().hasLeft()).toList();
        if (recipients.size() != request.getExpectedRecipients()) {
            throw new ConflictException(String.format("The staff register now has %d members to tell, not the %d"
                            + " confirmed; check the launch preview again", recipients.size(),
                    request.getExpectedRecipients()));
        }
        String username = authService.getLoggedInUsername();
        LocalDateTime now = marketTimeZone.nowUtc();
        StaffNotificationTemplate template = StaffNotificationTemplate.LAUNCH;
        StaffNotificationBroadcast broadcast;
        try {
            broadcast = broadcastRepository.saveAndFlush(StaffNotificationBroadcast.builder()
                    .kind(StaffNotificationBroadcastKind.LAUNCH)
                    .template(template)
                    .templateVersion(template.version())
                    .recipients(recipients.size())
                    .leftExcluded(members.size() - recipients.size())
                    .createdBy(username)
                    .createdAt(now)
                    .build());
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException("The launch has just been announced by someone else; it is sent once");
        }
        Long broadcastId = broadcast.getId();
        List<StaffNotification> notifications = recipients.stream()
                .map(member -> pending(member.getId(), template, now)
                        .broadcastId(broadcastId)
                        .message(template.text())
                        .build())
                .toList();
        storeInApp(notifications, recipients.stream()
                .collect(Collectors.toMap(StaffMember::getId, Function.identity())));
        log.info("Launch broadcast {} by {}: {} members told, {} who have left not told", broadcastId, username,
                broadcast.getRecipients(), broadcast.getLeftExcluded());
        auditService.record(AuditLog.builder()
                .eventType(LAUNCHED)
                .entityType("STAFF_NOTIFICATION_BROADCAST").entityId(String.valueOf(broadcastId))
                .actorId(username).channelUsed("admin-portal")
                .detail("template:" + template + ";version:" + template.version() + ";recipients:"
                        + broadcast.getRecipients() + ";leftExcluded:" + broadcast.getLeftExcluded()));
        return StaffNotificationBroadcastResponse.of(broadcast, summary(null, broadcastId, null, null, null));
    }

    /** Broadcasts sent, newest first, each with how it has fared. */
    @Transactional(readOnly = true)
    public List<StaffNotificationBroadcastResponse> broadcasts() {
        return broadcastRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .map(broadcast -> StaffNotificationBroadcastResponse.of(broadcast,
                        summary(null, broadcast.getId(), null, null, null)))
                .toList();
    }

    /** Notifications, newest first, each with every attempt to send it. */
    @Transactional(readOnly = true)
    public Page<StaffNotificationResponse> notifications(String employeeNumber, StaffNotificationTemplate template,
                                                         StaffNotificationOutboundStatus outboundStatus, Long runId,
                                                         Long broadcastId, LocalDate from, LocalDate to,
                                                         Pageable pageable) {
        Specification<StaffNotification> filter = notificationFilter(runId, broadcastId, template, from, to);
        Long memberId = memberIdOf(employeeNumber);
        if (memberId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("staffMemberId"), memberId));
        }
        if (outboundStatus != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("outboundStatus"), outboundStatus));
        }
        Page<StaffNotification> page = notificationRepository.findAll(filter, PageRequest.of(pageable.getPageNumber(),
                pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, StaffMember> members = members(page.getContent().stream()
                .map(StaffNotification::getStaffMemberId).toList());
        List<Long> ids = page.getContent().stream().map(StaffNotification::getId).toList();
        Map<Long, List<StaffNotificationDispatchResponse>> dispatches = ids.isEmpty() ? Map.of()
                : dispatchRepository.findByNotificationIdInOrderByIdAsc(ids).stream()
                .collect(Collectors.groupingBy(StaffNotificationDispatch::getNotificationId,
                        Collectors.mapping(dispatch -> StaffNotificationDispatchResponse.of(dispatch,
                                members.get(dispatch.getStaffMemberId())), Collectors.toList())));
        return page.map(notification -> StaffNotificationResponse.of(notification,
                members.get(notification.getStaffMemberId()),
                dispatches.getOrDefault(notification.getId(), List.of())));
    }

    /** The dispatch log (FR-SGL-023): every attempt on every channel, newest first. */
    @Transactional(readOnly = true)
    public Page<StaffNotificationDispatchResponse> dispatches(String employeeNumber, StaffNotificationChannel channel,
                                                              StaffNotificationDispatchStatus status,
                                                              StaffNotificationTemplate template, Long runId,
                                                              Long broadcastId, LocalDate from, LocalDate to,
                                                              Pageable pageable) {
        checkRange(from, to);
        Specification<StaffNotificationDispatch> filter = (root, query, cb) -> null;
        Long memberId = memberIdOf(employeeNumber);
        if (memberId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("staffMemberId"), memberId));
        }
        if (channel != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("channel"), channel));
        }
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (template != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("template"), template));
        }
        if (runId != null || broadcastId != null) {
            filter = filter.and((root, query, cb) -> {
                Subquery<Long> owners = query.subquery(Long.class);
                Root<StaffNotification> notification = owners.from(StaffNotification.class);
                List<Predicate> where = new ArrayList<>();
                if (runId != null) {
                    where.add(cb.equal(notification.get("runId"), runId));
                }
                if (broadcastId != null) {
                    where.add(cb.equal(notification.get("broadcastId"), broadcastId));
                }
                owners.select(notification.get("id")).where(where.toArray(Predicate[]::new));
                return root.get("notificationId").in(owners);
            });
        }
        if (from != null) {
            LocalDateTime start = marketTimeZone.startOfDayUtc(from);
            filter = filter.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("attemptedAt"), start));
        }
        if (to != null) {
            LocalDateTime end = marketTimeZone.endOfDayUtc(to);
            filter = filter.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("attemptedAt"), end));
        }
        Page<StaffNotificationDispatch> page = dispatchRepository.findAll(filter,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, StaffMember> members = members(page.getContent().stream()
                .map(StaffNotificationDispatch::getStaffMemberId).toList());
        return page.map(dispatch -> StaffNotificationDispatchResponse.of(dispatch,
                members.get(dispatch.getStaffMemberId())));
    }

    /** How the notifications of a run, a broadcast, a template or a period fared. */
    @Transactional(readOnly = true)
    public StaffNotificationSummaryResponse summary(Long runId, Long broadcastId, StaffNotificationTemplate template,
                                                    LocalDate from, LocalDate to) {
        Specification<StaffNotification> filter = notificationFilter(runId, broadcastId, template, from, to);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<StaffNotification> root = query.from(StaffNotification.class);
        query.multiselect(root.get("outboundStatus"), root.get("skipReason"), root.get("deliveredChannel"),
                cb.count(root));
        Predicate where = filter.toPredicate(root, query, cb);
        if (where != null) {
            query.where(where);
        }
        query.groupBy(root.get("outboundStatus"), root.get("skipReason"), root.get("deliveredChannel"));
        Map<StaffNotificationOutboundStatus, Long> outbound = zeros(StaffNotificationOutboundStatus.class);
        Map<StaffNotificationSkipReason, Long> skipped = zeros(StaffNotificationSkipReason.class);
        Map<StaffNotificationChannel, Long> sentBy = zeros(StaffNotificationChannel.class);
        sentBy.remove(StaffNotificationChannel.IN_APP);
        long total = 0;
        for (Tuple row : entityManager.createQuery(query).getResultList()) {
            long count = row.get(3, Long.class);
            total += count;
            outbound.merge(row.get(0, StaffNotificationOutboundStatus.class), count, Long::sum);
            StaffNotificationSkipReason reason = row.get(1, StaffNotificationSkipReason.class);
            if (reason != null) {
                skipped.merge(reason, count, Long::sum);
            }
            StaffNotificationChannel channel = row.get(2, StaffNotificationChannel.class);
            if (channel != null) {
                sentBy.merge(channel, count, Long::sum);
            }
        }
        return new StaffNotificationSummaryResponse(total, outbound, skipped, sentBy);
    }

    /** Starts sending whatever is still PENDING, and says how many that is. */
    public StaffNotificationQueueResponse dispatchPending() {
        long pending = dispatcher.pending();
        dispatcher.requestPass();
        log.info("Staff notification sending started by {}: {} pending", authService.getLoggedInUsername(), pending);
        return new StaffNotificationQueueResponse(pending);
    }

    private StaffNotification.StaffNotificationBuilder pending(Long staffMemberId, StaffNotificationTemplate template,
                                                               LocalDateTime now) {
        return StaffNotification.builder()
                .staffMemberId(staffMemberId)
                .template(template)
                .templateVersion(template.version())
                .title(template.title())
                .createdAt(now)
                .outboundStatus(StaffNotificationOutboundStatus.PENDING);
    }

    /** Saves the notifications with their in-app copies logged, and sends them once this transaction commits. */
    private void storeInApp(List<StaffNotification> notifications, Map<Long, StaffMember> members) {
        List<StaffNotification> saved = notificationRepository.saveAll(notifications);
        dispatchRepository.saveAll(saved.stream()
                .map(notification -> StaffNotificationDispatch.builder()
                        .notificationId(notification.getId())
                        .staffMemberId(notification.getStaffMemberId())
                        .channel(StaffNotificationChannel.IN_APP)
                        .recipient(MsisdnUtils.toE164(members.get(notification.getStaffMemberId()).getMsisdn()))
                        .template(notification.getTemplate())
                        .templateVersion(notification.getTemplateVersion())
                        .status(StaffNotificationDispatchStatus.STORED)
                        .attemptedAt(notification.getCreatedAt())
                        .build())
                .toList());
        dispatcher.afterCommit();
    }

    private Specification<StaffNotification> notificationFilter(Long runId, Long broadcastId,
                                                                StaffNotificationTemplate template, LocalDate from,
                                                                LocalDate to) {
        checkRange(from, to);
        Specification<StaffNotification> filter = (root, query, cb) -> null;
        if (runId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("runId"), runId));
        }
        if (broadcastId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("broadcastId"), broadcastId));
        }
        if (template != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("template"), template));
        }
        if (from != null) {
            LocalDateTime start = marketTimeZone.startOfDayUtc(from);
            filter = filter.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        }
        if (to != null) {
            LocalDateTime end = marketTimeZone.endOfDayUtc(to);
            filter = filter.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), end));
        }
        return filter;
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from (" + from + ") must not be after to (" + to + ")");
        }
    }

    /** The member's id, -1 for an employee number not on the register (so the filter matches nothing), or null. */
    private Long memberIdOf(String employeeNumber) {
        if (StringUtils.isBlank(employeeNumber)) {
            return null;
        }
        return memberRepository.findByEmployeeNumber(employeeNumber.strip().toUpperCase(Locale.ROOT))
                .map(StaffMember::getId).orElse(-1L);
    }

    private Map<Long, StaffMember> members(List<Long> ids) {
        List<Long> distinct = ids.stream().distinct().toList();
        return distinct.isEmpty() ? Map.of() : memberRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(StaffMember::getId, Function.identity()));
    }

    private static <E extends Enum<E>> Map<E, Long> zeros(Class<E> type) {
        Map<E, Long> map = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            map.put(value, 0L);
        }
        return map;
    }

    private static ConflictException alreadyLaunched(StaffNotificationBroadcast earlier) {
        return new ConflictException(String.format("The launch was announced by %s (broadcast %d); it is sent once",
                earlier.getCreatedBy(), earlier.getId()));
    }
}

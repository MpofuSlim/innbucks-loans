package zw.co.innbucks.loans.core.employment;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.TextUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecisionLog;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.LoanSpecification;
import zw.co.innbucks.loans.core.loan.LoanStage;
import zw.co.innbucks.loans.core.loan.SegregationOfDuties;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.user.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl.maskEcNumber;

/**
 * Employment events (FR-SSB-024). An event is recorded against a borrower's EC number, and the treatment configured
 * for its type is applied at once to every one of their applications and loans still open:
 * <ul>
 *   <li>an application not yet paid out CONTINUEs, is HELD (kept from SSB lodgement, credit approval and booking
 *       until an officer releases or declines it) or is DECLINED as a credit rejection for employment;</li>
 *   <li>a loan already paid out, or whose booking has begun, is left alone or opened for REVIEW, for an officer
 *       to decide how it will now be repaid and record it.</li>
 * </ul>
 * Every loan the event found open keeps a record of what was done to it, so the event's effect can be read back.
 * Declined and failed applications, and paid loans whose final deduction fell before the event, are not open.
 */
@Slf4j
@Service
public class EmploymentEventService {

    static final String EVENT_RECORDED = "EMPLOYMENT_EVENT_RECORDED";
    static final String LOAN_EVENT_RESOLVED = "LOAN_EMPLOYMENT_EVENT_RESOLVED";
    /** The credit reason an employment event declines an application with. */
    static final String DECLINE_REASON_CODE = "REJECT_EMPLOYMENT";
    static final String PORTAL_CHANNEL = "admin-portal";
    private static final String EC_NUMBER_FORMAT = "^[0-9]{7}[A-Z]$";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("id"));

    private final EmploymentEventRepository eventRepository;
    private final LoanEmploymentEventRepository loanEventRepository;
    private final EmploymentEventTreatmentService treatmentService;
    private final LoanRepository loanRepository;
    private final CreditDecisionLog creditDecisionLog;
    private final DeductionCancellationService deductionCancellationService;
    private final LoanNotificationService loanNotificationService;
    private final AuthService authService;
    private final AuditService auditService;

    public EmploymentEventService(EmploymentEventRepository eventRepository,
                                  LoanEmploymentEventRepository loanEventRepository,
                                  EmploymentEventTreatmentService treatmentService, LoanRepository loanRepository,
                                  CreditDecisionLog creditDecisionLog,
                                  DeductionCancellationService deductionCancellationService,
                                  LoanNotificationService loanNotificationService, AuthService authService,
                                  AuditService auditService) {
        this.eventRepository = eventRepository;
        this.loanEventRepository = loanEventRepository;
        this.treatmentService = treatmentService;
        this.loanRepository = loanRepository;
        this.creditDecisionLog = creditDecisionLog;
        this.deductionCancellationService = deductionCancellationService;
        this.loanNotificationService = loanNotificationService;
        this.authService = authService;
        this.auditService = auditService;
    }

    /** Where a loan stands for an employment event. */
    enum Standing {
        /** Declined, failed, or repaid before the event: nothing to do. */
        CLOSED,
        /** Not yet paid out, and its booking not begun: the application treatment applies. */
        APPLICATION,
        /** Paid out, being paid out, or its payout delayed: the loan treatment applies. */
        LOAN
    }

    static Standing standingOf(Loan loan, LocalDate effectiveDate) {
        if (loan.getInternalApprovalStatus() == InternalApprovalStatus.REJECTED
                || loan.getLoanApprovalStatus() == LoanApprovalStatus.REJECTED
                || loan.getLoanApprovalStatus() == LoanApprovalStatus.FAILED) {
            return Standing.CLOSED;
        }
        boolean paidOut = loan.getDisbursementStatus() == LoanDisbursementStatus.SUCCESS;
        if (!paidOut && loan.getBookingClaimedAt() == null
                && (loan.getLoanAccountStatus() == null || loan.getLoanAccountStatus() == LoanAccountStatus.PENDING)) {
            return Standing.APPLICATION;
        }
        if (paidOut && loan.getLoanEndDate() != null && loan.getLoanEndDate().isBefore(effectiveDate)) {
            // Its final deduction fell before the event: repaid by then, as far as this service can tell.
            return Standing.CLOSED;
        }
        return Standing.LOAN;
    }

    /**
     * Records an event and applies its type's treatment to the borrower's open applications and loans.
     *
     * @throws IllegalArgumentException the EC number is malformed, or a field the type needs is missing or one it
     *                                  cannot have is given
     * @throws ConflictException        the same event is already recorded for this EC number
     */
    @Transactional
    public EmploymentEventResponse record(RecordEmploymentEventRequest request) {
        String ecNumber = formattedEcNumber(request.getEcNumber());
        EmploymentEventType type = Objects.requireNonNull(request.getEventType(), "eventType");
        LocalDate effectiveDate = Objects.requireNonNull(request.getEffectiveDate(), "effectiveDate");
        requireFieldsFor(type, request);
        if (eventRepository.existsByEcNumberAndEventTypeAndEffectiveDate(ecNumber, type, effectiveDate)) {
            throw duplicate(type, effectiveDate);
        }
        EmploymentEventTreatment treatment = treatmentService.treatmentFor(type);
        String username = authService.getLoggedInUsername();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        EmploymentEvent event;
        try {
            event = eventRepository.saveAndFlush(EmploymentEvent.builder()
                    .ecNumber(ecNumber)
                    .eventType(type)
                    .effectiveDate(effectiveDate)
                    .endDate(request.getEndDate())
                    .ministry(StringUtils.trimToNull(request.getMinistry()))
                    .station(StringUtils.trimToNull(request.getStation()))
                    .grade(StringUtils.trimToNull(request.getGrade()))
                    .note(StringUtils.trimToNull(request.getNote()))
                    .recordedBy(username)
                    .recordedAt(now)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // The same event recorded at the same moment by someone else.
            throw duplicate(type, effectiveDate);
        }

        Map<LoanEmploymentEventAction, Integer> counts = new EnumMap<>(LoanEmploymentEventAction.class);
        List<LoanEmploymentEvent> applied = new ArrayList<>();
        // Locked in id order, the order every other writer takes loans in, so two writers cannot deadlock.
        for (Loan listed : loanRepository.findByEcNumberOrderByIdAsc(ecNumber)) {
            Loan loan = loanRepository.findByIdForUpdate(listed.getId()).orElse(null);
            if (loan == null) {
                continue;
            }
            LoanEmploymentEventAction action = switch (standingOf(loan, effectiveDate)) {
                case CLOSED -> null;
                case APPLICATION -> switch (treatment.getApplicationTreatment()) {
                    case CONTINUE -> LoanEmploymentEventAction.NONE;
                    case HOLD -> LoanEmploymentEventAction.HOLD;
                    case DECLINE -> LoanEmploymentEventAction.DECLINE;
                };
                case LOAN -> treatment.getLoanTreatment() == LoanTreatment.REVIEW
                        ? LoanEmploymentEventAction.REVIEW : LoanEmploymentEventAction.NONE;
            };
            if (action == null) {
                continue;
            }
            boolean open = action == LoanEmploymentEventAction.HOLD || action == LoanEmploymentEventAction.REVIEW;
            String comment = null;
            if (action == LoanEmploymentEventAction.DECLINE) {
                comment = "Declined on the " + describe(type) + " effective " + effectiveDate
                        + " (employment event " + event.getId() + ")";
                decline(loan, comment, username, now, treatment.isNotifyOnDecline());
            }
            applied.add(loanEventRepository.save(LoanEmploymentEvent.builder()
                    .eventId(event.getId())
                    .loanId(loan.getId())
                    .action(action)
                    .notifyOnDecline(treatment.isNotifyOnDecline())
                    .status(open ? LoanEmploymentEventStatus.OPEN : LoanEmploymentEventStatus.CLOSED)
                    .outcome(action == LoanEmploymentEventAction.DECLINE ? LoanEmploymentEventOutcome.DECLINED : null)
                    .comment(comment)
                    .resolvedBy(action == LoanEmploymentEventAction.DECLINE ? username : null)
                    .resolvedAt(action == LoanEmploymentEventAction.DECLINE ? now : null)
                    .createdAt(now)
                    .build()));
            counts.merge(action, 1, Integer::sum);
            if (action != LoanEmploymentEventAction.NONE) {
                log.warn("EMPLOYMENT EVENT: loan {} ({}) {} after a {} effective {} for ec {} (event {})",
                        loan.getReference(), LoanStage.of(loan), pastTense(action), type, effectiveDate,
                        maskEcNumber(ecNumber), event.getId());
            }
        }

        log.info("Employment event {} recorded by {}: {} for ec {} effective {}; loans {}", event.getId(), username,
                type, maskEcNumber(ecNumber), effectiveDate, counts.isEmpty() ? "none open" : counts);
        audit(EVENT_RECORDED, "EMPLOYMENT_EVENT", String.valueOf(event.getId()), username,
                "type=" + type + " ec=" + maskEcNumber(ecNumber) + " effective=" + effectiveDate
                        + " treatment=" + treatment.getApplicationTreatment() + "/" + treatment.getLoanTreatment()
                        + " loans=" + counts, null);
        return toResponse(event, applied);
    }

    /** Events newest first, every EC number's or one's. */
    @Transactional(readOnly = true)
    public Page<EmploymentEventResponse> find(String ecNumber, Pageable pageable) {
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
        Page<EmploymentEvent> events = StringUtils.isBlank(ecNumber)
                ? eventRepository.findAll(newestFirst)
                : eventRepository.findByEcNumber(formattedEcNumber(ecNumber), newestFirst);
        Map<Long, List<LoanEmploymentEvent>> byEvent = loanEventRepository
                .findByEventIdInOrderByIdAsc(events.stream().map(EmploymentEvent::getId).toList()).stream()
                .collect(Collectors.groupingBy(LoanEmploymentEvent::getEventId));
        Map<Long, Loan> loans = loansOf(byEvent.values().stream().flatMap(List::stream));
        return events.map(event -> toResponse(event, byEvent.getOrDefault(event.getId(), List.of()), loans));
    }

    /** One event and what it did to each loan. */
    @Transactional(readOnly = true)
    public EmploymentEventResponse get(Long eventId) {
        EmploymentEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Employment event " + eventId + " not found"));
        return toResponse(event, loanEventRepository.findByEventIdInOrderByIdAsc(List.of(eventId)));
    }

    /** The officers' queue: held applications and loans under review, oldest first. */
    @Transactional(readOnly = true)
    public List<LoanEmploymentEventResponse> queue() {
        return toResponses(loanEventRepository.findByStatusOrderByIdAsc(LoanEmploymentEventStatus.OPEN));
    }

    /**
     * What employment events did to one loan, oldest first, in the caller's loan scope.
     *
     * @throws NotFoundException no such loan, or not one the caller may read
     */
    @Transactional(readOnly = true)
    public List<LoanEmploymentEventResponse> forLoan(Long loanId, LoanReadScope scope) {
        boolean readable = scope.platformWide()
                ? loanRepository.existsById(loanId)
                : loanRepository.exists(LoanSpecification.readableBy(loanId, scope));
        if (!readable) {
            throw new NotFoundException("Loan " + loanId + " not found");
        }
        return toResponses(loanEventRepository.findByLoanIdOrderByIdAsc(loanId));
    }

    /**
     * Resolves a held application (RELEASED or DECLINED) or a loan under review (REVIEWED), once.
     *
     * @throws NotFoundException        no such record
     * @throws ConflictException        already resolved, or releasing an application that has since been declined
     * @throws IllegalArgumentException an outcome that does not fit a hold or a review
     * @throws AccessDeniedException    releasing an application the officer originated or is a party to
     */
    @Transactional
    public LoanEmploymentEventResponse resolve(Long id, ResolveLoanEmploymentEventRequest request) {
        LoanEmploymentEventOutcome outcome = Objects.requireNonNull(request.getOutcome(), "outcome");
        String comment = StringUtils.trimToNull(request.getComment());
        if (comment == null) {
            throw new IllegalArgumentException("Comment is required");
        }
        LoanEmploymentEvent loanEvent = loanEventRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Loan employment event " + id + " not found"));
        if (loanEvent.getStatus() != LoanEmploymentEventStatus.OPEN) {
            throw new ConflictException(String.format("Loan employment event %d is already resolved (%s)", id,
                    loanEvent.getOutcome() == null ? loanEvent.getAction() : loanEvent.getOutcome()));
        }
        requireOutcomeFits(loanEvent.getAction(), outcome);
        Loan loan = loanRepository.findByIdForUpdate(loanEvent.getLoanId())
                .orElseThrow(() -> new NotFoundException("Loan " + loanEvent.getLoanId() + " not found"));
        String username = authService.getLoggedInUsername();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean declined = loan.getInternalApprovalStatus() == InternalApprovalStatus.REJECTED
                || loan.getLoanApprovalStatus() == LoanApprovalStatus.REJECTED
                || loan.getLoanApprovalStatus() == LoanApprovalStatus.FAILED;

        if (outcome == LoanEmploymentEventOutcome.RELEASED) {
            if (declined) {
                throw new ConflictException(String.format(
                        "Loan %s has since been declined or failed; resolve its hold as DECLINED", loan.getReference()));
            }
            // Releasing lets the application go on to be paid, so it is held to the same rule as approving it.
            requireNoConflictOfInterest(loan, username);
        } else if (outcome == LoanEmploymentEventOutcome.DECLINED && !declined) {
            decline(loan, comment, username, now, loanEvent.isNotifyOnDecline());
        }
        loanEvent.resolve(outcome, comment, username, now);
        LoanEmploymentEvent saved = loanEventRepository.save(loanEvent);

        log.info("Loan {} employment event {} resolved {} by {}", loan.getReference(), id, outcome, username);
        audit(LOAN_EVENT_RESOLVED, "LOAN", String.valueOf(loan.getId()), username,
                "loanEmploymentEvent=" + id + " event=" + loanEvent.getEventId() + " action=" + loanEvent.getAction()
                        + " outcome=" + outcome, loan.getReference());
        return toResponses(List.of(saved)).getFirst();
    }

    /**
     * Declines an application as a credit rejection for employment, in the decision log like any other; a deduction
     * already lodged with SSB is flagged for cancellation, as for any credit rejection. The applicant is sent the
     * decline, which says nothing of why, once the transaction commits and only if the treatment says so.
     */
    private void decline(Loan loan, String comment, String username, LocalDateTime now, boolean notify) {
        loan.setInternalApprovalStatus(InternalApprovalStatus.REJECTED);
        loan.setInternalApprovalDate(now);
        loan.setInternalApprovalBy(username);
        loan.setInternalApprovalComment(StringUtils.left(comment, 255));
        loan.setInternalApprovalReasonCode(DECLINE_REASON_CODE);
        if (DeductionCancellationService.wasLodged(loan)) {
            deductionCancellationService.markRequired(loan, DeductionCancellationService.REASON_CREDIT_REJECTED,
                    username, PORTAL_CHANNEL);
        }
        Loan saved = loanRepository.save(loan);
        creditDecisionLog.record(saved, CreditAction.REJECTED, DECLINE_REASON_CODE, StringUtils.left(comment, 255),
                username, now);
        if (notify) {
            loanNotificationService.notify(saved, LoanNotice.DECLINED);
        }
    }

    private void requireNoConflictOfInterest(Loan loan, String officer) {
        if (SegregationOfDuties.originated(loan, officer)) {
            throw new AccessDeniedException(String.format(
                    "Loan %s was originated by %s, who cannot also release its hold; another credit officer must",
                    loan.getReference(), officer));
        }
        User user = authService.getLoggedInUser();
        if (SegregationOfDuties.isPartyTo(loan, user)) {
            throw new AccessDeniedException(String.format(
                    "%s is a party to loan %s and cannot release its hold; another credit officer must",
                    officer, loan.getReference()));
        }
    }

    private static void requireOutcomeFits(LoanEmploymentEventAction action, LoanEmploymentEventOutcome outcome) {
        boolean fits = action == LoanEmploymentEventAction.HOLD
                ? outcome == LoanEmploymentEventOutcome.RELEASED || outcome == LoanEmploymentEventOutcome.DECLINED
                : outcome == LoanEmploymentEventOutcome.REVIEWED;
        if (!fits) {
            throw new IllegalArgumentException(action == LoanEmploymentEventAction.HOLD
                    ? "A held application is resolved as RELEASED or DECLINED"
                    : "A loan under review is resolved as REVIEWED");
        }
    }

    private static void requireFieldsFor(EmploymentEventType type, RecordEmploymentEventRequest request) {
        if (type.requiresMinistry() && StringUtils.isBlank(request.getMinistry())) {
            throw new IllegalArgumentException("A " + describe(type) + " names the ministry the employee moves to");
        }
        if (type.requiresGrade() && StringUtils.isBlank(request.getGrade())) {
            throw new IllegalArgumentException("A " + describe(type) + " names the new grade or notch");
        }
        if (request.getEndDate() != null) {
            if (!type.mayEnd()) {
                throw new IllegalArgumentException("Only a secondment, suspension or unpaid leave has an end date");
            }
            if (request.getEndDate().isBefore(request.getEffectiveDate())) {
                throw new IllegalArgumentException("The end date cannot be before the effective date");
            }
        }
    }

    /** Stored as a loan stores it, so the two match: special characters removed, upper case. */
    private static String formattedEcNumber(String ecNumber) {
        String formatted = TextUtils.trimSpecialCharacters(StringUtils.defaultString(ecNumber)).toUpperCase();
        if (!formatted.matches(EC_NUMBER_FORMAT)) {
            throw new IllegalArgumentException("EC Number is not valid");
        }
        return formatted;
    }

    private static ConflictException duplicate(EmploymentEventType type, LocalDate effectiveDate) {
        return new ConflictException(String.format(
                "A %s effective %s is already recorded for this EC number", describe(type), effectiveDate));
    }

    /** "death in service", "unpaid leave", "transfer". */
    private static String describe(EmploymentEventType type) {
        return type.name().toLowerCase().replace('_', ' ');
    }

    private static String pastTense(LoanEmploymentEventAction action) {
        return switch (action) {
            case NONE -> "carries on";
            case HOLD -> "held for review";
            case DECLINE -> "declined";
            case REVIEW -> "opened for review";
        };
    }

    private EmploymentEventResponse toResponse(EmploymentEvent event, List<LoanEmploymentEvent> loanEvents) {
        return toResponse(event, loanEvents, loansOf(loanEvents.stream()));
    }

    private static EmploymentEventResponse toResponse(EmploymentEvent event, List<LoanEmploymentEvent> loanEvents,
                                                      Map<Long, Loan> loans) {
        return new EmploymentEventResponse(event.getId(), event.getEcNumber(), event.getEventType(),
                event.getEffectiveDate(), event.getEndDate(), event.getMinistry(), event.getStation(), event.getGrade(),
                event.getNote(), event.getRecordedBy(), event.getRecordedAt(),
                loanEvents.stream().map(loanEvent -> toResponse(loanEvent, event, loans.get(loanEvent.getLoanId())))
                        .toList());
    }

    private List<LoanEmploymentEventResponse> toResponses(List<LoanEmploymentEvent> loanEvents) {
        if (loanEvents.isEmpty()) {
            return List.of();
        }
        Map<Long, EmploymentEvent> events = eventRepository.findByIdIn(loanEvents.stream()
                        .map(LoanEmploymentEvent::getEventId).distinct().toList()).stream()
                .collect(Collectors.toMap(EmploymentEvent::getId, Function.identity()));
        Map<Long, Loan> loans = loansOf(loanEvents.stream());
        return loanEvents.stream()
                .map(loanEvent -> toResponse(loanEvent, events.get(loanEvent.getEventId()),
                        loans.get(loanEvent.getLoanId())))
                .toList();
    }

    private Map<Long, Loan> loansOf(Stream<LoanEmploymentEvent> loanEvents) {
        List<Long> ids = loanEvents.map(LoanEmploymentEvent::getLoanId).distinct().toList();
        return ids.isEmpty() ? Map.of() : loanRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Loan::getId, Function.identity()));
    }

    private static LoanEmploymentEventResponse toResponse(LoanEmploymentEvent loanEvent, EmploymentEvent event,
                                                          Loan loan) {
        return new LoanEmploymentEventResponse(loanEvent.getId(), loanEvent.getEventId(),
                event == null ? null : event.getEventType(), event == null ? null : event.getEffectiveDate(),
                loanEvent.getLoanId(), loan == null ? null : loan.getReference(),
                loan == null ? null : User.fullName(loan.getFirstName(), loan.getLastName()),
                loan == null ? null : LoanStage.of(loan), loanEvent.getAction(), loanEvent.getStatus(),
                loanEvent.getOutcome(), loanEvent.getComment(), loanEvent.getResolvedBy(), loanEvent.getResolvedAt(),
                loanEvent.getCreatedAt());
    }

    private void audit(String eventType, String entityType, String entityId, String actor, String detail,
                       String correlationId) {
        try {
            auditService.record(AuditLog.builder()
                    .eventType(eventType)
                    .entityType(entityType).entityId(entityId)
                    .actorId(actor).channelUsed(PORTAL_CHANNEL)
                    .detail(detail)
                    .correlationId(correlationId));
        } catch (RuntimeException ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while opening the
            // transaction; the change itself is saved and must stand.
            log.error("Audit {} of {} {} failed", eventType, entityType, entityId, ex);
        }
    }
}

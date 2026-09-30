package zw.co.innbucks.loans.core.employment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecisionLog;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.DeductionCancellationStatus;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.LoanStage;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.workflow.WorkAssignmentGuard;
import zw.co.innbucks.loans.core.workflow.SystemStage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * An employment event applies its type's treatment to the borrower's open applications and loans (FR-SSB-024), and
 * an officer resolves what it held or opened for review.
 */
class EmploymentEventServiceTest {

    private static final String EC = "1234567A";
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 10, 1);

    private EmploymentEventRepository eventRepository;
    private LoanEmploymentEventRepository loanEventRepository;
    private EmploymentEventTreatmentService treatmentService;
    private LoanRepository loanRepository;
    private CreditDecisionLog creditDecisionLog;
    private LoanNotificationService loanNotificationService;
    private AuditService auditService;
    private AuthService authService;
    private WorkAssignmentGuard workAssignmentGuard;
    private EmploymentEventService service;
    private final Map<Long, Loan> loans = new HashMap<>();
    private final List<LoanEmploymentEvent> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        eventRepository = mock(EmploymentEventRepository.class);
        loanEventRepository = mock(LoanEmploymentEventRepository.class);
        treatmentService = mock(EmploymentEventTreatmentService.class);
        loanRepository = mock(LoanRepository.class);
        creditDecisionLog = mock(CreditDecisionLog.class);
        loanNotificationService = mock(LoanNotificationService.class);
        auditService = mock(AuditService.class);
        authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("cmanager");
        User officer = new User();
        officer.setUsername("cmanager");
        when(authService.getLoggedInUser()).thenReturn(officer);

        when(eventRepository.saveAndFlush(any())).thenAnswer(i -> withId(i.getArgument(0), 5L));
        when(loanEventRepository.save(any())).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loanRepository.findByEcNumberOrderByIdAsc(EC)).thenAnswer(i -> loans.values().stream()
                .sorted(Comparator.comparing(Loan::getId)).toList());
        when(loanRepository.findByIdForUpdate(any())).thenAnswer(i -> Optional.ofNullable(loans.get(i.<Long>getArgument(0))));
        when(loanRepository.findAllById(anyList())).thenAnswer(i -> i.<List<Long>>getArgument(0).stream()
                .map(loans::get).toList());

        workAssignmentGuard = mock(WorkAssignmentGuard.class);
        service = new EmploymentEventService(eventRepository, loanEventRepository, treatmentService, loanRepository,
                creditDecisionLog, new DeductionCancellationService(loanRepository, auditService, authService,
                        mock(WorkAssignmentGuard.class), new MarketTimeZone("ZW")),
                loanNotificationService, authService, auditService, workAssignmentGuard);
    }

    private static EmploymentEvent withId(EmploymentEvent event, Long id) {
        return EmploymentEvent.builder().id(id).ecNumber(event.getEcNumber()).eventType(event.getEventType())
                .effectiveDate(event.getEffectiveDate()).endDate(event.getEndDate()).ministry(event.getMinistry())
                .station(event.getStation()).grade(event.getGrade()).note(event.getNote())
                .recordedBy(event.getRecordedBy()).recordedAt(event.getRecordedAt()).build();
    }

    private void treatment(EmploymentEventType type, ApplicationTreatment applications, LoanTreatment paidLoans,
                           boolean notify) {
        when(treatmentService.treatmentFor(type)).thenReturn(EmploymentEventTreatment.builder().eventType(type)
                .applicationTreatment(applications).loanTreatment(paidLoans).notifyOnDecline(notify).build());
    }

    /** An application just received: NEW with SSB, undecided by Credit. */
    private Loan application(long id) {
        Loan loan = Loan.builder().ecNumber(EC).firstName("Rudo").lastName("Chikwanha").createdBy("tmoyo")
                .mobileNumber("263771234567")
                .loanApprovalStatus(LoanApprovalStatus.NEW).internalApprovalStatus(InternalApprovalStatus.PENDING)
                .loanAccountStatus(LoanAccountStatus.PENDING).disbursementStatus(LoanDisbursementStatus.PENDING).build();
        loan.setId(id);
        loans.put(id, loan);
        return loan;
    }

    /** A loan paid out and being repaid until the end of next year. */
    private Loan paidLoan(long id) {
        Loan loan = application(id);
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        loan.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        loan.setLoanEndDate(LocalDate.of(2027, 12, 31));
        return loan;
    }

    private static RecordEmploymentEventRequest request(EmploymentEventType type) {
        return RecordEmploymentEventRequest.builder().ecNumber(EC).eventType(type).effectiveDate(EFFECTIVE).build();
    }

    private Map<Long, LoanEmploymentEvent> savedByLoan() {
        return saved.stream().collect(Collectors.toMap(LoanEmploymentEvent::getLoanId, Function.identity()));
    }

    @Nested
    @DisplayName("where a loan stands")
    class Standing {

        @Test
        @DisplayName("an application is anything not yet paid out whose booking has not begun")
        void applications() {
            Loan received = application(1);
            Loan approved = application(2);
            approved.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
            approved.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
            Loan returned = application(3);
            returned.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
            returned.setInternalApprovalStatus(InternalApprovalStatus.RETURNED);

            for (Loan loan : List.of(received, approved, returned)) {
                assertThat(EmploymentEventService.standingOf(loan, EFFECTIVE)).as(LoanStage.of(loan).name())
                        .isEqualTo(EmploymentEventService.Standing.APPLICATION);
            }
        }

        @Test
        @DisplayName("a loan is anything paid out, being booked or paid, or whose payout is delayed")
        void loans() {
            Loan claimed = application(1);
            claimed.setBookingClaimedAt(LocalDateTime.of(2026, 9, 30, 8, 0));
            Loan booked = paidLoan(2);
            booked.setDisbursementStatus(LoanDisbursementStatus.PENDING);
            Loan refused = application(3);
            refused.setLoanAccountStatus(LoanAccountStatus.FAILED);
            Loan paid = paidLoan(4);
            Loan paidNoEndDate = paidLoan(5);
            paidNoEndDate.setLoanEndDate(null);

            for (Loan loan : List.of(claimed, booked, refused, paid, paidNoEndDate)) {
                assertThat(EmploymentEventService.standingOf(loan, EFFECTIVE)).as("loan " + loan.getId())
                        .isEqualTo(EmploymentEventService.Standing.LOAN);
            }
        }

        @Test
        @DisplayName("declined, failed, and paid loans whose final deduction fell before the event are closed")
        void closed() {
            Loan byCredit = application(1);
            byCredit.setInternalApprovalStatus(InternalApprovalStatus.REJECTED);
            Loan bySsb = application(2);
            bySsb.setLoanApprovalStatus(LoanApprovalStatus.REJECTED);
            Loan failed = application(3);
            failed.setLoanApprovalStatus(LoanApprovalStatus.FAILED);
            Loan repaid = paidLoan(4);
            repaid.setLoanEndDate(EFFECTIVE.minusDays(1));

            for (Loan loan : List.of(byCredit, bySsb, failed, repaid)) {
                assertThat(EmploymentEventService.standingOf(loan, EFFECTIVE)).as("loan " + loan.getId())
                        .isEqualTo(EmploymentEventService.Standing.CLOSED);
            }
            Loan endsThatDay = paidLoan(5);
            endsThatDay.setLoanEndDate(EFFECTIVE);
            assertThat(EmploymentEventService.standingOf(endsThatDay, EFFECTIVE))
                    .isEqualTo(EmploymentEventService.Standing.LOAN);
        }
    }

    @Nested
    @DisplayName("recording an event")
    class Recording {

        @Test
        @DisplayName("a suspension holds the open application, opens the paid loan for review, and passes the declined one")
        void suspensionHoldsAndReviews() {
            treatment(EmploymentEventType.SUSPENSION, ApplicationTreatment.HOLD, LoanTreatment.REVIEW, true);
            application(42);
            paidLoan(30);
            application(50).setInternalApprovalStatus(InternalApprovalStatus.REJECTED);
            RecordEmploymentEventRequest request = request(EmploymentEventType.SUSPENSION);
            request.setEndDate(LocalDate.of(2026, 12, 31));
            request.setNote("  Suspension letter, ref PSC/2026/0912 ");

            EmploymentEventResponse response = service.record(request);

            assertThat(response.id()).isEqualTo(5L);
            assertThat(response.endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(response.note()).isEqualTo("Suspension letter, ref PSC/2026/0912");
            assertThat(response.recordedBy()).isEqualTo("cmanager");
            assertThat(response.loans()).extracting(LoanEmploymentEventResponse::loanId,
                            LoanEmploymentEventResponse::action, LoanEmploymentEventResponse::status)
                    .containsExactly(tuple(30L, LoanEmploymentEventAction.REVIEW, LoanEmploymentEventStatus.OPEN),
                            tuple(42L, LoanEmploymentEventAction.HOLD, LoanEmploymentEventStatus.OPEN));
            assertThat(response.loans().get(1).applicantName()).isEqualTo("Rudo Chikwanha");
            assertThat(response.loans().get(1).loanReference()).isEqualTo("000000042");
            // Nothing about the loans themselves changes: the hold lives in its own record.
            assertThat(loans.get(42L).getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.PENDING);
            verify(loanRepository, never()).save(any());
            verifyNoInteractions(creditDecisionLog, loanNotificationService);
            ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
            verify(auditService).record(audit.capture());
            assertThat(audit.getValue().build().getEventType()).isEqualTo("EMPLOYMENT_EVENT_RECORDED");
            assertThat(audit.getValue().build().getDetail()).contains("type=SUSPENSION", "ec=*****67A",
                    "treatment=HOLD/REVIEW").doesNotContain(EC);
        }

        @Test
        @DisplayName("a resignation declines the application as a credit rejection, flags a lodged deduction and tells the applicant")
        void resignationDeclines() {
            treatment(EmploymentEventType.RESIGNATION, ApplicationTreatment.DECLINE, LoanTreatment.REVIEW, true);
            Loan received = application(43);
            Loan lodged = application(44);
            lodged.setLoanApprovalStatus(LoanApprovalStatus.PROCESSING);
            lodged.setBatchNumber("BATCH-20261001-02");

            service.record(request(EmploymentEventType.RESIGNATION));

            for (Loan loan : List.of(received, lodged)) {
                assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
                assertThat(loan.getInternalApprovalReasonCode()).isEqualTo("REJECT_EMPLOYMENT");
                assertThat(loan.getInternalApprovalBy()).isEqualTo("cmanager");
                assertThat(loan.getInternalApprovalComment())
                        .isEqualTo("Declined on the resignation effective 2026-10-01 (employment event 5)");
                assertThat(LoanStage.of(loan)).isEqualTo(LoanStage.DECLINED);
                verify(creditDecisionLog).record(eq(loan), eq(CreditAction.REJECTED), eq("REJECT_EMPLOYMENT"),
                        eq("Declined on the resignation effective 2026-10-01 (employment event 5)"), eq("cmanager"),
                        any());
                verify(loanNotificationService).notify(loan, LoanNotice.DECLINED);
            }
            assertThat(received.getDeductionCancellationStatus()).isNull();
            assertThat(lodged.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
            assertThat(lodged.getDeductionCancellationReason()).isEqualTo("CREDIT_REJECTED");
            assertThat(savedByLoan().get(43L)).satisfies(row -> {
                assertThat(row.getAction()).isEqualTo(LoanEmploymentEventAction.DECLINE);
                assertThat(row.getStatus()).isEqualTo(LoanEmploymentEventStatus.CLOSED);
                assertThat(row.getOutcome()).isEqualTo(LoanEmploymentEventOutcome.DECLINED);
                assertThat(row.getResolvedBy()).isEqualTo("cmanager");
            });
        }

        @Test
        @DisplayName("a death in service declines without messaging the phone, when the treatment says so")
        void deathInServiceDeclinesSilently() {
            treatment(EmploymentEventType.DEATH_IN_SERVICE, ApplicationTreatment.DECLINE, LoanTreatment.REVIEW, false);
            Loan received = application(43);
            paidLoan(30);

            service.record(request(EmploymentEventType.DEATH_IN_SERVICE));

            assertThat(received.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
            verifyNoInteractions(loanNotificationService);
            assertThat(savedByLoan().get(30L).getAction()).isEqualTo(LoanEmploymentEventAction.REVIEW);
            assertThat(savedByLoan().get(43L).isNotifyOnDecline()).isFalse();
        }

        @Test
        @DisplayName("a transfer is recorded against every open loan and changes nothing")
        void transferContinues() {
            treatment(EmploymentEventType.TRANSFER, ApplicationTreatment.CONTINUE, LoanTreatment.NONE, true);
            application(42);
            paidLoan(30);
            RecordEmploymentEventRequest request = request(EmploymentEventType.TRANSFER);
            request.setMinistry(" Ministry of Health and Child Care ");
            request.setStation("Mpilo Central Hospital");

            EmploymentEventResponse response = service.record(request);

            assertThat(response.ministry()).isEqualTo("Ministry of Health and Child Care");
            assertThat(saved).extracting(LoanEmploymentEvent::getAction, LoanEmploymentEvent::getStatus)
                    .containsOnly(tuple(LoanEmploymentEventAction.NONE, LoanEmploymentEventStatus.CLOSED));
            assertThat(saved).hasSize(2);
            verify(loanRepository, never()).save(any());
            verifyNoInteractions(creditDecisionLog, loanNotificationService);
        }

        @Test
        @DisplayName("a borrower with no open loan still has the event recorded, touching nothing")
        void noOpenLoans() {
            treatment(EmploymentEventType.RETIREMENT, ApplicationTreatment.HOLD, LoanTreatment.REVIEW, true);

            EmploymentEventResponse response = service.record(request(EmploymentEventType.RETIREMENT));

            assertThat(response.loans()).isEmpty();
            verify(eventRepository).saveAndFlush(any());
            verifyNoInteractions(loanEventRepository);
        }

        @Test
        @DisplayName("the EC number is stored as a loan stores it, so the two match however it was typed")
        void ecNumberIsNormalised() {
            treatment(EmploymentEventType.RETIREMENT, ApplicationTreatment.HOLD, LoanTreatment.REVIEW, true);
            application(42);
            RecordEmploymentEventRequest request = request(EmploymentEventType.RETIREMENT);
            request.setEcNumber(" 12-34567 a ");

            EmploymentEventResponse response = service.record(request);

            assertThat(response.ecNumber()).isEqualTo(EC);
            assertThat(response.loans()).hasSize(1);
        }

        @Test
        @DisplayName("each type's required fields, and only a temporary event's end date, are enforced before anything is saved")
        void fieldsAreChecked() {
            RecordEmploymentEventRequest badEc = request(EmploymentEventType.RETIREMENT);
            badEc.setEcNumber("12345");
            RecordEmploymentEventRequest transfer = request(EmploymentEventType.TRANSFER);
            RecordEmploymentEventRequest secondment = request(EmploymentEventType.SECONDMENT);
            RecordEmploymentEventRequest promotion = request(EmploymentEventType.PROMOTION);
            RecordEmploymentEventRequest endingResignation = request(EmploymentEventType.RESIGNATION);
            endingResignation.setEndDate(EFFECTIVE.plusMonths(1));
            RecordEmploymentEventRequest endsBefore = request(EmploymentEventType.SUSPENSION);
            endsBefore.setEndDate(EFFECTIVE.minusDays(1));

            assertThatThrownBy(() -> service.record(badEc)).hasMessage("EC Number is not valid");
            assertThatThrownBy(() -> service.record(transfer))
                    .hasMessage("A transfer names the ministry the employee moves to");
            assertThatThrownBy(() -> service.record(secondment))
                    .hasMessage("A secondment names the ministry the employee moves to");
            assertThatThrownBy(() -> service.record(promotion)).hasMessage("A promotion names the new grade or notch");
            assertThatThrownBy(() -> service.record(endingResignation))
                    .hasMessage("Only a secondment, suspension or unpaid leave has an end date");
            assertThatThrownBy(() -> service.record(endsBefore))
                    .hasMessage("The end date cannot be before the effective date");
            verify(eventRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("the same event twice is a conflict, whether seen first or lost in a race, and changes nothing")
        void duplicateIsAConflict() {
            treatment(EmploymentEventType.SUSPENSION, ApplicationTreatment.HOLD, LoanTreatment.REVIEW, true);
            application(42);
            when(eventRepository.existsByEcNumberAndEventTypeAndEffectiveDate(EC, EmploymentEventType.SUSPENSION,
                    EFFECTIVE)).thenReturn(true);

            assertThatThrownBy(() -> service.record(request(EmploymentEventType.SUSPENSION)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("A suspension effective 2026-10-01 is already recorded for this EC number");

            when(eventRepository.existsByEcNumberAndEventTypeAndEffectiveDate(any(), any(), any())).thenReturn(false);
            doThrow(new DataIntegrityViolationException("uq")).when(eventRepository).saveAndFlush(any());
            assertThatThrownBy(() -> service.record(request(EmploymentEventType.SUSPENSION)))
                    .isInstanceOf(ConflictException.class);
            verifyNoInteractions(loanEventRepository);
        }
    }

    @Nested
    @DisplayName("resolving a hold or a review")
    class Resolving {

        private LoanEmploymentEvent open(long id, long loanId, LoanEmploymentEventAction action, boolean notify) {
            LoanEmploymentEvent row = LoanEmploymentEvent.builder().id(id).eventId(5L).loanId(loanId).action(action)
                    .notifyOnDecline(notify).status(LoanEmploymentEventStatus.OPEN)
                    .createdAt(LocalDateTime.of(2026, 10, 2, 7, 14, 5)).build();
            when(loanEventRepository.findByIdForUpdate(id)).thenReturn(Optional.of(row));
            when(eventRepository.findByIdIn(any())).thenReturn(List.of(EmploymentEvent.builder().id(5L).ecNumber(EC)
                    .eventType(EmploymentEventType.SUSPENSION).effectiveDate(EFFECTIVE).build()));
            return row;
        }

        private ResolveLoanEmploymentEventRequest resolution(LoanEmploymentEventOutcome outcome) {
            return new ResolveLoanEmploymentEventRequest(outcome, "  Suspension lifted on appeal ");
        }

        @Test
        @DisplayName("releasing a hold closes it and leaves the application to carry on")
        void release() {
            Loan loan = application(42);
            open(11, 42, LoanEmploymentEventAction.HOLD, true);

            LoanEmploymentEventResponse resolved = service.resolve(11L, resolution(LoanEmploymentEventOutcome.RELEASED));

            assertThat(resolved.status()).isEqualTo(LoanEmploymentEventStatus.CLOSED);
            assertThat(resolved.outcome()).isEqualTo(LoanEmploymentEventOutcome.RELEASED);
            assertThat(resolved.comment()).isEqualTo("Suspension lifted on appeal");
            assertThat(resolved.resolvedBy()).isEqualTo("cmanager");
            assertThat(resolved.eventType()).isEqualTo(EmploymentEventType.SUSPENSION);
            assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.PENDING);
            verify(loanRepository, never()).save(any());
            verifyNoInteractions(creditDecisionLog, loanNotificationService);
        }

        @Test
        @DisplayName("an assigned hold at an EXCLUSIVE stage is its assignee's to resolve; anyone else is refused")
        void resolveChecksTheAssignment() {
            Loan loan = application(42);
            open(11, 42, LoanEmploymentEventAction.HOLD, true);
            doThrow(new ConflictException("Loan 000000042's Employment event review is assigned to rnyathi"))
                    .when(workAssignmentGuard).requireMayAct(SystemStage.EMPLOYMENT_EVENT_REVIEW, loan, "cmanager");

            assertThatThrownBy(() -> service.resolve(11L, resolution(LoanEmploymentEventOutcome.DECLINED)))
                    .isInstanceOf(ConflictException.class);
            assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.PENDING);
            verify(loanEventRepository, never()).save(any());
            verifyNoInteractions(creditDecisionLog);
        }

        @Test
        @DisplayName("nobody may release a hold on an application they originated or are a party to")
        void releaseSegregationOfDuties() {
            application(42).setCreatedBy("cmanager");
            open(11, 42, LoanEmploymentEventAction.HOLD, true);
            assertThatThrownBy(() -> service.resolve(11L, resolution(LoanEmploymentEventOutcome.RELEASED)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("was originated by cmanager, who cannot also release its hold");

            Loan party = application(43);
            party.setMobileNumber("263771234000");
            open(12, 43, LoanEmploymentEventAction.HOLD, true);
            authService.getLoggedInUser().setMobileNumber("0771234000");
            assertThatThrownBy(() -> service.resolve(12L, resolution(LoanEmploymentEventOutcome.RELEASED)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("is a party to loan 000000043");
        }

        @Test
        @DisplayName("declining a hold declines the application, telling the applicant only if the event's treatment did")
        void declineHold() {
            Loan told = application(42);
            Loan notTold = application(43);
            open(11, 42, LoanEmploymentEventAction.HOLD, true);
            open(12, 43, LoanEmploymentEventAction.HOLD, false);

            service.resolve(11L, resolution(LoanEmploymentEventOutcome.DECLINED));
            service.resolve(12L, resolution(LoanEmploymentEventOutcome.DECLINED));

            assertThat(told.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
            assertThat(told.getInternalApprovalComment()).isEqualTo("Suspension lifted on appeal");
            assertThat(notTold.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
            verify(creditDecisionLog, times(2)).record(any(), eq(CreditAction.REJECTED), eq("REJECT_EMPLOYMENT"),
                    eq("Suspension lifted on appeal"), eq("cmanager"), any());
            verify(loanNotificationService).notify(told, LoanNotice.DECLINED);
            verify(loanNotificationService, never()).notify(notTold, LoanNotice.DECLINED);
        }

        @Test
        @DisplayName("a hold on an application declined since cannot be released, and declining it only closes it")
        void sinceDeclined() {
            Loan loan = application(42);
            loan.setInternalApprovalStatus(InternalApprovalStatus.REJECTED);
            open(11, 42, LoanEmploymentEventAction.HOLD, true);

            assertThatThrownBy(() -> service.resolve(11L, resolution(LoanEmploymentEventOutcome.RELEASED)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Loan 000000042 has since been declined or failed; resolve its hold as DECLINED");
            LoanEmploymentEventResponse resolved = service.resolve(11L, resolution(LoanEmploymentEventOutcome.DECLINED));

            assertThat(resolved.outcome()).isEqualTo(LoanEmploymentEventOutcome.DECLINED);
            verifyNoInteractions(creditDecisionLog, loanNotificationService);
        }

        @Test
        @DisplayName("a review is closed as REVIEWED, with what will be done")
        void review() {
            paidLoan(30);
            open(13, 30, LoanEmploymentEventAction.REVIEW, true);

            LoanEmploymentEventResponse resolved = service.resolve(13L, resolution(LoanEmploymentEventOutcome.REVIEWED));

            assertThat(resolved.outcome()).isEqualTo(LoanEmploymentEventOutcome.REVIEWED);
            assertThat(resolved.stage()).isEqualTo(LoanStage.PAID);
            verifyNoInteractions(creditDecisionLog, loanNotificationService);
            ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
            verify(auditService).record(audit.capture());
            assertThat(audit.getValue().build().getEventType()).isEqualTo("LOAN_EMPLOYMENT_EVENT_RESOLVED");
            assertThat(audit.getValue().build().getDetail()).contains("action=REVIEW", "outcome=REVIEWED");
        }

        @Test
        @DisplayName("an outcome that does not fit, a resolved record or a missing one is refused, changing nothing")
        void refusals() {
            application(42);
            paidLoan(30);
            open(11, 42, LoanEmploymentEventAction.HOLD, true);
            open(13, 30, LoanEmploymentEventAction.REVIEW, true);
            LoanEmploymentEvent closed = LoanEmploymentEvent.builder().id(14L).eventId(5L).loanId(42L)
                    .action(LoanEmploymentEventAction.HOLD).status(LoanEmploymentEventStatus.CLOSED)
                    .outcome(LoanEmploymentEventOutcome.RELEASED).build();
            when(loanEventRepository.findByIdForUpdate(14L)).thenReturn(Optional.of(closed));

            assertThatThrownBy(() -> service.resolve(11L, resolution(LoanEmploymentEventOutcome.REVIEWED)))
                    .hasMessage("A held application is resolved as RELEASED or DECLINED");
            assertThatThrownBy(() -> service.resolve(13L, resolution(LoanEmploymentEventOutcome.RELEASED)))
                    .hasMessage("A loan under review is resolved as REVIEWED");
            assertThatThrownBy(() -> service.resolve(14L, resolution(LoanEmploymentEventOutcome.RELEASED)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Loan employment event 14 is already resolved (RELEASED)");
            assertThatThrownBy(() -> service.resolve(99L, resolution(LoanEmploymentEventOutcome.RELEASED)))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.resolve(11L,
                    new ResolveLoanEmploymentEventRequest(LoanEmploymentEventOutcome.RELEASED, "  ")))
                    .hasMessage("Comment is required");
            verify(loanEventRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("a loan's employment events are read in the caller's scope: outside it, the loan does not exist")
    @SuppressWarnings("unchecked")
    void forLoanIsScoped() {
        when(loanRepository.exists(any(Specification.class))).thenReturn(false);

        assertThatThrownBy(() -> service.forLoan(42L, LoanReadScope.originator("harare-motors", 7L)))
                .isInstanceOf(NotFoundException.class).hasMessage("Loan 42 not found");
        verify(loanEventRepository, never()).findByLoanIdOrderByIdAsc(any());

        when(loanRepository.existsById(42L)).thenReturn(true);
        when(loanEventRepository.findByLoanIdOrderByIdAsc(42L)).thenReturn(List.of());
        assertThat(service.forLoan(42L, LoanReadScope.platform())).isEmpty();
    }
}

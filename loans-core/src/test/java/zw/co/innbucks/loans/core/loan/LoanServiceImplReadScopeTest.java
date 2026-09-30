package zw.co.innbucks.loans.core.loan;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.channel.ChannelRepository;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.document.LoanDocumentService;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.instrument.SignedInstrumentService;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.parameter.ParameterService;
import zw.co.innbucks.loans.core.turnaround.ServiceLevel;
import zw.co.innbucks.loans.core.turnaround.ServiceLevelService;
import zw.co.innbucks.loans.core.turnaround.ServiceLevelStage;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The scoped reads behind {@code GET /lending/v1/loans} and {@code GET /lending/v1/loans/{loanId}}.
 * The scope must be IN the query — so these evaluate the {@link Specification}
 * the service hands the repository against mocked criteria objects and assert
 * which columns it constrains, rather than trusting a filter applied afterwards.
 */
class LoanServiceImplReadScopeTest {

    private LoanRepository loanRepository;
    private LoanMapper loanMapper;
    private ServiceLevelService serviceLevelService;
    private LoanServiceImpl service;

    private Root<Loan> root;
    private CriteriaBuilder cb;
    private Expression<String> lowerMerchantCode;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        loanMapper = mock(LoanMapper.class);
        serviceLevelService = mock(ServiceLevelService.class);
        service = new LoanServiceImpl(loanRepository, mock(ParameterService.class), loanMapper,
                mock(AuthService.class), mock(MerchantRepository.class), mock(ChannelRepository.class),
                mock(Validator.class), new MarketTimeZone("ZW"),
                mock(LoanDocumentService.class), mock(PayslipFraudDetector.class), mock(PayslipReviewService.class),
                mock(SignedInstrumentService.class), mock(LoanNotificationService.class), serviceLevelService);

        root = mock(Root.class, RETURNS_DEEP_STUBS);
        cb = mock(CriteriaBuilder.class);
        lowerMerchantCode = mock(Expression.class);
        when(cb.lower(any())).thenReturn(lowerMerchantCode);
    }

    @Test
    @DisplayName("an agent's search is constrained to their merchant AND to loans they created")
    void originatorSearchConstrainsMerchantAndUser() {
        Loan loan = new Loan();
        LoanSummaryResponse own = new LoanSummaryResponse();
        when(loanRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(loan)));
        when(loanMapper.toSummary(loan)).thenReturn(own);

        var result = service.findLoans(LoanSearchCriteria.builder().build(), LoanReadScope.originator("M-001", 7L),
                PageRequest.of(0, 20));

        assertThat(result.getContent()).containsExactly(own);
        evaluate(capturedSearch());
        verify(root).join("merchant", JoinType.LEFT);
        verify(cb).equal(lowerMerchantCode, "m-001");
        verify(cb).equal(path("createdByUser", "id"), 7L);
    }

    @Test
    @DisplayName("a merchant filter narrows an agent's scope and never widens it: both merchants are required")
    void merchantFilterCannotWidenTheScope() {
        when(loanRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.findLoans(LoanSearchCriteria.builder().merchantCode("M-002").build(),
                LoanReadScope.originator("M-001", 7L), PageRequest.of(0, 20));

        evaluate(capturedSearch());
        verify(cb).equal(lowerMerchantCode, "m-002");
        verify(cb).equal(lowerMerchantCode, "m-001");
        verify(cb).equal(path("createdByUser", "id"), 7L);
    }

    @Test
    @DisplayName("a platform-wide search carries no merchant or originator constraint")
    void platformSearchIsUnconstrained() {
        when(loanRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.findLoans(LoanSearchCriteria.builder().build(), LoanReadScope.platform(), PageRequest.of(0, 20));

        evaluate(capturedSearch());
        verify(root, never()).join(eq("merchant"), any(JoinType.class));
        verify(root, never()).get("createdByUser");
    }

    @Test
    @DisplayName("a page is newest first with the id as tie-break, so paging neither repeats nor skips loans")
    void pagesAreNewestFirstWithATotalOrder() {
        when(loanRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.findLoans(LoanSearchCriteria.builder().build(), LoanReadScope.platform(), PageRequest.of(2, 50));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(loanRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdDate"), Sort.Order.desc("id")));
    }

    @Test
    @DisplayName("an originator's view of a loan says nothing of a payslip review; lender-side staff see it")
    void payslipReviewIsForStaffOnly() {
        Loan loan = new Loan();
        when(loanRepository.findOne(any(Specification.class))).thenReturn(Optional.of(loan));
        when(loanMapper.toResponse(loan)).thenAnswer(i -> {
            LoanResponse view = new LoanResponse();
            view.setPayslipReviewStatus(PayslipReviewStatus.PENDING);
            view.setPayslipReviewComment("Same payslip as loan 17");
            return view;
        });

        LoanResponse agentView = service.getLoan(42L, LoanReadScope.originator("M-001", 7L));
        LoanResponse staffView = service.getLoan(42L, LoanReadScope.platform());

        assertThat(agentView.getPayslipReviewStatus()).isNull();
        assertThat(agentView.getPayslipReviewComment()).isNull();
        assertThat(staffView.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.PENDING);
    }

    @Test
    @DisplayName("a scoped single read queries id + merchant + originator, never the bare findById")
    void scopedGetConstrainsIdMerchantAndUser() {
        Loan loan = new Loan();
        LoanResponse dto = new LoanResponse();
        when(loanRepository.findOne(any(Specification.class))).thenReturn(Optional.of(loan));
        when(loanMapper.toResponse(loan)).thenReturn(dto);

        assertThat(service.getLoan(42L, LoanReadScope.originator("M-001", 7L))).isSameAs(dto);

        evaluate(capturedSingleRead());
        Path<Object> id = root.get("id");
        verify(cb).equal(id, 42L);
        verify(cb).equal(lowerMerchantCode, "m-001");
        verify(cb).equal(path("createdByUser", "id"), 7L);
        verify(loanRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("another merchant's loan is simply not found by the scoped query → NotFoundException, like a missing id")
    void outOfScopeLoanIsNotFound() {
        when(loanRepository.findOne(any(Specification.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLoan(42L, LoanReadScope.originator("M-001", 7L)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 42 not found");
    }

    @Test
    @DisplayName("a missing id is a NotFoundException (the controller no longer maps every exception to 404)")
    void missingLoanIsNotFound() {
        when(loanRepository.findOne(any(Specification.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLoan(42L, LoanReadScope.platform()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 42 not found");
    }

    @Test
    @DisplayName("staff see how long each loan on a page has waited on Credit; the service level is read once a page")
    void staffListCarriesTheCreditTurnaround() {
        LocalDateTime approved = LocalDateTime.now(ZoneOffset.UTC).minusHours(30);
        Loan first = awaitingCredit(approved);
        Loan second = awaitingCredit(approved.plusHours(20));
        Loan decided = awaitingCredit(approved);
        decided.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
        when(loanRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second, decided)));
        when(loanMapper.toSummary(any(Loan.class))).thenAnswer(i -> new LoanSummaryResponse());
        when(serviceLevelService.serviceLevel(ServiceLevelStage.CREDIT_DECISION)).thenReturn(creditLevel());

        List<LoanSummaryResponse> page = service.findLoans(LoanSearchCriteria.awaitingCreditDecision(),
                LoanReadScope.platform(), PageRequest.of(0, 20)).getContent();

        assertThat(page.get(0).getCreditTurnaround().queueEnteredAt()).isEqualTo(approved);
        assertThat(page.get(0).getCreditTurnaround().dueAt()).isEqualTo(approved.plusHours(24));
        assertThat(page.get(0).getCreditTurnaround().overdue()).isTrue();
        assertThat(page.get(1).getCreditTurnaround().overdue()).isFalse();
        assertThat(page.get(2).getCreditTurnaround()).isNull();
        verify(serviceLevelService, times(1)).serviceLevel(ServiceLevelStage.CREDIT_DECISION);
    }

    @Test
    @DisplayName("an originator sees no turnaround: the service level is the lender's measure of its own staff")
    void originatorListCarriesNoTurnaround() {
        when(loanRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(awaitingCredit(LocalDateTime.now(ZoneOffset.UTC).minusHours(30)))));
        when(loanMapper.toSummary(any(Loan.class))).thenAnswer(i -> new LoanSummaryResponse());

        List<LoanSummaryResponse> page = service.findLoans(LoanSearchCriteria.builder().build(),
                LoanReadScope.originator("M-001", 7L), PageRequest.of(0, 20)).getContent();

        assertThat(page.getFirst().getCreditTurnaround()).isNull();
        verifyNoInteractions(serviceLevelService);
    }

    @Test
    @DisplayName("a loan's own view carries its turnaround for staff, measured from its resubmission when it had one")
    void singleReadCarriesTheTurnaroundForStaffOnly() {
        LocalDateTime resubmitted = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
        Loan loan = awaitingCredit(resubmitted.minusDays(3));
        loan.setCreditResubmittedAt(resubmitted);
        when(loanRepository.findOne(any(Specification.class))).thenReturn(Optional.of(loan));
        when(loanMapper.toResponse(loan)).thenAnswer(i -> new LoanResponse());
        when(serviceLevelService.serviceLevel(ServiceLevelStage.CREDIT_DECISION)).thenReturn(creditLevel());

        LoanResponse staffView = service.getLoan(42L, LoanReadScope.platform());
        LoanResponse agentView = service.getLoan(42L, LoanReadScope.originator("M-001", 7L));

        assertThat(staffView.getCreditTurnaround().queueEnteredAt()).isEqualTo(resubmitted);
        assertThat(staffView.getCreditTurnaround().overdue()).isFalse();
        assertThat(agentView.getCreditTurnaround()).isNull();
    }

    private static Loan awaitingCredit(LocalDateTime ssbApprovedAt) {
        Loan loan = new Loan();
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
        loan.setDateApproved(ssbApprovedAt);
        return loan;
    }

    private static ServiceLevel creditLevel() {
        return ServiceLevel.builder().stage(ServiceLevelStage.CREDIT_DECISION).targetHours(24).escalationHours(48)
                .updatedBy("system").updatedAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    @SuppressWarnings("unchecked")
    private Specification<Loan> capturedSearch() {
        ArgumentCaptor<Specification<Loan>> spec = ArgumentCaptor.forClass(Specification.class);
        verify(loanRepository).findAll(spec.capture(), any(Pageable.class));
        return spec.getValue();
    }

    @SuppressWarnings("unchecked")
    private Specification<Loan> capturedSingleRead() {
        ArgumentCaptor<Specification<Loan>> spec = ArgumentCaptor.forClass(Specification.class);
        verify(loanRepository).findOne(spec.capture());
        return spec.getValue();
    }

    private void evaluate(Specification<Loan> spec) {
        spec.toPredicate(root, mock(CriteriaQuery.class), cb);
    }

    /** The deep-stubbed path the spec navigated, e.g. {@code root.get("createdByUser").get("id")}. */
    private Path<Object> path(String association, String attribute) {
        return root.get(association).get(attribute);
    }
}

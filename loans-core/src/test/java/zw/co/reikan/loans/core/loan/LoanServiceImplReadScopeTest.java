package zw.co.reikan.loans.core.loan;

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
import org.springframework.data.jpa.domain.Specification;
import zw.co.reikan.loans.core.api.FindLoansRequest;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.channel.ChannelRepository;
import zw.co.reikan.loans.core.exception.NotFoundException;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.parameter.ParameterService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The scoped reads behind {@code /api/loans/search} and {@code /api/loans/{id}}.
 * The scope must be IN the query — so these evaluate the {@link Specification}
 * the service hands the repository against mocked criteria objects and assert
 * which columns it constrains, rather than trusting a filter applied afterwards.
 */
class LoanServiceImplReadScopeTest {

    private LoanRepository loanRepository;
    private LoanMapper loanMapper;
    private LoanServiceImpl service;

    private Root<Loan> root;
    private CriteriaBuilder cb;
    private Expression<String> lowerMerchantCode;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        loanMapper = mock(LoanMapper.class);
        service = new LoanServiceImpl(loanRepository, mock(ParameterService.class), loanMapper,
                mock(AuthService.class), mock(MerchantRepository.class), mock(ChannelRepository.class),
                mock(Validator.class));

        root = mock(Root.class, RETURNS_DEEP_STUBS);
        cb = mock(CriteriaBuilder.class);
        lowerMerchantCode = mock(Expression.class);
        when(cb.lower(any())).thenReturn(lowerMerchantCode);
    }

    @Test
    @DisplayName("an agent's search is constrained to their merchant AND to loans they created or agent")
    void originatorSearchConstrainsMerchantAndUser() {
        LoanDto own = new LoanDto();
        when(loanMapper.fromLoans(any())).thenReturn(List.of(own));

        List<LoanDto> result = service.findLoans(new FindLoansRequest(), LoanReadScope.originator("M-001", 7L));

        assertThat(result).containsExactly(own);
        evaluate(capturedSearch());
        verify(root).join("merchant", JoinType.LEFT);
        verify(cb).equal(lowerMerchantCode, "m-001");
        verify(cb).equal(path("createdByUser", "id"), 7L);
        verify(cb).equal(path("agent", "id"), 7L);
    }

    @Test
    @DisplayName("merchant management's search is constrained to the merchant, every originator")
    void merchantSearchConstrainsMerchantOnly() {
        service.findLoans(new FindLoansRequest(), LoanReadScope.merchant("M-001"));

        evaluate(capturedSearch());
        verify(cb).equal(lowerMerchantCode, "m-001");
        verify(root, never()).get("createdByUser");
        verify(root, never()).get("agent");
    }

    @Test
    @DisplayName("a platform-wide search carries no merchant or originator constraint")
    void platformSearchIsUnconstrained() {
        service.findLoans(new FindLoansRequest(), LoanReadScope.platform());

        evaluate(capturedSearch());
        verify(root, never()).join(eq("merchant"), any(JoinType.class));
        verify(root, never()).get("createdByUser");
        verify(root, never()).get("agent");
    }

    @Test
    @DisplayName("a scoped single read queries id + merchant + originator, never the bare findById")
    void scopedGetConstrainsIdMerchantAndUser() {
        Loan loan = new Loan();
        LoanDto dto = new LoanDto();
        when(loanRepository.findOne(any(Specification.class))).thenReturn(Optional.of(loan));
        when(loanMapper.fromLoan(loan)).thenReturn(dto);

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
        when(loanRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLoan(42L, LoanReadScope.platform()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 42 not found");
    }

    @SuppressWarnings("unchecked")
    private Specification<Loan> capturedSearch() {
        ArgumentCaptor<Specification<Loan>> spec = ArgumentCaptor.forClass(Specification.class);
        verify(loanRepository).findAll(spec.capture());
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

    /** The deep-stubbed path the spec navigated, e.g. {@code root.get("agent").get("id")}. */
    private Path<Object> path(String association, String attribute) {
        return root.get(association).get(attribute);
    }
}

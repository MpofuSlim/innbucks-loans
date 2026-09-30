package zw.co.innbucks.loans.core.loan;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.ObjectUtils;

import static org.springframework.data.jpa.domain.Specification.unrestricted;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.merchant.Merchant;

import java.time.LocalDateTime;

public class LoanSpecification {

    /**
     * This loan, if the scope may read it: a merchant-scoped caller reads only its own merchant's loans that it
     * originated. Platform-wide staff are not narrowed.
     */
    public static Specification<Loan> readableBy(Long id, LoanReadScope scope) {
        Specification<Loan> loan = withId(id);
        return scope.platformWide() ? loan
                : loan.and(withMerchantCode(scope.merchantCode())).and(createdByUserId(scope.userId()));
    }

    public static Specification<Loan> withId(Long id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    public static Specification<Loan> withInternalApprovalStatus(InternalApprovalStatus status) {
        if (status == null) {
            return unrestricted();
        } else {
            return (root, query, cb) -> cb.equal(root.get("internalApprovalStatus"), status);
        }
    }

    public static Specification<Loan> createdByUserId(Long userId) {
        return (root, query, cb) -> userId == null ? null : cb.equal(root.get("createdByUser").get("id"), userId);
    }

    public static Specification<Loan> withDisbursementStatus(LoanDisbursementStatus status) {
        if (status == null) {
            return unrestricted();
        } else {
            return (root, query, cb) -> cb.equal(root.get("disbursementStatus"), status);
        }
    }

    public static Specification<Loan> withApprovalStatus(LoanApprovalStatus status) {
        if (status == null) {
            return unrestricted();
        } else {
            return (root, query, cb) -> cb.equal(root.get("loanApprovalStatus"), status);
        }
    }

    public static Specification<Loan> withMerchantCode(String merchantCode) {
        return (root, query, cb) -> {
            if (merchantCode == null || merchantCode.trim().isEmpty()) {
                return cb.isTrue(cb.literal(true)); // Always true predicate
            }
            Join<Loan, Merchant> merchantJoin = root.join("merchant", JoinType.LEFT);
            return cb.equal(cb.lower(merchantJoin.get("merchantCode")), merchantCode.toLowerCase().trim());
        };
    }

    public static Specification<Loan> withCreatedDateBetween(LocalDateTime fromDate, LocalDateTime toDate) {
        if (ObjectUtils.isEmpty(fromDate) || ObjectUtils.isEmpty(toDate)) {
            return unrestricted();
        } else {
            return (root, query, cb) -> cb.between(root.get("createdDate"), fromDate, toDate);
        }
    }


}

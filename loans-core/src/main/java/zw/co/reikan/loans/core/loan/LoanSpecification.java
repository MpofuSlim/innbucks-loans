package zw.co.reikan.loans.core.loan;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.ObjectUtils;

import static org.springframework.data.jpa.domain.Specification.unrestricted;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.merchant.Merchant;

import java.time.LocalDateTime;

public class LoanSpecification {

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

    public static Specification<Loan> createdByUserOrAsAgent(Long userId) {
        return (root, query, cb) -> {
            if (userId == null) {
                return null;
            }
            return cb.or(
                    cb.equal(root.get("createdByUser").get("id"), userId),
                    cb.equal(root.get("agent").get("id"), userId)
            );
        };
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

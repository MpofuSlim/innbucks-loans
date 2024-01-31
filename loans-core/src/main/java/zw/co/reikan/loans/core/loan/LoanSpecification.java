package zw.co.reikan.loans.core.loan;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.ObjectUtils;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import java.time.LocalDateTime;

public class LoanSpecification {

    public static Specification<Loan> withDisbursementStatus(LoanDisbursementStatus status) {
        if (status == null) {
            return null;
        } else {
            return (root, query, cb) -> cb.equal(root.get("disbursementStatus"), status);
        }
    }

    public static Specification<Loan> withApprovalStatus(LoanApprovalStatus status) {
        if (status == null) {
            return null;
        } else {
            return (root, query, cb) -> cb.equal(root.get("loanApprovalStatus"), status);
        }
    }

    public static Specification<Loan> withCreatedDateBetween(LocalDateTime fromDate, LocalDateTime toDate) {
        if (ObjectUtils.isEmpty(fromDate) || ObjectUtils.isEmpty(toDate)) {
            return null;
        } else {
            return (root, query, cb) -> cb.between(root.get("createdDate"), fromDate, toDate);
        }
    }


}

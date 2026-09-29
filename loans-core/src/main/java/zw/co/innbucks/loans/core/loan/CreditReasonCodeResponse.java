package zw.co.innbucks.loans.core.loan;

/** A reason code a credit officer can choose, for the decision it belongs to. */
public record CreditReasonCodeResponse(String code, InternalApprovalStatus decision, String description) {

    public static CreditReasonCodeResponse of(CreditReasonCode reasonCode) {
        return new CreditReasonCodeResponse(reasonCode.getCode(), reasonCode.getDecision(), reasonCode.getDescription());
    }
}

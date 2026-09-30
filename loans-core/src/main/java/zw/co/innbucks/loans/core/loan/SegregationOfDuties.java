package zw.co.innbucks.loans.core.loan;

import org.apache.commons.lang3.StringUtils;
import zw.co.innbucks.loans.core.user.User;

import java.util.Locale;

/**
 * Who may not sign off a loan (FR-PBL-029): whoever originated it, and anyone who is a party to it. One
 * person must not both put a loan forward and wave it through. Nor, at a check made after Credit has
 * approved the loan, whoever approved it (FR-SSB-018): the check would otherwise be the approval again.
 */
public final class SegregationOfDuties {

    private SegregationOfDuties() {
    }

    /** By the loan's createdBy text or its originating user account, ignoring case. */
    public static boolean originated(Loan loan, String username) {
        return StringUtils.equalsIgnoreCase(username, loan.getCreatedBy())
                || (loan.getCreatedByUser() != null
                && StringUtils.equalsIgnoreCase(username, loan.getCreatedByUser().getUsername()));
    }

    /** Whoever approved it at Credit, ignoring case: a separate check after the approval is not theirs to make. */
    public static boolean approved(Loan loan, String username) {
        return loan.getInternalApprovalStatus() == InternalApprovalStatus.APPROVED && StringUtils.isNotBlank(username)
                && StringUtils.equalsIgnoreCase(username, loan.getInternalApprovalBy());
    }

    /**
     * The applicant, their next of kin, or the holder of the wallet the loan pays: matched on the user's
     * ID number or mobile number, however either was typed.
     */
    public static boolean isPartyTo(Loan loan, User user) {
        if (user == null) {
            return false;
        }
        String idNumber = identityNumber(user.getIdNumber());
        String mobile = nationalMobileNumber(user.getMobileNumber());
        NextOfKin nextOfKin = loan.getNextOfKin();
        boolean sameId = idNumber != null
                && (idNumber.equals(identityNumber(loan.getNationalIdNumber()))
                || (nextOfKin != null && idNumber.equals(identityNumber(nextOfKin.getNationalId()))));
        boolean sameMobile = mobile != null
                && (mobile.equals(nationalMobileNumber(loan.getMobileNumber()))
                || mobile.equals(nationalMobileNumber(loan.payoutWalletNumber()))
                || (nextOfKin != null && mobile.equals(nationalMobileNumber(nextOfKin.getMobileNumber()))));
        return sameId || sameMobile;
    }

    /** Letters and digits only, upper case: 63-1234567-A-42 and 631234567a42 are one ID. */
    private static String identityNumber(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /** The last nine digits, which every spelling of a Zimbabwean mobile shares. */
    private static String nationalMobileNumber(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.length() < 9 ? null : digits.substring(digits.length() - 9);
    }
}

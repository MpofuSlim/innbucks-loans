package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.staff.StaffMember;

import java.util.regex.Pattern;

/**
 * Where a cash Staff Grocery Loan is paid (loans.staff-loans.payout.destination), kept on the loan as it was at
 * acceptance with the account it names.
 */
public enum StaffLoanPayoutDestination {
    /** The borrower's InnBucks (Veengu) wallet: their registered phone, 2637XXXXXXXX. */
    VEENGU_WALLET("InnBucks wallet"),
    /** Their InnBucks bank (BR.NET) account: the register's account number. */
    BR_ACCOUNT("InnBucks account");

    /** A wallet: a Zimbabwean mobile number as the register stores it. */
    private static final Pattern WALLET = Pattern.compile("2637[13789]\\d{7}");
    /** An account number: digits only. The register keeps a wallet in the same field, so a wallet is never one. */
    private static final Pattern ACCOUNT = Pattern.compile("\\d{6,20}");

    private final String label;

    StaffLoanPayoutDestination(String label) {
        this.label = label;
    }

    /** How the borrower is told where the money goes: "your InnBucks wallet ending 3123". */
    public String label() {
        return label;
    }

    /**
     * The account this destination pays for {@code member}, as the register holds it now; null when it holds none.
     * VEENGU_WALLET: their registered phone, the number they prove with their PIN. BR_ACCOUNT: the register's
     * {@code wallet_account_number}, only when it is an account number (6 to 20 digits that are not a mobile number):
     * the same field holds a wallet for a member with no bank account, and that is never taken for one.
     */
    public String accountOf(StaffMember member) {
        if (member == null) {
            return null;
        }
        return switch (this) {
            case VEENGU_WALLET -> member.getMsisdn() != null && WALLET.matcher(member.getMsisdn()).matches()
                    ? member.getMsisdn() : null;
            case BR_ACCOUNT -> {
                String value = member.getWalletAccountNumber();
                yield value != null && ACCOUNT.matcher(value).matches() && !WALLET.matcher(value).matches()
                        ? value : null;
            }
        };
    }
}

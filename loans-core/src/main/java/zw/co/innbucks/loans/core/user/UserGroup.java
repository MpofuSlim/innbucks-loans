package zw.co.innbucks.loans.core.user;

public enum UserGroup {
    AGENTS,
    SUPER_ADMIN,
    CREDIT_MANAGER,
    FINANCE,
    /** Human Capital: owns the Staff Register for the Staff Grocery Loan (FR-SGL-001 to FR-SGL-008). */
    HUMAN_CAPITAL,
    /** GetMore's till integration: validates and redeems grocery vouchers, and nothing else (FR-SGL-036). */
    GETMORE,
    /** May read a voucher code in full, each time on the record (FR-SGL-040); everyone else sees it masked. */
    VOUCHER_SUPPORT
}
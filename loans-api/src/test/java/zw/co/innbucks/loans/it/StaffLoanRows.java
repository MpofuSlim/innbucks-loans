package zw.co.innbucks.loans.it;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Staff Grocery Loan rows written straight into a migrated database, for the ITs that need a loan to hang a voucher on:
 * a register batch and member, an approved grade limit, an on-demand offer taken up, and the loan. Each value is the
 * least the schema's constraints accept; nothing here goes through the services.
 */
final class StaffLoanRows {

    private StaffLoanRows() {
    }

    /** A register member, active; its id. */
    static long member(JdbcTemplate jdbc, String employeeNumber, String msisdn) {
        long batch = jdbc.queryForObject("""
                INSERT INTO staff_register_batches (source, status, submitted_by, submitted_at, total_rows,
                                                    staged_rows, rejected_rows)
                VALUES ('MANUAL', 'PENDING', 'it', now(), 1, 1, 0) RETURNING id
                """, Long.class);
        return jdbc.queryForObject("""
                INSERT INTO staff_members (employee_number, full_name, national_id, msisdn, grade, department,
                                           employment_status, engagement_date, wallet_account_number,
                                           status_changed_at, created_batch_id, created_at, updated_batch_id,
                                           updated_at)
                VALUES (?, 'Rutendo Chari', '63-1234567A63', ?, 'C4', 'Finance', 'ACTIVE', '2020-01-06',
                        '0771640064', now(), ?, now(), ?, now()) RETURNING id
                """, Long.class, employeeNumber, msisdn, batch, batch);
    }

    /**
     * A loan of {@code member} at GetMore Groceries, USD 150.00 due on 20 November 2026.
     *
     * @param disbursementReference null with {@code disbursedAt} for a loan not paid out
     */
    static long loan(JdbcTemplate jdbc, long member, String reference, String status, LocalDateTime disbursedAt,
                     String disbursementReference) {
        long limit = jdbc.queryForList("SELECT id FROM staff_grade_limit_changes WHERE grade = 'C4' AND status ="
                + " 'APPROVED'", Long.class).stream().findFirst().orElseGet(() -> jdbc.queryForObject("""
                INSERT INTO staff_grade_limit_changes (grade, score_band, maximum_limit, effective_from, status,
                                                       proposed_by, proposed_at, decided_by, decided_at)
                VALUES ('C4', 'ANY', 300.00, '2026-01-01', 'APPROVED', 'maker', now(), 'checker', now())
                RETURNING id
                """, Long.class));
        long offer = jdbc.queryForObject("""
                INSERT INTO staff_offers (staff_member_id, cycle_start, grade, score_band, grade_limit_change_id,
                                          amount, issued_at, expires_at, status, closed_at, origin)
                VALUES (?, '2026-10-12', 'C4', 'ANY', ?, 150.00, now(), now() + interval '7 days', 'TAKEN_UP', now(),
                        'APPLY') RETURNING id
                """, Long.class, member, limit);
        long merchant = jdbc.queryForObject("SELECT id FROM merchants WHERE merchant_code = 'getmore-groceries'",
                Long.class);
        return jdbc.queryForObject("""
                INSERT INTO staff_loans (reference, staff_member_id, offer_id, employee_number, full_name, msisdn,
                                         grade, amount, currency, interest_rate, total_repayable, due_date,
                                         unredeemed_voucher_treatment, status, accepted_at, disbursed_at,
                                         disbursement_reference, merchant_id)
                VALUES (?, ?, ?, 'E1064', 'Rutendo Chari', '263771640064', 'C4', 150.00, 'USD', 0, 150.00, ?,
                        'DEBT_STANDS', ?, now(), ?, ?, ?) RETURNING id
                """, Long.class, reference, member, offer, LocalDate.of(2026, 11, 20), status, disbursedAt,
                disbursementReference, merchant);
    }
}

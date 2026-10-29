package zw.co.innbucks.loans.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V36 lets a Staff Grocery Loan be paid in cash by the bank. Its CHECKs are the last guard on the rules the code keeps:
 * a voucher loan carries no payout; a cash loan always names where it is paid; it is cancelled only WITHDRAWN or
 * FAILED (so nothing, an image from before V36 included, cancels a payout the bank may have booked); PAID exactly when
 * it is paid out; claimed exactly while SENDING; one "paid" and one "not paid" notice per loan. Pinned on a real
 * Postgres, because a CHECK whose expression is NULL passes, and only Postgres says which are.
 */
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
class StaffLoanPayoutMigrationIT {

    private static final String WALLET = "263772123123";
    private static final String ACCOUNT = "1234567890";

    private final AtomicLong sequence = new AtomicLong();
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new SingleConnectionDataSource(ItPostgres.createDatabase("loans_v36_it"), ItPostgres.user(),
                ItPostgres.password(), true);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void close() {
        dataSource.destroy();
    }

    private void migrateTo(String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load().migrate();
    }

    @Test
    @DisplayName("a loan from before V36 becomes a voucher loan with no payout; so does one an older image inserts")
    void existingLoansAreVoucherLoans() {
        migrateTo("35");
        long before = insertLoan("AWAITING_DISBURSEMENT", Map.of());

        migrateTo("36");
        long after = insertLoan("AWAITING_DISBURSEMENT", Map.of());

        for (long id : List.of(before, after)) {
            Map<String, Object> row = jdbc.queryForMap("SELECT payout_method, payout_destination, payout_account,"
                    + " payout_status FROM staff_loans WHERE id = ?", id);
            assertThat(row.get("payout_method")).isEqualTo("VOUCHER");
            assertThat(row.get("payout_destination")).isNull();
            assertThat(row.get("payout_account")).isNull();
            assertThat(row.get("payout_status")).isNull();
        }
    }

    @Test
    @DisplayName("a cash loan names its destination, account and payout status; a voucher loan none of the payout")
    void cashNamesItsPayoutAndVoucherHasNone() {
        migrateTo("36");
        insertLoan("AWAITING_DISBURSEMENT", cash("VEENGU_WALLET", WALLET, "READY"));
        insertLoan("AWAITING_DISBURSEMENT", cash("BR_ACCOUNT", ACCOUNT, "READY"));

        refused("ck_staff_loans_payout", "AWAITING_DISBURSEMENT",
                Map.of("payout_method", "CASH", "payout_account", WALLET, "payout_status", "READY"));
        refused("ck_staff_loans_payout", "AWAITING_DISBURSEMENT",
                Map.of("payout_method", "CASH", "payout_destination", "VEENGU_WALLET", "payout_account", WALLET));
        refused("ck_staff_loans_payout", "AWAITING_DISBURSEMENT", Map.of("payout_message", "a note"));
        refused("ck_staff_loans_payout", "AWAITING_DISBURSEMENT", Map.of("bank_loan_account", "LA-1"));
        refused("ck_staff_loans_payout", "AWAITING_DISBURSEMENT", Map.of("payout_apply_answer", "{}"));
        refused("ck_staff_loans_payout", "AWAITING_DISBURSEMENT",
                Map.of("payout_checked_at", Timestamp.valueOf("2027-02-02 09:44:01")));
        refused("ck_staff_loans_payout", "AWAITING_DISBURSEMENT",
                Map.of("payout_declined_at", Timestamp.valueOf("2027-02-02 09:44:01")));
        refused("ck_staff_loans_payout", "AWAITING_DISBURSEMENT", Map.of("payout_status", "READY"));
        // Neither method: refused (CHECKs are tried in name order, so this one answers before _method).
        refused("ck_staff_loans_payout", "AWAITING_DISBURSEMENT", Map.of("payout_method", "CHEQUE"));
    }

    @Test
    @DisplayName("the account: digits; a wallet for VEENGU_WALLET, and never a wallet for BR_ACCOUNT")
    void theAccountFitsItsDestination() {
        migrateTo("36");

        refused("ck_staff_loans_payout_account", "AWAITING_DISBURSEMENT", cash("VEENGU_WALLET", ACCOUNT, "READY"));
        refused("ck_staff_loans_payout_account", "AWAITING_DISBURSEMENT", cash("BR_ACCOUNT", WALLET, "READY"));
        refused("ck_staff_loans_payout_account", "AWAITING_DISBURSEMENT", cash("BR_ACCOUNT", "12AB5678", "READY"));
        refused("ck_staff_loans_payout_account", "AWAITING_DISBURSEMENT", cash("BR_ACCOUNT", "12345", "READY"));
        refused("ck_staff_loans_payout_destination", "AWAITING_DISBURSEMENT", cash("BANK", ACCOUNT, "READY"));
    }

    @Test
    @DisplayName("cancelled only WITHDRAWN or FAILED, and both always cancelled")
    void cancelledOnlyWithdrawnOrFailed() {
        migrateTo("36");
        insertLoan("CANCELLED", cancelled(cash("VEENGU_WALLET", WALLET, "WITHDRAWN")));
        insertLoan("CANCELLED", cancelled(failed(cash("VEENGU_WALLET", WALLET, "FAILED"))));

        for (String payout : List.of("READY", "BOOKED", "IN_DOUBT", "REFUSED")) {
            refused("ck_staff_loans_payout_cancelled", "CANCELLED", cancelled(cash("VEENGU_WALLET", WALLET, payout)));
        }
        refused("ck_staff_loans_payout_cancelled", "AWAITING_DISBURSEMENT", cash("VEENGU_WALLET", WALLET, "WITHDRAWN"));
        refused("ck_staff_loans_payout_cancelled", "AWAITING_DISBURSEMENT",
                failed(cash("VEENGU_WALLET", WALLET, "FAILED")));
    }

    @Test
    @DisplayName("a payout the bank may hold cannot be cancelled by any code path: an old image's cancel is refused")
    void anInFlightPayoutIsNeverCancelled() {
        migrateTo("36");
        for (String payout : List.of("BOOKED", "IN_DOUBT")) {
            long id = insertLoan("AWAITING_DISBURSEMENT", cash("VEENGU_WALLET", WALLET, payout));

            assertThatThrownBy(() -> jdbc.update("UPDATE staff_loans SET status = 'CANCELLED', cancelled_at = now(),"
                    + " cancelled_by = 'credit', cancellation_reason = 'in error' WHERE id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_staff_loans_payout_cancelled");
        }
        // A voucher loan cancels exactly as before.
        long voucher = insertLoan("AWAITING_DISBURSEMENT", Map.of());
        jdbc.update("UPDATE staff_loans SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'credit',"
                + " cancellation_reason = 'in error' WHERE id = ?", voucher);
    }

    @Test
    @DisplayName("PAID exactly when the cash loan is paid out; a voucher loan is paid out with no payout status")
    void paidExactlyWhenPaidOut() {
        migrateTo("36");
        insertLoan("DISBURSED", disbursed(cash("VEENGU_WALLET", WALLET, "PAID")));
        insertLoan("DISBURSED", disbursed(Map.of()));

        refused("ck_staff_loans_payout_paid", "AWAITING_DISBURSEMENT", cash("VEENGU_WALLET", WALLET, "PAID"));
        refused("ck_staff_loans_payout_paid", "DISBURSED", disbursed(cash("VEENGU_WALLET", WALLET, "BOOKED")));
    }

    @Test
    @DisplayName("claimed exactly while SENDING")
    void claimedExactlyWhileSending() {
        migrateTo("36");
        insertLoan("AWAITING_DISBURSEMENT", claimed(cash("VEENGU_WALLET", WALLET, "SENDING")));

        refused("ck_staff_loans_payout_claimed", "AWAITING_DISBURSEMENT", cash("VEENGU_WALLET", WALLET, "SENDING"));
        refused("ck_staff_loans_payout_claimed", "AWAITING_DISBURSEMENT",
                claimed(cash("VEENGU_WALLET", WALLET, "IN_DOUBT")));
    }

    @Test
    @DisplayName("failed exactly when stamped so; paid-after-cancel only on a failed payout")
    void failedExactlyWhenStamped() {
        migrateTo("36");
        Map<String, Object> watched = with(cancelled(failed(cash("VEENGU_WALLET", WALLET, "FAILED"))),
                "payout_paid_after_cancel_at", Timestamp.valueOf("2026-10-30 08:00:00"));
        insertLoan("CANCELLED", watched);

        refused("ck_staff_loans_payout_failed", "CANCELLED", cancelled(cash("VEENGU_WALLET", WALLET, "FAILED")));
        refused("ck_staff_loans_payout_failed", "CANCELLED",
                with(cancelled(cash("VEENGU_WALLET", WALLET, "WITHDRAWN")), "payout_paid_after_cancel_at",
                        Timestamp.valueOf("2026-10-30 08:00:00")));
    }

    @Test
    @DisplayName("a loan notice names its loan, and each kind is sent once per loan")
    void oneNoticeOfEachKindPerLoan() {
        migrateTo("36");
        long loan = insertLoan("AWAITING_DISBURSEMENT", cash("VEENGU_WALLET", WALLET, "READY"));
        long member = jdbc.queryForObject("SELECT staff_member_id FROM staff_loans WHERE id = ?", Long.class, loan);

        notice(member, "LOAN_PAID_OUT", loan);
        notice(member, "LOAN_NOT_PAID_OUT", loan);

        assertThatThrownBy(() -> notice(member, "LOAN_PAID_OUT", loan))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_staff_notifications_loan");
        assertThatThrownBy(() -> notice(member, "LOAN_PAID_OUT", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_staff_notifications_subject");
        assertThatThrownBy(() -> notice(member, "LAUNCH", loan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_staff_notifications_subject");
        assertThatThrownBy(() -> notice(member, "LOAN_REPAID", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_staff_notifications_template");
    }

    @Test
    @DisplayName("the payout queues are read through their partial indexes, with the literals the queries repeat")
    void theQueuesUseTheirIndexes() {
        migrateTo("36");
        insertLoan("AWAITING_DISBURSEMENT", cash("VEENGU_WALLET", WALLET, "READY"));
        jdbc.execute("SET enable_seqscan = off");

        String ready = String.join("\n", jdbc.queryForList("EXPLAIN SELECT id FROM staff_loans WHERE payout_method"
                + " = 'CASH' AND status = 'AWAITING_DISBURSEMENT' AND payout_status = 'READY' AND id > 0 ORDER BY id"
                + " LIMIT 100", String.class));
        String failed = String.join("\n", jdbc.queryForList("EXPLAIN SELECT id FROM staff_loans WHERE payout_status"
                + " = 'FAILED' AND payout_paid_after_cancel_at IS NULL AND payout_failed_at >= now() - interval"
                + " '14 days' AND id > 0 ORDER BY id LIMIT 100", String.class));

        assertThat(ready).contains("idx_staff_loans_payout_pending");
        assertThat(failed).contains("idx_staff_loans_payout_failed");
    }

    @Test
    @DisplayName("each CHECK on its own is FALSE, never NULL, for the rows its IS NOT NULL guards are there for")
    void noCheckPassesOnANull() {
        migrateTo("36");
        // A CHECK whose expression is NULL passes, so a row the other CHECKs happen to refuse today could slip through
        // one of these the day another changes. Each is evaluated alone, over a copy of the table with no constraints.
        jdbc.execute("CREATE TEMP TABLE probe AS SELECT * FROM staff_loans WHERE false");
        Map<String, List<Map<String, Object>>> holes = new LinkedHashMap<>();
        holes.put("ck_staff_loans_payout_account", List.of(
                Map.of("payout_method", "CASH", "payout_account", WALLET)));
        holes.put("ck_staff_loans_payout", List.of(
                Map.of("payout_method", "CASH", "payout_account", WALLET, "payout_status", "READY"),
                Map.of("payout_method", "CASH", "payout_destination", "VEENGU_WALLET", "payout_account", WALLET)));
        holes.put("ck_staff_loans_payout_cancelled", List.of(
                Map.of("status", "CANCELLED", "payout_method", "CASH"),
                Map.of("status", "AWAITING_DISBURSEMENT", "payout_method", "CASH", "payout_status", "WITHDRAWN")));
        holes.put("ck_staff_loans_payout_paid", List.of(
                Map.of("status", "DISBURSED", "payout_method", "CASH"),
                Map.of("status", "AWAITING_DISBURSEMENT", "payout_method", "VOUCHER", "payout_status", "PAID")));
        holes.put("ck_staff_loans_payout_claimed", List.of(
                Map.of("payout_method", "VOUCHER", "payout_claimed_at", Timestamp.valueOf("2026-10-29 08:30:00"))));
        holes.put("ck_staff_loans_payout_failed", List.of(
                Map.of("payout_method", "VOUCHER", "payout_failed_at", Timestamp.valueOf("2026-10-29 09:00:00")),
                Map.of("payout_method", "CASH", "payout_status", "WITHDRAWN",
                        "payout_paid_after_cancel_at", Timestamp.valueOf("2026-10-30 08:00:00"))));

        for (Map.Entry<String, List<Map<String, Object>>> check : holes.entrySet()) {
            for (Map<String, Object> row : check.getValue()) {
                assertThat(evaluate(check.getKey(), row)).as("%s over %s", check.getKey(), row).isFalse();
            }
        }
        // And each is TRUE for a cash loan READY to pay, so the probe reads the CHECKs as Postgres does.
        Map<String, Object> ready = new LinkedHashMap<>(cash("VEENGU_WALLET", WALLET, "READY"));
        ready.put("status", "AWAITING_DISBURSEMENT");
        for (String check : holes.keySet()) {
            assertThat(evaluate(check, ready)).as(check).isTrue();
        }
    }

    /** {@code check}'s expression over one row of {@code columns}, every other column NULL: TRUE, FALSE or NULL. */
    private Boolean evaluate(String check, Map<String, Object> columns) {
        String definition = jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?"
                + " AND conrelid = 'staff_loans'::regclass", String.class, check);
        assertThat(definition).startsWith("CHECK ");
        jdbc.execute("TRUNCATE probe");
        jdbc.update("INSERT INTO probe (" + String.join(", ", columns.keySet()) + ") VALUES ("
                + String.join(", ", columns.keySet().stream().map(column -> "?").toList()) + ")",
                columns.values().toArray());
        return jdbc.queryForObject("SELECT " + definition.substring("CHECK ".length()) + " FROM probe",
                Boolean.class);
    }

    // --- fixtures --------------------------------------------------------------------------------------------------

    private void refused(String constraint, String status, Map<String, Object> columns) {
        assertThatThrownBy(() -> insertLoan(status, columns)).as("%s %s", status, columns)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint + "\"");
    }

    private static Map<String, Object> cash(String destination, String account, String payout) {
        return Map.of("payout_method", "CASH", "payout_destination", destination, "payout_account", account,
                "payout_status", payout);
    }

    private static Map<String, Object> cancelled(Map<String, Object> columns) {
        return with(with(with(columns, "cancelled_at", Timestamp.valueOf("2026-10-29 09:00:00")),
                "cancelled_by", "credit.officer"), "cancellation_reason", "Cancelled");
    }

    private static Map<String, Object> failed(Map<String, Object> columns) {
        return with(columns, "payout_failed_at", Timestamp.valueOf("2026-10-29 09:00:00"));
    }

    private static Map<String, Object> claimed(Map<String, Object> columns) {
        return with(columns, "payout_claimed_at", Timestamp.valueOf("2026-10-29 08:30:00"));
    }

    private static Map<String, Object> disbursed(Map<String, Object> columns) {
        return with(with(columns, "disbursed_at", Timestamp.valueOf("2026-10-29 09:00:00")),
                "disbursement_reference", "SGL-2026-000143");
    }

    private static Map<String, Object> with(Map<String, Object> columns, String column, Object value) {
        LinkedHashMap<String, Object> more = new LinkedHashMap<>(columns);
        more.put(column, value);
        return more;
    }

    /** A staff loan in {@code status}, of a member and offer of its own, with {@code columns} on top. */
    private long insertLoan(String status, Map<String, Object> columns) {
        long n = sequence.incrementAndGet();
        long member = member(n);
        long offer = offer(n, member);
        long merchant = merchant();
        StringBuilder names = new StringBuilder("reference, staff_member_id, offer_id, employee_number, full_name,"
                + " msisdn, grade, merchant_id, amount, currency, interest_rate, total_repayable, due_date,"
                + " unredeemed_voucher_treatment, status, accepted_at");
        StringBuilder marks = new StringBuilder("?, ?, ?, ?, 'Chipo Banda', ?, 'G4', ?, 150.00, 'USD', 0, 150.00,"
                + " DATE '2026-11-20', 'DEBT_STANDS', ?, TIMESTAMP '2026-10-29 08:00:00'");
        List<Object> values = new ArrayList<>(List.of("SGL-2026-" + String.format("%06d", n), member,
                offer, "E" + n, WALLET, merchant, status));
        columns.forEach((column, value) -> {
            names.append(", ").append(column);
            marks.append(", ?");
            values.add(value);
        });
        return jdbc.queryForObject("INSERT INTO staff_loans (" + names + ") VALUES (" + marks + ") RETURNING id",
                Long.class, values.toArray());
    }

    /** A register member; its own parents (the register batch) are not this test's subject, so not checked. */
    private long member(long n) {
        return withoutForeignKeys(() -> jdbc.queryForObject("""
                INSERT INTO staff_members (employee_number, full_name, national_id, msisdn, grade, department,
                    employment_status, engagement_date, wallet_account_number, status_changed_at, created_batch_id,
                    created_at, updated_batch_id, updated_at)
                VALUES (?, 'Chipo Banda', ?, ?, 'G4', 'Operations', 'ACTIVE', DATE '2015-02-02', ?, now(), 1, now(),
                    1, now())
                RETURNING id""", Long.class, "E" + n, "63" + (2345678 + n) + "B42",
                "2637721" + String.format("%05d", n), ACCOUNT));
    }

    private long offer(long n, long member) {
        return withoutForeignKeys(() -> jdbc.queryForObject("""
                INSERT INTO staff_offers (staff_member_id, cycle_start, grade, score_band, grade_limit_change_id,
                    amount, issued_at, expires_at, status, closed_at, origin)
                VALUES (?, DATE '2026-10-26', 'G4', 'A', 1, 150.00, now(), now() + interval '7 days', 'TAKEN_UP',
                    now(), 'APPLY')
                RETURNING id""", Long.class, member));
    }

    private long merchant() {
        Long existing = jdbc.query("SELECT id FROM merchants WHERE id = 990001", rs -> rs.next() ? rs.getLong(1)
                : null);
        if (existing != null) {
            return existing;
        }
        return withoutForeignKeys(() -> jdbc.queryForObject("INSERT INTO merchants (id, created_date, version)"
                + " VALUES (990001, now(), 0) RETURNING id", Long.class));
    }

    private void notice(long member, String template, Long loan) {
        jdbc.update("""
                INSERT INTO staff_notifications (staff_member_id, template, template_version, title, message,
                    created_at, outbound_status, staff_loan_id)
                VALUES (?, ?, 1, 'Your Staff Grocery Loan', 'Message', now(), 'PENDING', ?)""",
                member, template, loan);
    }

    /** Runs a fixture insert with foreign keys unchecked (one connection, so the setting holds), then restores them. */
    private <T> T withoutForeignKeys(Supplier<T> insert) {
        jdbc.execute("SET session_replication_role = replica");
        try {
            return insert.get();
        } finally {
            jdbc.execute("SET session_replication_role = DEFAULT");
        }
    }
}

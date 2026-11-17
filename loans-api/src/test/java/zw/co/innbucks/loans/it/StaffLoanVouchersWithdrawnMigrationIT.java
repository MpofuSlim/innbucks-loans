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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V37 withdraws the voucher: every Staff Grocery Loan is paid in cash into the borrower's InnBucks wallet. A voucher
 * loan still awaiting disbursement can never be paid, so it is cancelled and audited; every other row is history and
 * stays as it is. A loan made from now on is a cash loan by the columns' defaults, with no merchant. The till accounts
 * existed only to redeem vouchers: their sessions end, an account that was nothing but a till can no longer sign in,
 * and the two voucher roles are gone. Pinned on a real Postgres, against V36's CHECKs.
 */
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
class StaffLoanVouchersWithdrawnMigrationIT {

    private static final String WALLET = "263772123123";
    private static final long MERCHANT = 990003;

    private final AtomicLong sequence = new AtomicLong();
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new SingleConnectionDataSource(ItPostgres.createDatabase("loans_v37_it"), ItPostgres.user(),
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
    @DisplayName("a voucher loan awaiting disbursement is cancelled and audited; every other loan is left as it was")
    void openVoucherLoansAreCancelled() {
        migrateTo("36");
        long open = legacyLoan("AWAITING_DISBURSEMENT", Map.of());
        long cancelled = legacyLoan("CANCELLED", Map.of("cancelled_at", Timestamp.valueOf("2026-10-20 08:00:00"),
                "cancelled_by", "credit.officer", "cancellation_reason", "Accepted in error"));
        long cash = legacyLoan("AWAITING_DISBURSEMENT", Map.of("payout_method", "CASH",
                "payout_destination", "VEENGU_WALLET", "payout_account", WALLET, "payout_status", "READY"));

        migrateTo("37");

        Map<String, Object> was = row(open);
        assertThat(was.get("status")).isEqualTo("CANCELLED");
        assertThat(was.get("cancelled_by")).isEqualTo("SYSTEM_USER");
        assertThat(was.get("cancelled_at")).isNotNull();
        assertThat((String) was.get("cancellation_reason")).startsWith("Vouchers were withdrawn")
                .endsWith("The borrower may apply again.");
        assertThat(was.get("payout_method")).as("history: it was a voucher loan").isEqualTo("VOUCHER");
        assertThat(was.get("payout_status")).isNull();
        assertThat(jdbc.queryForList("SELECT event_type, actor_id, channel_used, detail FROM audit_logs"
                + " WHERE entity_type = 'STAFF_LOAN' AND entity_id = ?", String.valueOf(open)))
                .singleElement().satisfies(audit -> {
                    assertThat(audit.get("event_type")).isEqualTo("STAFF_LOAN_CANCELLED");
                    assertThat(audit.get("actor_id")).isEqualTo("SYSTEM_USER");
                    assertThat(audit.get("channel_used")).isEqualTo("migration V37");
                    assertThat((String) audit.get("detail")).startsWith("reference:SGL-2026-")
                            .endsWith(";reason:vouchers withdrawn");
                });

        assertThat(row(cancelled).get("cancelled_by")).as("already cancelled: untouched").isEqualTo("credit.officer");
        assertThat(row(cash).get("status")).isEqualTo("AWAITING_DISBURSEMENT");
        assertThat(row(cash).get("payout_status")).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).as("one audit, the cancel")
                .isOne();
    }

    @Test
    @DisplayName("a loan made from now on is a cash loan into the wallet by default, with no merchant; one with no"
            + " payout is refused")
    void newLoansArePaidIntoTheWallet() {
        migrateTo("37");

        long loan = newLoan("AWAITING_DISBURSEMENT", Map.of("payout_account", WALLET, "payout_status", "READY"));

        Map<String, Object> made = row(loan);
        assertThat(made.get("payout_method")).isEqualTo("CASH");
        assertThat(made.get("payout_destination")).isEqualTo("VEENGU_WALLET");
        assertThat(made.get("merchant_id")).isNull();
        assertThat(made.get("unredeemed_voucher_treatment")).isNull();
        assertThatThrownBy(() -> newLoan("AWAITING_DISBURSEMENT", Map.of()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_staff_loans_payout\"");
        assertThatThrownBy(() -> newLoan("AWAITING_DISBURSEMENT", Map.of("payout_account", "1234567890",
                "payout_status", "READY"))).as("never an account number: the wallet is a phone")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_staff_loans_payout_account\"");
    }

    @Test
    @DisplayName("the till accounts are retired: sessions ended, a till-only account cannot sign in, the voucher roles"
            + " are gone and refused")
    void tillAccountsAreRetired() {
        migrateTo("36");
        long till = user("till.getmore", "secret-hash-1", null, "MERCHANT_TILL");
        long support = user("support.finance", "secret-hash-2", 4L, "VOUCHER_SUPPORT", "FINANCE");
        long tillAndCredit = user("till.credit", "secret-hash-3", 2L, "MERCHANT_TILL", "CREDIT_MANAGER");
        long finance = user("finance1", "secret-hash-4", 7L, "FINANCE");

        migrateTo("37");

        assertThat(user(till)).containsEntry("password", null).containsEntry("token_version", 1L);
        assertThat(groups(till)).isEmpty();
        assertThat(user(support)).containsEntry("password", "secret-hash-2").containsEntry("token_version", 5L);
        assertThat(groups(support)).containsExactly("FINANCE");
        assertThat(user(tillAndCredit)).as("still a Credit manager: keeps their password")
                .containsEntry("password", "secret-hash-3").containsEntry("token_version", 3L);
        assertThat(groups(tillAndCredit)).containsExactly("CREDIT_MANAGER");
        assertThat(user(finance)).as("never a voucher role: untouched").containsEntry("password", "secret-hash-4")
                .containsEntry("token_version", 7L);
        for (String role : List.of("MERCHANT_TILL", "VOUCHER_SUPPORT", "GETMORE")) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO user_groups (user_id, user_group) VALUES (?, ?)",
                    finance, role)).as(role).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_user_groups_user_group");
        }
        jdbc.update("INSERT INTO user_groups (user_id, user_group) VALUES (?, 'HUMAN_CAPITAL')", finance);
    }

    @Test
    @DisplayName("the payout queues' index names no method: every loan with a payout is a cash loan")
    void theQueueIndexNamesNoMethod() {
        migrateTo("37");

        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname ="
                + " 'idx_staff_loans_payout_pending'", String.class))
                .contains("payout_status IS NOT NULL", "'AWAITING_DISBURSEMENT'").doesNotContain("payout_method");
    }

    // --- fixtures --------------------------------------------------------------------------------------------------

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT status, cancelled_at, cancelled_by, cancellation_reason, payout_method,"
                + " payout_destination, payout_status, merchant_id, unredeemed_voucher_treatment FROM staff_loans"
                + " WHERE id = ?", id);
    }

    /** A loan as an image before V37 inserts one: with its merchant and unspent-voucher treatment. */
    private long legacyLoan(String status, Map<String, Object> columns) {
        merchant();
        return insertLoan(status, true, columns);
    }

    /** A loan as this image inserts one: neither named. */
    private long newLoan(String status, Map<String, Object> columns) {
        return insertLoan(status, false, columns);
    }

    private long insertLoan(String status, boolean legacy, Map<String, Object> columns) {
        long n = sequence.incrementAndGet();
        long member = withoutForeignKeys(() -> jdbc.queryForObject("""
                INSERT INTO staff_members (employee_number, full_name, national_id, msisdn, grade, department,
                    employment_status, engagement_date, wallet_account_number, status_changed_at, created_batch_id,
                    created_at, updated_batch_id, updated_at)
                VALUES (?, 'Chipo Banda', ?, ?, 'G4', 'Operations', 'ACTIVE', DATE '2015-02-02', '1234567890', now(), 1,
                    now(), 1, now())
                RETURNING id""", Long.class, "E" + n, "63" + (4345678 + n) + "B42",
                "2637741" + String.format("%05d", n)));
        long offer = withoutForeignKeys(() -> jdbc.queryForObject("""
                INSERT INTO staff_offers (staff_member_id, cycle_start, grade, score_band, grade_limit_change_id,
                    amount, issued_at, expires_at, status, closed_at, origin)
                VALUES (?, DATE '2026-10-26', 'G4', 'A', 1, 150.00, now(), now() + interval '7 days', 'TAKEN_UP',
                    now(), 'APPLY')
                RETURNING id""", Long.class, member));
        StringBuilder names = new StringBuilder("reference, staff_member_id, offer_id, employee_number, full_name,"
                + " msisdn, grade, amount, currency, interest_rate, total_repayable, due_date, status, accepted_at");
        StringBuilder marks = new StringBuilder("?, ?, ?, ?, 'Chipo Banda', ?, 'G4', 150.00, 'USD', 0, 150.00,"
                + " DATE '2026-11-20', ?, TIMESTAMP '2026-10-29 08:00:00'");
        List<Object> values = new ArrayList<>(List.of("SGL-2026-" + String.format("%06d", n), member, offer,
                "E" + n, WALLET, status));
        if (legacy) {
            names.append(", merchant_id, unredeemed_voucher_treatment");
            marks.append(", ?, 'DEBT_STANDS'");
            values.add(MERCHANT);
        }
        columns.forEach((column, value) -> {
            names.append(", ").append(column);
            marks.append(", ?");
            values.add(value);
        });
        return jdbc.queryForObject("INSERT INTO staff_loans (" + names + ") VALUES (" + marks + ") RETURNING id",
                Long.class, values.toArray());
    }

    private void merchant() {
        if (jdbc.queryForObject("SELECT count(*) FROM merchants WHERE id = ?", Long.class, MERCHANT) == 0) {
            withoutForeignKeys(() -> jdbc.update("INSERT INTO merchants (id, created_date, version) VALUES (?, now(),"
                    + " 0)", MERCHANT));
        }
    }

    private long user(String username, String password, Long tokenVersion, String... groups) {
        merchant();
        long id = withoutForeignKeys(() -> jdbc.queryForObject("""
                INSERT INTO users (external_system_id, username, password, temporary_password, token_version,
                    merchant_id, created_date, version)
                VALUES (?, ?, ?, FALSE, ?, ?, now(), 0)
                RETURNING id""", Long.class, "ext-" + username, username, password, tokenVersion, MERCHANT));
        for (String group : groups) {
            jdbc.update("INSERT INTO user_groups (user_id, user_group) VALUES (?, ?)", id, group);
        }
        return id;
    }

    private Map<String, Object> user(long id) {
        return jdbc.queryForMap("SELECT password, token_version FROM users WHERE id = ?", id);
    }

    private List<String> groups(long id) {
        return jdbc.queryForList("SELECT user_group FROM user_groups WHERE user_id = ? ORDER BY user_group",
                String.class, id);
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

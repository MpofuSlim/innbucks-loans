package zw.co.innbucks.loans.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V37 on real Postgres: what a GetMore voucher's rows may and may not hold, and that everything written before it (and
 * everything the build before it writes, after a rollback) still passes. Rows go in by SQL, exactly as the columns'
 * defaults and constraints see them.
 */
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
class GetMoreVoucherMigrationPostgresIT {

    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 10, 14, 8, 20, 7);
    private static final LocalDateTime EXPIRES = LocalDateTime.of(2026, 11, 13, 21, 59, 59);

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private long merchant;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(ItPostgres.createDatabase("loans_v37_it"), ItPostgres.user(),
                ItPostgres.password());
        jdbc = new JdbcTemplate(dataSource);
    }

    private void migrateTo(String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load().migrate();
        merchant = jdbc.queryForObject("SELECT id FROM merchants WHERE merchant_code = 'getmore-groceries'",
                Long.class);
    }

    /** A voucher of loans' own, as V22 to V36 wrote one: none of the GetMore columns named. */
    private long loansVoucher(String disbursement, String hmac) {
        return jdbc.queryForObject("""
                INSERT INTO vouchers (product, disbursement_reference, loan_account, customer_reference, customer_name,
                                      customer_msisdn, code_hmac, code_ciphertext, code_last4, code_length,
                                      face_value, redeemed_amount, currency, issued_at, expires_at, status,
                                      delivery_status, delivery_updated_at, merchant_id)
                VALUES ('STAFF_GROCERY_LOAN', ?, 'SGL-2026-000143', 'E1012', 'Chipo Banda', '+263772123123', ?,
                        'v1:sealed', '8406', 16, 300.00, 0, 'USD', ?, ?, 'ISSUED', 'PENDING', ?, ?) RETURNING id
                """, Long.class, disbursement, hmac, ISSUED, EXPIRES, ISSUED, merchant);
    }

    /** A GetMore voucher of {@code loan}; {@code changes} overrides any column. */
    private long getMoreVoucher(long loan, Map<String, Object> changes) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("product", "STAFF_GROCERY_LOAN");
        row.put("disbursement_reference", "GETMORE-SIM-1207");
        row.put("loan_account", "SGL-2026-000164");
        row.put("customer_reference", "E1064");
        row.put("customer_name", "Rutendo Chari");
        row.put("customer_msisdn", "+263771640064");
        row.put("code_hmac", "b".repeat(64));
        row.put("code_ciphertext", "v1:sealed");
        row.put("code_last4", "8B1C");
        row.put("code_length", 29);
        row.put("face_value", new BigDecimal("150.00"));
        row.put("redeemed_amount", BigDecimal.ZERO);
        row.put("currency", "USD");
        row.put("issued_at", ISSUED);
        row.put("expires_at", EXPIRES);
        row.put("status", "ISSUED");
        row.put("delivery_status", "PENDING");
        row.put("delivery_updated_at", ISSUED);
        row.put("merchant_id", merchant);
        row.put("code_source", "GETMORE");
        row.put("store_mode", "SIMULATED");
        row.put("staff_loan_id", loan);
        row.put("external_coupon_id", 1207L);
        row.put("external_rule_id", 43L);
        row.put("code_prefix", "SIM-K7Q2M3XW-");
        row.putAll(changes);
        String columns = String.join(", ", row.keySet());
        String marks = String.join(", ", java.util.Collections.nCopies(row.size(), "?"));
        return jdbc.queryForObject("INSERT INTO vouchers (" + columns + ") VALUES (" + marks + ") RETURNING id",
                Long.class, row.values().toArray());
    }

    private long disbursedLoan() {
        long member = StaffLoanRows.member(jdbc, "E1064", "263771640064");
        return StaffLoanRows.loan(jdbc, member, "SGL-2026-000164", "DISBURSED", ISSUED, "GETMORE-SIM-1207");
    }

    @Test
    @DisplayName("rows from before V37 still pass, read LOANS and TILL; a till insert naming no source is TILL")
    void earlierRowsStillPass() {
        migrateTo("36");
        jdbc.update("INSERT INTO merchants (merchant_code, name, disbursement_type, staff_loan_merchant, created_date,"
                + " version) VALUES ('pick-n-pay', 'Pick n Pay', 'MERCHANT_MOBILE_WALLET', FALSE, now(), 0)");
        long voucher = loansVoucher("BRNET-20261001-0007", "a".repeat(64));
        jdbc.update("""
                INSERT INTO voucher_redemptions (voucher_id, merchant_reference, amount, balance_after, outlet_id,
                                                 redeemed_by, redeemed_at, merchant_id)
                VALUES (?, 'GM-POS-88412', 180.00, 120.00, 'GM-AVD-01', 'till', ?, ?)
                """, voucher, ISSUED.plusDays(2), merchant);
        jdbc.update("UPDATE vouchers SET redeemed_amount = 180.00, status = 'PARTIALLY_REDEEMED',"
                + " last_redeemed_at = ?, last_redeemed_outlet = 'GM-AVD-01' WHERE id = ?", ISSUED.plusDays(2),
                voucher);

        migrateTo("37");

        assertThat(jdbc.queryForMap("SELECT code_source, refunded_amount, held_amount, store_mode FROM vouchers"
                + " WHERE id = ?", voucher)).containsEntry("code_source", "LOANS")
                .containsEntry("refunded_amount", new BigDecimal("0.00")).containsEntry("held_amount",
                        new BigDecimal("0.00")).containsEntry("store_mode", null);
        assertThat(jdbc.queryForObject("SELECT source FROM voucher_redemptions", String.class)).isEqualTo("TILL");
        // The build before V37, rolled back to, still redeems at a till: it names none of the new columns.
        jdbc.update("""
                INSERT INTO voucher_redemptions (voucher_id, merchant_reference, amount, balance_after, outlet_id,
                                                 redeemed_by, redeemed_at, merchant_id)
                VALUES (?, 'GM-POS-88501', 120.00, 0.00, 'GM-AVD-01', 'till', ?, ?)
                """, voucher, ISSUED.plusDays(3), merchant);
        assertThat(jdbc.queryForList("SELECT source FROM voucher_redemptions", String.class))
                .containsOnly("TILL");
        assertThat(jdbc.queryForObject("SELECT voucher_store FROM merchants WHERE merchant_code = 'getmore-groceries'",
                String.class)).isEqualTo("GETMORE");
        assertThat(jdbc.queryForObject("SELECT voucher_store FROM merchants WHERE merchant_code = 'pick-n-pay'",
                String.class)).isEqualTo("LOANS");
    }

    @Test
    @DisplayName("a GetMore voucher's shape is accepted; a loans voucher carrying any GetMore field is refused")
    void codeSourceShapes() {
        migrateTo("37");
        long loan = disbursedLoan();

        assertThat(getMoreVoucher(loan, Map.of())).isPositive();

        assertThatThrownBy(() -> jdbc.update("UPDATE vouchers SET code_source = 'LOANS'"))
                .as("LOANS with GetMore's ids and a lettered code").isInstanceOf(DataIntegrityViolationException.class);
        long loansVoucher = loansVoucher("BRNET-20261001-0007", "a".repeat(64));
        for (String column : new String[]{"external_coupon_id = 1207", "store_mode = 'LIVE'", "held_amount = 1",
                "refunded_amount = 1", "code_prefix = 'SIM-'", "stopped_reason = 'BANK_STOPPED'"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE vouchers SET " + column + " WHERE id = ?", loansVoucher))
                    .as(column).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE vouchers SET code_last4 = '8B1C' WHERE id = ?", loansVoucher))
                .as("a loans code is digits").isInstanceOf(DataIntegrityViolationException.class);
        long other = StaffLoanRows.loan(jdbc, StaffLoanRows.member(jdbc, "E1065", "263771640065"),
                "SGL-2026-000170", "DISBURSED", ISSUED, "GETMORE-SIM-1300");
        assertThatThrownBy(() -> getMoreVoucher(other, Map.of("disbursement_reference", "GETMORE-SIM-1300",
                "code_hmac", "c".repeat(64), "store_mode", "OFF"))).as("OFF is no store")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> getMoreVoucher(other, Map.of("disbursement_reference", "GETMORE-SIM-1300",
                "code_hmac", "c".repeat(64), "code_last4", "8b1c"))).as("the last four as stored: upper case")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(getMoreVoucher(other, Map.of("disbursement_reference", "GETMORE-SIM-1300", "code_hmac",
                "c".repeat(64), "store_mode", "LIVE", "external_coupon_id", 1300L))).isPositive();
        assertThatThrownBy(() -> getMoreVoucher(loan, Map.of("disbursement_reference", "GETMORE-SIM-1300",
                "code_hmac", "c".repeat(64)))).as("one voucher per loan")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refunded back to nothing is allowed, the last spend kept; cancelled with refunds or held is not")
    void refundsAndCancellation() {
        migrateTo("37");
        long voucher = getMoreVoucher(disbursedLoan(), Map.of());

        jdbc.update("UPDATE vouchers SET redeemed_amount = 0, refunded_amount = 45.50, last_redeemed_at = ?,"
                + " last_redeemed_outlet = 'GetMore Avondale', status = 'ISSUED' WHERE id = ?", ISSUED.plusHours(1),
                voucher);
        assertThat(jdbc.queryForObject("SELECT status FROM vouchers WHERE id = ?", String.class, voucher))
                .isEqualTo("ISSUED");

        assertThatThrownBy(() -> jdbc.update("UPDATE vouchers SET status = 'CANCELLED', cancelled_by = 'c',"
                + " cancelled_at = now(), cancellation_reason = 'r' WHERE id = ?", voucher))
                .as("refunded, so it was spent").isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE vouchers SET refunded_amount = 0, held_amount = 10.00, last_redeemed_at = NULL,"
                + " last_redeemed_outlet = NULL WHERE id = ?", voucher);
        assertThatThrownBy(() -> jdbc.update("UPDATE vouchers SET status = 'CANCELLED', cancelled_by = 'c',"
                + " cancelled_at = now(), cancellation_reason = 'r' WHERE id = ?", voucher))
                .as("a sale held at the till").isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE vouchers SET held_amount = 0, status = 'CANCELLED', cancelled_by = 'c',"
                + " cancelled_at = now(), cancellation_reason = 'r' WHERE id = ?", voucher);
    }

    @Test
    @DisplayName("a loan cancelled after its GetMore voucher was issued is allowed; any other paid-out loan is not")
    void cancelledLoans() {
        migrateTo("37");

        long getMore = StaffLoanRows.loan(jdbc, StaffLoanRows.member(jdbc, "E1064", "263771640064"),
                "SGL-2026-000164", "DISBURSED", ISSUED, "GETMORE-SIM-1207");
        jdbc.update("UPDATE staff_loans SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'credit1',"
                + " cancellation_reason = 'accepted by mistake' WHERE id = ?", getMore);

        long brnet = StaffLoanRows.loan(jdbc, StaffLoanRows.member(jdbc, "E1065", "263771640065"),
                "SGL-2026-000165", "DISBURSED", ISSUED, "BRNET-20261014-0001");
        assertThatThrownBy(() -> jdbc.update("UPDATE staff_loans SET status = 'CANCELLED', cancelled_at = now(),"
                + " cancelled_by = 'credit1', cancellation_reason = 'r' WHERE id = ?", brnet))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> StaffLoanRows.loan(jdbc, StaffLoanRows.member(jdbc, "E1066", "263771640066"),
                "SGL-2026-000166", "AWAITING_DISBURSEMENT", ISSUED, "GETMORE-SIM-1300")).as("awaiting with a payout")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> StaffLoanRows.loan(jdbc, StaffLoanRows.member(jdbc, "E1067", "263771640067"),
                "SGL-2026-000167", "DISBURSED", null, null)).as("paid out with no payout")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> StaffLoanRows.loan(jdbc, StaffLoanRows.member(jdbc, "E1068", "263771640068"),
                "SGL-2026-000168", "DISBURSED", ISSUED, null)).as("a payout with no reference")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(StaffLoanRows.loan(jdbc, StaffLoanRows.member(jdbc, "E1069", "263771640069"),
                "SGL-2026-000169", "AWAITING_DISBURSEMENT", null, null)).as("awaiting, as accepted").isPositive();
    }

    @Test
    @DisplayName("each credit memo is refunded once, each order level spent once; sync rows carry their order")
    void syncRowsOnce() {
        migrateTo("37");
        long voucher = getMoreVoucher(disbursedLoan(), Map.of());
        String refund = """
                INSERT INTO voucher_refunds (voucher_id, merchant_id, merchant_reference, store_mode,
                                             external_order_id, external_creditmemo_id, amount, balance_after,
                                             refunded_at, recorded_at, recorded_by)
                VALUES (?, ?, ?, 'SIMULATED', 12, 3, 10.00, 60.00, now(), now(), 'getmore-simulator')
                """;
        jdbc.update(refund, voucher, merchant, "GMS:CM:3");
        assertThatThrownBy(() -> jdbc.update(refund, voucher, merchant, "GMS:CM:3-again"))
                .as("the same memo twice").isInstanceOf(DataIntegrityViolationException.class);

        String spend = """
                INSERT INTO voucher_redemptions (voucher_id, merchant_id, merchant_reference, amount, balance_after,
                                                 outlet_id, redeemed_by, redeemed_at, source, store_mode,
                                                 external_order_id, external_order_number, recorded_at)
                VALUES (?, ?, ?, 45.50, 104.50, 'store-2', 'getmore-simulator', now(), 'GETMORE_SYNC', 'SIMULATED',
                        ?, 'SIM000000012', ?)
                """;
        jdbc.update(spend, voucher, merchant, "GMS:O:12:4550", 12L, LocalDateTime.now());
        assertThatThrownBy(() -> jdbc.update(spend, voucher, merchant, "GMS:O:12:4550", 12L, LocalDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(spend, voucher, merchant, "GMS:O:13:5450", null, LocalDateTime.now()))
                .as("a sync row names its order").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(spend, voucher, merchant, "GMS:O:13:5450", 13L, null))
                .as("and when it was recorded").isInstanceOf(DataIntegrityViolationException.class);
    }
}

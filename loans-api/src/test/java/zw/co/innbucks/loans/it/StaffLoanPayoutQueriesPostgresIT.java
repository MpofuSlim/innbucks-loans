package zw.co.innbucks.loans.it;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.staff.loan.StaffLoan;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutBacklog;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutDestination;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanRepository;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cash payout's queues and gauges as {@link StaffLoanRepository} reads them, against real Postgres and the mapped
 * entity: each queue hands out exactly its own payouts, in id order above the cursor ({@code IdChunks}' contract); the
 * literals its partial index is built on reach the SQL inlined, so the planner can use the index; the backlog groups
 * what the gauges show; and a cash loan's payout columns round-trip through the entity.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "it"})
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
class StaffLoanPayoutQueriesPostgresIT {

    private static final Pageable CHUNK = PageRequest.of(0, 100);
    private static final String WALLET = "263772123123";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = ItPostgres.createDatabase("loans_payout_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ItPostgres::user);
        registry.add("spring.datasource.password", ItPostgres::password);
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", CapturedSql.class::getName);
    }

    @Autowired private StaffLoanRepository loans;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    private final Map<String, Long> ids = new LinkedHashMap<>();
    private long sequence;

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM staff_loans");
        sequence = jdbc.queryForObject("SELECT count(*) FROM staff_members", Long.class) + 1;
        ids.put("ready1", loan("AWAITING_DISBURSEMENT", cash("READY"), now.minusDays(3)));
        ids.put("ready2", loan("AWAITING_DISBURSEMENT", cash("READY"), now.minusDays(1)));
        ids.put("staleSending", loan("AWAITING_DISBURSEMENT",
                with(cash("SENDING"), "payout_claimed_at", now.minusHours(1)), now.minusDays(2)));
        ids.put("freshSending", loan("AWAITING_DISBURSEMENT",
                with(cash("SENDING"), "payout_claimed_at", now.minusMinutes(1)), now.minusDays(2)));
        ids.put("booked", loan("AWAITING_DISBURSEMENT", cash("BOOKED"), now.minusDays(2)));
        ids.put("inDoubt", loan("AWAITING_DISBURSEMENT", cash("IN_DOUBT"), now.minusDays(2)));
        ids.put("refused", loan("AWAITING_DISBURSEMENT", cash("REFUSED"), now.minusDays(2)));
        ids.put("failedRecently", loan("CANCELLED", failed(now.minusDays(2), null), now.minusDays(5)));
        ids.put("failedLongAgo", loan("CANCELLED", failed(now.minusDays(30), null), now.minusDays(40)));
        ids.put("paidAfterCancel", loan("CANCELLED", failed(now.minusDays(3), now.minusDays(1)), now.minusDays(5)));
        ids.put("voucher", loan("AWAITING_DISBURSEMENT", Map.of(), now.minusDays(4)));
        ids.put("paid", loan("DISBURSED", with(with(cash("PAID"), "disbursed_at", now.minusDays(1)),
                "disbursement_reference", "SGL-PAID"), now.minusDays(2)));
    }

    @Test
    @DisplayName("each queue hands out exactly its own cash payouts, in id order above the cursor")
    void eachQueueHoldsItsOwn() {
        assertThat(loans.findPayoutReadyIds(0, CHUNK)).containsExactly(id("ready1"), id("ready2"));
        assertThat(loans.findPayoutReadyIds(id("ready1"), CHUNK)).containsExactly(id("ready2"));
        assertThat(loans.findPayoutReadyIds(0, PageRequest.of(0, 1))).containsExactly(id("ready1"));
        assertThat(loans.findPayoutAwaitingInquiryIds(0, CHUNK)).containsExactly(id("booked"), id("inDoubt"));
        assertThat(loans.findPayoutClaimedBeforeIds(now.minusMinutes(30), 0, CHUNK))
                .containsExactly(id("staleSending"));
        assertThat(loans.findPayoutFailedSinceIds(now.minusDays(14), 0, CHUNK))
                .as("not one confirmed too long ago, nor one already found paid").containsExactly(id("failedRecently"));
    }

    @Test
    @DisplayName("the queues' literals reach the SQL inlined, as their partial indexes need")
    void theQueuesInlineTheirLiterals() {
        CapturedSql.clear();
        loans.findPayoutReadyIds(0, CHUNK);
        loans.findPayoutAwaitingInquiryIds(0, CHUNK);
        loans.findPayoutClaimedBeforeIds(now, 0, CHUNK);
        loans.findPayoutFailedSinceIds(now, 0, CHUNK);

        List<String> sql = CapturedSql.statements();
        assertThat(sql).hasSize(4);
        for (String pending : sql.subList(0, 3)) {
            assertThat(pending).contains("'CASH'", "'AWAITING_DISBURSEMENT'");
        }
        assertThat(sql.get(0)).contains("'READY'");
        assertThat(sql.get(1)).contains("'BOOKED'", "'IN_DOUBT'");
        assertThat(sql.get(2)).contains("'SENDING'");
        assertThat(sql.get(3)).contains("'FAILED'");
    }

    @Test
    @DisplayName("a 2xx whose settle failed is recorded as seen by the bank, once, its version moved; never on a voucher"
            + " loan")
    void markSeenByBank() {
        long sending = id("staleSending");
        long version = jdbc.queryForObject("SELECT version FROM staff_loans WHERE id = ?", Long.class, sending);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Integer marked = transaction.execute(status -> loans.markPayoutSeenByBank(sending, now,
                "{\"responseCode\":\"000\"}"));
        Integer again = transaction.execute(status -> loans.markPayoutSeenByBank(sending, now.plusMinutes(1), "{}"));
        Integer voucher = transaction.execute(status -> loans.markPayoutSeenByBank(id("voucher"), now, "{}"));

        assertThat(marked).isEqualTo(1);
        assertThat(again).as("already seen").isZero();
        assertThat(voucher).isZero();
        Map<String, Object> row = jdbc.queryForMap("SELECT payout_status, payout_bank_seen_at, payout_apply_answer,"
                + " version FROM staff_loans WHERE id = ?", sending);
        assertThat(row.get("payout_status")).as("nothing else moves").isEqualTo("SENDING");
        assertThat(((java.sql.Timestamp) row.get("payout_bank_seen_at")).toLocalDateTime()).isEqualTo(now);
        assertThat(row.get("payout_apply_answer")).isEqualTo("{\"responseCode\":\"000\"}");
        assertThat(row.get("version")).isEqualTo(version + 1);
        assertThat(jdbc.queryForObject("SELECT payout_bank_seen_at FROM staff_loans WHERE id = ?",
                java.sql.Timestamp.class, id("voucher"))).isNull();
    }

    @Test
    @DisplayName("the backlog: how many cash payouts await disbursement in each status, and how old the oldest is")
    void theBacklogGroupsByStatus() {
        Map<StaffLoanPayoutStatus, StaffLoanPayoutBacklog> backlog = loans.payoutBacklog().stream()
                .collect(Collectors.toMap(StaffLoanPayoutBacklog::status, Function.identity()));

        assertThat(backlog.keySet()).containsExactlyInAnyOrder(StaffLoanPayoutStatus.READY,
                StaffLoanPayoutStatus.SENDING, StaffLoanPayoutStatus.BOOKED, StaffLoanPayoutStatus.IN_DOUBT,
                StaffLoanPayoutStatus.REFUSED);
        assertThat(backlog.get(StaffLoanPayoutStatus.READY).loans()).isEqualTo(2);
        assertThat(backlog.get(StaffLoanPayoutStatus.READY).oldestAcceptedAt()).isEqualTo(now.minusDays(3));
        assertThat(backlog.get(StaffLoanPayoutStatus.READY).oldestAge(now)).hasDays(3);
        assertThat(backlog.get(StaffLoanPayoutStatus.SENDING).loans()).isEqualTo(2);
        assertThat(backlog.get(StaffLoanPayoutStatus.SENDING).oldestAge(now)).as("since its claim").hasHours(1);
        assertThat(backlog.get(StaffLoanPayoutStatus.REFUSED).loans()).isEqualTo(1);
    }

    @Test
    @DisplayName("found paid after a cancel holds back that member's payouts; nobody else's")
    void paidAfterCancelIsPerMember() {
        long flagged = memberOf("paidAfterCancel");

        assertThat(loans.existsByStaffMemberIdAndPayoutPaidAfterCancelAtIsNotNull(flagged)).isTrue();
        assertThat(loans.existsByStaffMemberIdAndPayoutPaidAfterCancelAtIsNotNull(memberOf("failedRecently")))
                .isFalse();
    }

    @Test
    @DisplayName("a member's loans by status, as ids only, newest first")
    void aMembersLoanIds() {
        long member = memberOf("ready1");

        assertThat(loans.findIdsByStaffMemberIdAndStatusIn(member, List.of(StaffLoanStatus.AWAITING_DISBURSEMENT)))
                .containsExactly(id("ready1"));
        assertThat(loans.findIdsByStaffMemberIdAndStatusIn(member, List.of(StaffLoanStatus.DISBURSED))).isEmpty();
    }

    @Test
    @DisplayName("a cash loan's payout columns round-trip through the entity; a voucher loan reads as VOUCHER")
    void theEntityReadsThePayout() {
        StaffLoan cash = new TransactionTemplate(transactionManager).execute(status ->
                loans.lockById(id("inDoubt")).orElseThrow());
        StaffLoan voucher = loans.findById(id("voucher")).orElseThrow();

        assertThat(cash.getPayoutMethod()).isEqualTo(StaffLoanPayoutMethod.CASH);
        assertThat(cash.getPayoutDestination()).isEqualTo(StaffLoanPayoutDestination.VEENGU_WALLET);
        assertThat(cash.getPayoutAccount()).isEqualTo(WALLET);
        assertThat(cash.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(cash.payoutInFlight()).isTrue();
        assertThat(voucher.getPayoutMethod()).isEqualTo(StaffLoanPayoutMethod.VOUCHER);
        assertThat(voucher.getPayoutStatus()).isNull();
        assertThat(loans.findById(id("paidAfterCancel")).orElseThrow().getPayoutPaidAfterCancelAt()).isNotNull();
    }

    // --- fixtures --------------------------------------------------------------------------------------------------

    private long id(String name) {
        return ids.get(name);
    }

    private long memberOf(String name) {
        return jdbc.queryForObject("SELECT staff_member_id FROM staff_loans WHERE id = ?", Long.class, id(name));
    }

    private static Map<String, Object> cash(String payout) {
        return Map.of("payout_method", "CASH", "payout_destination", "VEENGU_WALLET", "payout_account", WALLET,
                "payout_status", payout);
    }

    private Map<String, Object> failed(LocalDateTime failedAt, LocalDateTime paidAfterCancelAt) {
        Map<String, Object> columns = with(with(with(with(cash("FAILED"), "payout_failed_at", failedAt),
                "cancelled_at", failedAt), "cancelled_by", "super.admin"), "cancellation_reason", "Not booked");
        return paidAfterCancelAt == null ? columns : with(columns, "payout_paid_after_cancel_at", paidAfterCancelAt);
    }

    private static Map<String, Object> with(Map<String, Object> columns, String column, Object value) {
        Map<String, Object> more = new LinkedHashMap<>(columns);
        more.put(column, value instanceof LocalDateTime at ? Timestamp.valueOf(at) : value);
        return more;
    }

    /** A loan of a member and offer of its own (their own parents unchecked: not this test's subject). */
    private long loan(String status, Map<String, Object> columns, LocalDateTime acceptedAt) {
        long n = sequence++;
        return new TransactionTemplate(transactionManager).execute(tx -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            long member = jdbc.queryForObject("""
                    INSERT INTO staff_members (employee_number, full_name, national_id, msisdn, grade, department,
                        employment_status, engagement_date, wallet_account_number, status_changed_at,
                        created_batch_id, created_at, updated_batch_id, updated_at)
                    VALUES (?, 'Chipo Banda', ?, ?, 'G4', 'Operations', 'ACTIVE', DATE '2015-02-02', '1234567890',
                        now(), 1, now(), 1, now())
                    RETURNING id""", Long.class, "PAY" + n, "63" + (3345678 + n) + "B42",
                    "2637731" + String.format("%05d", n));
            long offer = jdbc.queryForObject("""
                    INSERT INTO staff_offers (staff_member_id, cycle_start, grade, score_band, grade_limit_change_id,
                        amount, issued_at, expires_at, status, closed_at, origin)
                    VALUES (?, DATE '2026-10-26', 'G4', 'A', 1, 150.00, now(), now() + interval '7 days', 'TAKEN_UP',
                        now(), 'APPLY')
                    RETURNING id""", Long.class, member);
            Long merchant = jdbc.query("SELECT id FROM merchants WHERE id = 990002",
                    rs -> rs.next() ? rs.getLong(1) : null);
            if (merchant == null) {
                merchant = jdbc.queryForObject("INSERT INTO merchants (id, created_date, version) VALUES (990002,"
                        + " now(), 0) RETURNING id", Long.class);
            }
            StringBuilder names = new StringBuilder("reference, staff_member_id, offer_id, employee_number, full_name,"
                    + " msisdn, grade, merchant_id, amount, currency, interest_rate, total_repayable, due_date,"
                    + " unredeemed_voucher_treatment, status, accepted_at");
            StringBuilder marks = new StringBuilder("?, ?, ?, ?, 'Chipo Banda', ?, 'G4', ?, 150.00, 'USD', 0, 150.00,"
                    + " DATE '2026-11-20', 'DEBT_STANDS', ?, ?");
            List<Object> values = new ArrayList<>(List.of("SGL-PAY-" + n, member, offer, "PAY" + n, WALLET, merchant,
                    status, Timestamp.valueOf(acceptedAt)));
            columns.forEach((column, value) -> {
                names.append(", ").append(column);
                marks.append(", ?");
                values.add(value);
            });
            return jdbc.queryForObject("INSERT INTO staff_loans (" + names + ") VALUES (" + marks + ") RETURNING id",
                    Long.class, values.toArray());
        });
    }
}

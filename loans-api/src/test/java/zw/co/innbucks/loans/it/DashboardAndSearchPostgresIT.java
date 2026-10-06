package zw.co.innbucks.loans.it;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.StartupTask;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.core.api.DashboardStatsResponse;
import zw.co.innbucks.loans.core.api.SearchUserRequest;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionGroupRepository;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.dashboard.DashboardService;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.loan.Address;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchRepository;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query rewrites that must change nothing a client sees, against real Postgres:
 * <ul>
 *   <li>the dashboard's two statements return exactly what its nine did (the old queries are run here as the oracle,
 *       on loans in every combination of status, with nulls and unpaid sums), through the service and over HTTP;</li>
 *   <li>the user search reads one page in the database and returns what the in-memory paging returned;</li>
 *   <li>the substring searches compare exactly the expressions V34's trigram indexes are built on: the SQL Hibernate
 *       really sends is captured, its LIKE operands read out of it, and each one is shown to be served by its
 *       index.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "it"})
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
class DashboardAndSearchPostgresIT {

    private static final String BASE = "/lending/v1";

    /** A LIKE operand in Hibernate's SQL: a column, or a function of one ({@code lower(sm1_0.full_name)}). */
    private static final Pattern LIKE_OPERAND =
            Pattern.compile("(?i)((?:lower|upper)\\(\\s*[a-z0-9_]+\\.[a-z0-9_]+\\s*\\)|[a-z0-9_]+\\.[a-z0-9_]+)\\s+like\\s");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = ItPostgres.createDatabase("loans_queries_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ItPostgres::user);
        registry.add("spring.datasource.password", ItPostgres::password);
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", CapturedSql.class::getName);
        // Every dashboard call here must read the database: the cache is pinned by DashboardServiceImplTest.
        registry.add("innbucks.dashboard.cache-ttl", () -> "PT0S");
    }

    @LocalServerPort
    int port;

    @Autowired private EntityManagerFactory emf;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private DashboardService dashboardService;
    @Autowired private AuthService authService;
    @Autowired private StaffRegisterService staffRegisterService;
    @Autowired private LoanRepository loanRepository;
    @Autowired private LoanBatchRepository loanBatchRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CommissionGroupRepository commissionGroupRepository;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    /** The application's own mapper: the one the controller's response is written with. */
    @Autowired private JsonMapper jsonMapper;
    private Statistics stats;
    private String adminToken;
    private static int seeded;

    @BeforeEach
    void setUp() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).as("hibernate.generate_statistics in the it profile").isTrue();
        // Before anything is counted: the admin is a user, and a merchant, of its own.
        adminToken = jwtService.generateToken(superAdmin());
    }

    // ── The dashboard ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the dashboard: two statements, the same figures as the nine they replaced, empty and populated")
    void dashboardEqualsTheOldQueries() throws Exception {
        // An empty loans table first: every tile 0 and both sums a plain 0, as coalesce(sum(...), 0) answered.
        jdbc.update("DELETE FROM loans");
        assertSameAsTheOldQueries();

        // Every SSB status, every Credit decision and every disbursement status, each also null; paid loans with and
        // without amounts; enough rows that every group has more than one loan.
        List<LoanApprovalStatus> approvals = new ArrayList<>(List.of(LoanApprovalStatus.values()));
        approvals.add(null);
        List<InternalApprovalStatus> decisions = new ArrayList<>(List.of(InternalApprovalStatus.values()));
        decisions.add(null);
        List<LoanDisbursementStatus> disbursements = new ArrayList<>(List.of(LoanDisbursementStatus.values()));
        disbursements.add(null);
        Merchant merchant = merchant("DASH");
        User agent = user("dash-agent", merchant);
        int n = 0;
        for (LoanApprovalStatus approval : approvals) {
            for (InternalApprovalStatus decision : decisions) {
                for (LoanDisbursementStatus disbursement : disbursements) {
                    for (int copy = 0; copy < 2; copy++) {
                        n++;
                        BigDecimal disbursed = n % 5 == 0 ? null : BigDecimal.valueOf(n * 3757L % 90_000, 2);
                        BigDecimal commission = n % 3 == 0 ? null : BigDecimal.valueOf(100 + n % 97, 2);
                        insertLoan(merchant, agent, approval, decision, disbursement, disbursed, commission);
                    }
                }
            }
        }
        assertThat(loanRepository.count()).isEqualTo(n);
        assertSameAsTheOldQueries();
    }

    private void assertSameAsTheOldQueries() throws Exception {
        long[] oldStatements = new long[1];
        DashboardStatsResponse oracle = countStatements(this::oldDashboard, oldStatements);
        long[] newStatements = new long[1];
        DashboardStatsResponse actual = countStatements(dashboardService::getDashboardStats, newStatements);

        assertThat(actual).isEqualTo(oracle);
        // BigDecimal equality includes the scale, so the JSON prints the same digits.
        assertThat(actual.getTotalDisbursedAmount().scale()).isEqualTo(oracle.getTotalDisbursedAmount().scale());
        assertThat(actual.getTotalAgentCommission().scale()).isEqualTo(oracle.getTotalAgentCommission().scale());
        assertThat(new ArrayList<>(actual.getLoansBySsbApprovalStatus().keySet()))
                .containsExactlyElementsOf(oracle.getLoansBySsbApprovalStatus().keySet());
        assertThat(new ArrayList<>(actual.getLoansByDisbursementStatus().keySet()))
                .containsExactlyElementsOf(oracle.getLoansByDisbursementStatus().keySet());
        assertThat(oldStatements[0]).isEqualTo(9);
        assertThat(newStatements[0]).isEqualTo(2);
        System.out.println("[statements] dashboard: " + oldStatements[0] + " -> " + newStatements[0]);

        // And over HTTP, as the console reads it: byte for byte the body the old figures serialise to.
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(BASE + "/dashboard"))
                .header("Authorization", "Bearer " + adminToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(jsonMapper.writeValueAsString(ApiResult.ok(oracle)));
    }

    /** The dashboard as it was computed before: the old queries, verbatim, and the old assembly. */
    private DashboardStatsResponse oldDashboard() {
        EntityManager em = emf.createEntityManager();
        try {
            Map<String, Long> byApproval = new LinkedHashMap<>();
            for (LoanApprovalStatus status : LoanApprovalStatus.values()) {
                byApproval.put(status.name(), 0L);
            }
            for (Object[] row : em.createQuery("select l.loanApprovalStatus, count(l) from Loan l"
                    + " group by l.loanApprovalStatus", Object[].class).getResultList()) {
                if (row[0] != null) {
                    byApproval.put(((LoanApprovalStatus) row[0]).name(), (Long) row[1]);
                }
            }
            Map<String, Long> byDisbursement = new LinkedHashMap<>();
            for (LoanDisbursementStatus status : LoanDisbursementStatus.values()) {
                byDisbursement.put(status.name(), 0L);
            }
            for (Object[] row : em.createQuery("select l.disbursementStatus, count(l) from Loan l where"
                    + " l.disbursementStatus is not null group by l.disbursementStatus", Object[].class)
                    .getResultList()) {
                if (row[0] != null) {
                    byDisbursement.put(((LoanDisbursementStatus) row[0]).name(), (Long) row[1]);
                }
            }
            return DashboardStatsResponse.builder()
                    .totalLoans(loanRepository.count())
                    .loansBySsbApprovalStatus(byApproval)
                    .pendingCreditApprovals(em.createQuery("select count(l) from Loan l where l.loanApprovalStatus"
                                    + " = :approval and l.internalApprovalStatus = :decision", Long.class)
                            .setParameter("approval", LoanApprovalStatus.APPROVED)
                            .setParameter("decision", InternalApprovalStatus.PENDING).getSingleResult())
                    .loansByDisbursementStatus(byDisbursement)
                    .totalDisbursedAmount(em.createQuery("""
                            select coalesce(sum(l.disbursedAmount), 0) from Loan l
                            where l.disbursementStatus = zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
                            """, BigDecimal.class).getSingleResult())
                    .totalAgentCommission(em.createQuery("""
                            select coalesce(sum(l.agentCommission), 0) from Loan l
                            where l.disbursementStatus = zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
                            """, BigDecimal.class).getSingleResult())
                    .merchantCount(merchantRepository.count())
                    .userCount(userRepository.count())
                    .batchCount(loanBatchRepository.count())
                    .build();
        } finally {
            em.close();
        }
    }

    // ── The user search ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the user search: one page read by the database, the same page the in-memory paging returned")
    void userSearchPagesInTheDatabase() {
        Merchant merchant = merchant("SRCH");
        for (int i = 1; i <= 23; i++) {
            user((i % 2 == 0 ? "Search-Agent-" : "search_agent_") + i, merchant);
        }
        user("unrelated-person", merchant);

        for (String text : List.of("search", "SEARCH-agent", "_agent_", "agent-1", "nobody-has-this")) {
            List<User> matches = everyMatch(text);
            for (int size : List.of(5, 10, 50)) {
                for (int page = 1; page <= (matches.size() / size) + 2; page++) {
                    List<String> expected = matches.stream().skip((long) (page - 1) * size).limit(size)
                            .map(User::getUsername).toList();
                    List<String> actual = authService.search(request(text, page, size)).stream()
                            .map(UserResponse::username).toList();
                    assertThat(actual).as("'%s' page %d of %d", text, page, size).isEqualTo(expected);
                }
            }
        }
        // LIKE's wildcards are literal: '_' matches only an underscore, never any character.
        assertThat(everyMatch("_agent_")).hasSize(12).allSatisfy(u -> assertThat(u.getUsername()).contains("_"));

        // The old search loaded every match (and each one's groups, merchant and commission group); the new one
        // reads one page. Statements stay level (the associations batch-load either way); the rows do not.
        stats.clear();
        oldSearchPage("search", 1, 5);
        long statementsBefore = stats.getPrepareStatementCount();
        long usersBefore = stats.getEntityLoadCount();
        stats.clear();
        authService.search(request("search", 1, 5));
        long statementsAfter = stats.getPrepareStatementCount();
        long usersAfter = stats.getEntityLoadCount();
        System.out.println("[statements] user search, 23 matches, page of 5: statements " + statementsBefore + " -> "
                + statementsAfter + ", entities loaded " + usersBefore + " -> " + usersAfter);
        assertThat(statementsAfter).isLessThanOrEqualTo(statementsBefore);
        assertThat(usersBefore - usersAfter).as("the 18 matches beyond the page are no longer loaded").isEqualTo(18);
    }

    /** Every user whose username contains {@code text} ignoring case, in id order: what the pages are cut from. */
    private List<User> everyMatch(String text) {
        String wanted = text.toUpperCase(Locale.ROOT);
        return userRepository.findAll().stream()
                .filter(user -> user.getUsername().toUpperCase(Locale.ROOT).contains(wanted))
                .sorted(Comparator.comparing(User::getId))
                .toList();
    }

    /** The search as it was: every match loaded, then skipped and limited in memory. */
    private List<UserResponse> oldSearchPage(String text, int page, int size) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("select u from User u where upper(u.username) like upper(:text) escape '\\'",
                            User.class)
                    .setParameter("text", "%" + text + "%").getResultList().stream()
                    .skip((long) (page - 1) * size).limit(size).map(UserResponse::from).toList();
        } finally {
            em.close();
        }
    }

    private static SearchUserRequest request(String text, int page, int size) {
        SearchUserRequest request = new SearchUserRequest();
        request.setSearchText(text);
        request.setPageNumber(page);
        request.setPageSize(size);
        return request;
    }

    // ── The trigram indexes (V34) ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the staff register search compares exactly what V34 indexes, and each comparison uses its index")
    void staffRegisterSearchUsesTheTrigramIndexes() throws Exception {
        CapturedSql.clear();
        staffRegisterService.members(null, null, "fin", "rudo 77123", PageRequest.of(0, 20));
        Set<String> operands = likeOperands(CapturedSql.statements());

        assertThat(operands).as("the LIKE operands of the staff register search; V34's indexes must match them")
                .containsExactlyInAnyOrder("lower(employee_number)", "lower(full_name)", "lower(department)",
                        "msisdn");
        assertIndexServes("staff_members", "lower(employee_number)", "idx_staff_members_employee_number_trgm");
        assertIndexServes("staff_members", "lower(full_name)", "idx_staff_members_full_name_trgm");
        assertIndexServes("staff_members", "lower(department)", "idx_staff_members_department_trgm");
        assertIndexServes("staff_members", "msisdn", "idx_staff_members_msisdn_trgm");
    }

    @Test
    @DisplayName("the user search compares exactly what V34 indexes, and the comparison uses its index")
    void userSearchUsesTheTrigramIndex() throws Exception {
        CapturedSql.clear();
        authService.search(request("agent", 1, 10));
        Set<String> operands = likeOperands(CapturedSql.statements());

        assertThat(operands).as("the LIKE operand of the user search; V34's index must match it")
                .containsExactly("upper(username)");
        assertIndexServes("users", "upper(username)", "idx_users_username_trgm");
    }

    /** The LIKE operands in these statements, table aliases dropped: {@code lower(sm1_0.full_name)} → {@code lower(full_name)}. */
    private static Set<String> likeOperands(List<String> statements) {
        Set<String> operands = new TreeSet<>();
        for (String sql : statements) {
            Matcher like = LIKE_OPERAND.matcher(sql);
            while (like.find()) {
                operands.add(like.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", "")
                        .replaceAll("[a-z0-9_]+\\.", ""));
            }
        }
        return operands;
    }

    /**
     * The planner answers {@code <expression> LIKE '%text%'} on {@code table} from {@code index}. Sequential scans are
     * switched off for the one statement, so the plan shows whether an index CAN serve the expression at all; an
     * index on any other expression cannot, and the plan falls back to the table.
     */
    private void assertIndexServes(String table, String expression, String index) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL enable_seqscan = off");
                try (ResultSet rows = statement.executeQuery("EXPLAIN (COSTS OFF) SELECT id FROM " + table
                        + " WHERE " + expression + " LIKE '%abcd%'")) {
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append('\n');
                    }
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        }
        assertThat(plan.toString()).as("plan of %s LIKE on %s", expression, table).contains(index);
    }

    // ── Seeding and measuring ────────────────────────────────────────────────────────────────────────────────

    private void insertLoan(Merchant merchant, User agent, LoanApprovalStatus approval, InternalApprovalStatus decision,
                            LoanDisbursementStatus disbursement, BigDecimal disbursed, BigDecimal commission) {
        int n = ++seeded;
        Loan loan = loanRepository.save(Loan.builder()
                .principal(new BigDecimal("500.00")).tenor(6)
                .monthlyInstallment(new BigDecimal("98.50")).grossedMonthlyDeduction(new BigDecimal("98.50"))
                .firstName("Rudo").lastName("Moyo " + n)
                .ecNumber(String.format("%07dA", 2_000_000 + n)).nationalIdNumber("63-" + (200000 + n) + "A63")
                .mobileNumber(String.format("26377%07d", n)).walletNumber(String.format("26377%07d", n))
                .dateOfBirth(LocalDate.of(1990, 1, 1)).address(address())
                .merchant(merchant).createdByUser(agent).createdBy(agent.getUsername()).build());
        // The statuses and amounts exactly as wanted, nulls included, past any default the entity applies.
        jdbc.update("UPDATE loans SET loan_status = ?, internal_approval_status = ?, disbursement_status = ?,"
                        + " disbursed_amount = ?, agent_commission = ? WHERE id = ?",
                approval == null ? null : approval.name(), decision == null ? null : decision.name(),
                disbursement == null ? null : disbursement.name(), disbursed, commission, loan.getId());
    }

    private static Address address() {
        Address address = new Address();
        address.setStreet("12 Samora Machel Ave");
        address.setCity("Harare");
        address.setCountry("Zimbabwe");
        return address;
    }

    private Merchant merchant(String code) {
        CommissionGroup group = commissionGroupRepository.findByNameIgnoreCase(StartupTask.ZERO_BASED_DEFAULT)
                .orElseThrow();
        return merchantRepository.save(Merchant.builder().merchantCode("IT-" + code + "-" + UUID.randomUUID()
                        .toString().substring(0, 6)).companyName("Merchant " + code)
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).accountNumber("263771000001")
                .commissionGroup(group).commissionStructure(CommissionStructure.MERCHANT_DEFINED).build());
    }

    private User user(String username, Merchant merchant) {
        User user = new User();
        user.setUsername(username);
        user.setExternalSystemId(UUID.randomUUID().toString());
        user.setFirstName("Agent");
        user.setLastName(username);
        user.setGroups(new HashSet<>(Set.of(UserGroup.AGENTS)));
        user.setMerchant(merchant);
        user.setTemporaryPassword(false);
        return userRepository.save(user);
    }

    private User superAdmin() {
        return userRepository.findByUsername("it-super-admin").orElseGet(() -> {
            User user = user("it-super-admin", merchant("ADMIN"));
            user.setGroups(new HashSet<>(Set.of(UserGroup.SUPER_ADMIN)));
            return userRepository.save(user);
        });
    }

    private <T> T countStatements(Supplier<T> work, long[] statements) {
        stats.clear();
        T result = work.get();
        statements[0] = stats.getPrepareStatementCount();
        return result;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}

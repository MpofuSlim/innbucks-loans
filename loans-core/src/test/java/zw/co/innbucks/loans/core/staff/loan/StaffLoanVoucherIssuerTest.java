package zw.co.innbucks.loans.core.staff.loan;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.getmore.Deadline;
import zw.co.innbucks.loans.core.getmore.FindOrCreate;
import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.VoucherStore;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer.Cleanup;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer.Outcome;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer.Trigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherCommand;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherCodeSource;
import zw.co.innbucks.loans.core.voucher.VoucherCodeVault;
import zw.co.innbucks.loans.core.voucher.VoucherDeliveryStatus;
import zw.co.innbucks.loans.core.voucher.VoucherProduct;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issuing Rutendo Chari's voucher for SGL-2026-000164 (USD 150.00, due 20 November 2026) at 10:20:05 in Harare on 14
 * October 2026, over an in-memory GetMore store that refuses to be called with a transaction open, and transactions
 * that are really open while their work runs. What it pins: the claim, the call and the settle are separate
 * transactions with nothing held across GetMore; a generate is counted and committed before it is sent and never sent
 * twice without an operator; a lost answer is adopted, a lost mint parks; what GetMore did not do is recorded, never
 * thrown; and a coupon loans will not keep (a lost claim, a cancelled loan) is withdrawn.
 */
class StaffLoanVoucherIssuerTest {

    private static final Instant START = Instant.parse("2026-10-14T08:20:05Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(START, ZoneOffset.UTC);
    private static final String NONCE = "K7Q2M3XW";
    private static final String PREFIX = "SIM-" + NONCE + "-";
    private static final String CODE = PREFIX + GetMoreFakes.STORY_RANDOM;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final GetMoreFakes.MutableClock clock = new GetMoreFakes.MutableClock(START);
    private final MarketTimeZone market = new MarketTimeZone("ZW", clock);
    private final GetMoreFakes.Store store = new GetMoreFakes.Store(clock, events);
    private final GetMoreProperties properties = new GetMoreProperties();
    private final GetMoreAmountBinding binding = mock(GetMoreAmountBinding.class);
    private final StaffLoanVoucherIssuanceRepository issuances = mock(StaffLoanVoucherIssuanceRepository.class);
    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final VoucherService voucherService = mock(VoucherService.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService audit = mock(AuditService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<StaffLoanVoucherIssuance> rows = new CopyOnWriteArrayList<>();
    private final Set<String> takenPrefixes = new HashSet<>();
    private final Deque<String> nonces = new ArrayDeque<>();
    private final AtomicReference<Voucher> issued = new AtomicReference<>();
    private VoucherCodeVault vault = GetMoreFakes.vault();
    private StaffLoan loan;
    private StaffLoanVoucherIssuer issuer;

    @BeforeEach
    void setUp() {
        properties.setMode(GetMoreMode.SIMULATED);
        when(binding.partialSpending()).thenReturn(true);
        when(authService.getLoggedInUsername()).thenReturn("SYSTEM");
        loan = loan(LocalDate.of(2026, 11, 20));
        when(loans.findByReference("SGL-2026-000164")).thenAnswer(i -> Optional.of(loan));
        when(loans.findById(164L)).thenAnswer(i -> Optional.of(loan));
        when(loans.lockById(164L)).thenAnswer(i -> Optional.of(loan));
        when(loans.findAllById(any())).thenAnswer(i -> List.of(loan));
        stubIssuances();
        when(voucherService.expiryFor(any())).thenAnswer(i -> market.endOfDayUtc(market.localDay(
                i.<LocalDateTime>getArgument(0)).plusDays(30)).truncatedTo(ChronoUnit.MICROS));
        when(voucherService.issueFromGetMore(any())).thenAnswer(i -> {
            Voucher voucher = voucherOf(i.getArgument(0));
            issued.set(voucher);
            return VoucherResponse.of(voucher, NOW);
        });
        when(vouchers.findById(9L)).thenAnswer(i -> Optional.ofNullable(issued.get()));
        nonces.add(NONCE);
        issuer = issuer(vault);
    }

    private StaffLoanVoucherIssuer issuer(VoucherCodeVault withVault) {
        return new StaffLoanVoucherIssuer(issuances, loans, vouchers, voucherService, withVault,
                new GetMoreCoupons(store, binding, properties, market), properties,
                new StaffLoanPolicy(new StaffLoanProperties(), new VoucherProperties()), authService, audit, market,
                new GetMoreFakes.Transactions(events), registry, () -> nonces.isEmpty() ? "ZZZZZZZZ" : nonces.pop());
    }

    private void stubIssuances() {
        when(issuances.save(any())).thenAnswer(i -> {
            StaffLoanVoucherIssuance row = i.getArgument(0);
            if (row.getId() == null) {
                ReflectionTestUtils.setField(row, "id", 101L + rows.size());
                rows.add(row);
            }
            events.add("save:" + row.getStatus() + ":" + row.getGenerateCalls());
            return row;
        });
        when(issuances.findById(anyLong())).thenAnswer(i -> row(i.getArgument(0)));
        when(issuances.lockById(anyLong())).thenAnswer(i -> row(i.getArgument(0)));
        when(issuances.findByStaffLoanId(anyLong())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getStaffLoanId().equals(i.getArgument(0))).findFirst());
        when(issuances.lockByStaffLoanId(anyLong())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getStaffLoanId().equals(i.getArgument(0))).findFirst());
        when(issuances.existsByStoreModeAndCouponPrefix(any(), anyString())).thenAnswer(i -> takenPrefixes.contains(
                i.<String>getArgument(1)) || rows.stream().anyMatch(r -> r.getCouponPrefix().equals(i.getArgument(1))));
        when(issuances.findForMember(anyLong(), any(), anyInt())).thenAnswer(i -> rows.stream()
                .filter(r -> i.<Collection<?>>getArgument(1).contains(r.getStatus())).toList());
        when(issuances.findByStatusInOrderByIdDesc(any(), any())).thenAnswer(i -> new PageImpl<>(rows.stream()
                .filter(r -> i.<Collection<?>>getArgument(0).contains(r.getStatus())).toList()));
    }

    private Optional<StaffLoanVoucherIssuance> row(Long id) {
        return rows.stream().filter(r -> r.getId().equals(id)).findFirst();
    }

    private static StaffLoan loan(LocalDate due) {
        Merchant getMore = Merchant.builder().merchantCode("getmore-groceries").companyName("GetMore Groceries")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).staffLoanMerchant(true)
                .voucherStore(VoucherStore.GETMORE).build();
        getMore.setId(3L);
        return StaffLoan.builder().id(164L).reference("SGL-2026-000164").staffMemberId(9L).offerId(64L)
                .employeeNumber("E1064").fullName("Rutendo Chari").msisdn("263771640064").grade("C4")
                .merchant(getMore).amount(new BigDecimal("150.00")).currency("USD").interestRate(BigDecimal.ZERO)
                .totalRepayable(new BigDecimal("150.00")).dueDate(due)
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
                .status(StaffLoanStatus.AWAITING_DISBURSEMENT).acceptedAt(NOW).version(0L).build();
    }

    private Voucher voucherOf(GetMoreVoucherCommand command) {
        return Voucher.builder().id(9L).product(VoucherProduct.STAFF_GROCERY_LOAN).merchant(loan.getMerchant())
                .disbursementReference(VoucherService.getMoreDisbursementReference(command.storeMode(),
                        command.couponId()))
                .loanAccount(command.loanAccount()).staffMemberId(command.staffMemberId())
                .customerReference(command.customerReference()).customerName(command.customerName())
                .customerMsisdn("+" + command.customerMsisdn()).codeHmac(vault.fingerprint(CODE))
                .codeCiphertext(vault.encrypt(command.codeAsStored()))
                .codeLast4(GetMoreCodes.lastFour(command.codeAsStored()))
                .codeLength(command.codeAsStored().length()).faceValue(command.faceValue())
                .redeemedAmount(new BigDecimal("0.00")).currency(command.currency()).issuedAt(NOW.plusSeconds(2))
                .expiresAt(LocalDateTime.of(2026, 11, 13, 21, 59, 59, 999_999_000)).status(VoucherStatus.ISSUED)
                .deliveryStatus(VoucherDeliveryStatus.PENDING).deliveryUpdatedAt(NOW).version(0L)
                .codeSource(VoucherCodeSource.GETMORE).storeMode(command.storeMode())
                .staffLoanId(command.staffLoanId()).externalCouponId(command.couponId())
                .externalRuleId(command.ruleId()).codePrefix(command.codePrefix()).build();
    }

    /** What the acceptance queues, in its own transaction. */
    private StaffLoanVoucherIssuance queued() {
        return issuer.queue(loan).orElseThrow();
    }

    private StaffLoanVoucherIssuance issuance() {
        return rows.getFirst();
    }

    private Outcome attempt(Trigger trigger) {
        return issuer.attempt("SGL-2026-000164", trigger, Deadline.none());
    }

    private double counted(String outcome) {
        return registry.get(StaffLoanVoucherIssuer.METRIC).tag("outcome", outcome).counter().count();
    }

    private List<AuditLog> audits() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit, atLeastOnce()).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    // ---- queued with the acceptance ----------------------------------------------------------------------------

    @Test
    @DisplayName("queued with the acceptance: PENDING and due now, under the simulator's prefix and a fresh nonce")
    void queue() {
        StaffLoanVoucherIssuance queued = queued();

        assertThat(queued.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.PENDING);
        assertThat(queued.getStoreMode()).isEqualTo(GetMoreMode.SIMULATED);
        assertThat(queued.getCouponPrefix()).isEqualTo(PREFIX);
        assertThat(queued.getRuleId()).isEqualTo(43L);
        assertThat(queued.getFaceValue()).isEqualByComparingTo("150.00");
        assertThat(queued.getCurrency()).isEqualTo("USD");
        assertThat(queued.getGenerateAllowance()).isEqualTo(1);
        assertThat(queued.getGenerateCalls()).isZero();
        assertThat(queued.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(queued.getMerchantId()).isEqualTo(3L);
        assertThat(events).as("nothing is called at GetMore").doesNotContain("search", "generate");
        assertThat(issuer.queue(loan)).as("once per loan").contains(queued);
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("a taken prefix draws another nonce; six taken in a row is an error, never a shared prefix")
    void queueRedrawsTheNonce() {
        nonces.clear();
        nonces.addAll(List.of("AAAAAAAA", "BBBBBBBB", "CCCCCCCC"));
        takenPrefixes.addAll(List.of("SIM-AAAAAAAA-", "SIM-BBBBBBBB-"));
        assertThat(queued().getCouponPrefix()).isEqualTo("SIM-CCCCCCCC-");

        rows.clear();
        nonces.clear();
        nonces.addAll(List.of("D1", "D2", "D3", "D4", "D5", "D6", "D7"));
        nonces.forEach(nonce -> takenPrefixes.add("SIM-" + nonce + "-"));
        assertThatThrownBy(this::queued).isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not draw an unused GetMore coupon prefix in 6 draws");
        assertThat(nonces).as("the seventh is never drawn").containsExactly("D7");
    }

    @Test
    @DisplayName("nothing is queued while GetMore is OFF, nor for a merchant whose vouchers are loans' own")
    void queueOnlyForGetMore() {
        properties.setMode(GetMoreMode.OFF);
        assertThat(issuer.queue(loan)).isEmpty();

        properties.setMode(GetMoreMode.LIVE);
        loan.getMerchant().setVoucherStore(VoucherStore.LOANS);
        assertThat(issuer.queue(loan)).isEmpty();
        verify(issuances, never()).save(any());
    }

    // ---- an attempt ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("issued: the coupon is kept as the voucher, the loan DISBURSED as it, the issuance ISSUED")
    void issued() {
        queued();

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.ISSUED);

        ArgumentCaptor<GetMoreVoucherCommand> command = ArgumentCaptor.forClass(GetMoreVoucherCommand.class);
        verify(voucherService).issueFromGetMore(command.capture());
        assertThat(command.getValue().codeAsStored()).as("the code exactly as GetMore made it").isEqualTo(CODE);
        assertThat(command.getValue().codePrefix()).isEqualTo(PREFIX);
        assertThat(command.getValue().couponId()).isEqualTo(1207L);
        assertThat(command.getValue().ruleId()).isEqualTo(43L);
        assertThat(command.getValue().storeMode()).isEqualTo(GetMoreMode.SIMULATED);
        assertThat(command.getValue().customerMsisdn()).isEqualTo("263771640064");
        assertThat(command.getValue().faceValue()).isEqualByComparingTo("150.00");

        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(loan.getDisbursementReference()).isEqualTo("GETMORE-SIM-1207");
        assertThat(loan.getDisbursedAt()).as("the voucher's issue time").isEqualTo(NOW.plusSeconds(2));
        assertThat(issuance().getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.ISSUED);
        assertThat(issuance().getVoucherId()).isEqualTo(9L);
        assertThat(issuance().getClaimedAt()).isNull();
        assertThat(issuance().getFirstClaimedAt()).isEqualTo(NOW);
        assertThat(issuance().getAttempts()).isEqualTo(1);
        assertThat(issuance().getGenerateCalls()).isEqualTo(1);
        assertThat(issuance().getLastOutcome()).isEqualTo("ISSUED");
        assertThat(store.generateCalls).isEqualTo(1);

        assertThat(store.updates).as("the coupon's expiry is told to GetMore, best effort").singleElement()
                .satisfies(update -> assertThat(update.expirationDateUtc())
                        .isEqualTo(LocalDateTime.of(2026, 11, 13, 21, 59, 59, 999_999_000)));
        verify(voucherService).markCouponExpirySet(eq(9L), any());
        AuditLog issuedAudit = audits().stream().filter(a -> a.getEventType().equals(
                StaffLoanVoucherIssuer.ISSUED_EVENT)).findFirst().orElseThrow();
        assertThat(issuedAudit.getEntityId()).isEqualTo("164");
        assertThat(issuedAudit.getDetail()).contains("coupon:1207", "code:****8B1C", "adopted:false")
                .doesNotContain(CODE);
        assertThat(counted("issued")).isEqualTo(1);
    }

    @Test
    @DisplayName("the generate is counted and committed BEFORE it is sent, and nothing is held across GetMore")
    void generateCountedBeforeTheCall() {
        queued();
        events.clear();
        store.onGenerate = () -> assertThat(issuance().getGenerateCalls()).as("counted already").isEqualTo(1);

        attempt(Trigger.ACCEPTANCE);

        int counted = events.indexOf("save:CALLING:1");
        assertThat(events.subList(counted, counted + 3)).containsExactly("save:CALLING:1", "commit", "generate");
        int firstCall = events.indexOf("rule");
        assertThat(events.subList(firstCall - 3, firstCall)).as("the claim committed before the first call")
                .containsExactly("begin", "save:CALLING:0", "commit");
    }

    @Test
    @DisplayName("GetMore unavailable: PENDING again with back-off, nothing generated, the claim released")
    void unavailable() {
        queued();
        store.searchFailure = new GetMoreUnavailableException(Operation.SEARCH_COUPONS,
                GetMoreUnavailableException.Reason.NOT_REACHED);

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.PENDING);

        assertThat(issuance().getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.PENDING);
        assertThat(issuance().getLastOutcome()).isEqualTo("UNAVAILABLE_NOT_REACHED");
        assertThat(issuance().getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(issuance().getClaimedAt()).isNull();
        assertThat(issuance().getGenerateCalls()).isZero();
        assertThat(store.generateCalls).isZero();
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(counted("retry")).isEqualTo(1);

        assertThat(attempt(Trigger.BORROWER_READ)).as("a read waits for the back-off").isEqualTo(Outcome.NOT_DUE);
        clock.advance(Duration.ofSeconds(30));
        store.searchFailure = null;
        assertThat(attempt(Trigger.BORROWER_READ)).isEqualTo(Outcome.ISSUED);
        assertThat(issuance().getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("the answer to the generate is lost: PENDING; the next attempt finds the coupon and adopts it")
    void lostResponseThenAdopt() {
        queued();
        store.generateBehaviour = spec -> {
            store.mint(spec);
            throw new GetMoreOutcomeUnknownException(Operation.GENERATE, "read timed out");
        };

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.PENDING);
        assertThat(issuance().getLastOutcome()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(issuance().getGenerateCalls()).as("it may have reached GetMore: kept").isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        assertThat(attempt(Trigger.BORROWER_READ)).isEqualTo(Outcome.ISSUED);

        assertThat(store.generateCalls).as("generated once in all").isEqualTo(1);
        assertThat(store.on(PREFIX)).singleElement().satisfies(c -> assertThat(c.code()).isEqualTo(CODE));
        assertThat(issuance().getGenerateCalls()).isEqualTo(1);
        assertThat(counted("adopted")).isEqualTo(1);
    }

    @Test
    @DisplayName("a mint that never shows up: the next attempt does not generate again, it parks GENERATED_NOT_FOUND")
    void lostMintParks() {
        queued();
        store.generateBehaviour = spec -> {
            throw new GetMoreOutcomeUnknownException(Operation.GENERATE, "read timed out");
        };
        attempt(Trigger.ACCEPTANCE);
        clock.advance(Duration.ofMinutes(1));

        assertThat(attempt(Trigger.BORROWER_READ)).isEqualTo(Outcome.PARKED);

        assertThat(store.generateCalls).isEqualTo(1);
        assertThat(issuance().getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.PARKED);
        assertThat(issuance().getLastOutcome()).isEqualTo(FindOrCreate.GENERATED_NOT_FOUND);
        assertThat(audits()).extracting(AuditLog::getEventType).contains(StaffLoanVoucherIssuer.PARKED_EVENT);
        assertThat(attempt(Trigger.BORROWER_READ)).as("a read never moves a parked one").isEqualTo(Outcome.PARKED);
    }

    @Test
    @DisplayName("codes GetMore returned that no search finds are kept sealed, never in plain text")
    void mintedCodesSealed() {
        queued();
        store.generateBehaviour = spec -> List.of(CODE);   // answered, but not on the store's search

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.PENDING);

        StaffLoanVoucherIssuance row = issuance();
        assertThat(row.getLastOutcome()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(row.getMintedCodesCiphertext()).isNotBlank().doesNotContain(CODE).doesNotContain("8B1C");
        assertThat(row.mintedCodeCount()).isEqualTo(1);
        assertThat(row.sealedCodes().stream().map(vault::decrypt)).containsExactly(CODE);
        assertThat(row.getLastError()).doesNotContain(CODE);
        assertThat(row.toString()).doesNotContain(CODE).doesNotContain(row.getMintedCodesCiphertext());
    }

    @Test
    @DisplayName("refused by the amount binding before anything is minted: PARKED with the reason")
    void rejectedParks() {
        queued();
        doThrow(new GetMoreRejectedException(null, GetMoreRejectedException.Reason.AMOUNT_MODEL_UNSET,
                "GETMORE_LIVE_AMOUNT_MODEL is NONE")).when(binding).precheck(any(), any(), any(), any());

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.PARKED);

        assertThat(issuance().getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.PARKED);
        assertThat(issuance().getLastOutcome()).isEqualTo("AMOUNT_MODEL_UNSET");
        assertThat(issuance().getLastError()).contains("GETMORE_LIVE_AMOUNT_MODEL is NONE");
        assertThat(store.generateCalls).isZero();
        assertThat(counted("parked")).isEqualTo(1);
    }

    @Test
    @DisplayName("a coupon on the prefix loans did not make (another rule) is never adopted: PARKED FOREIGN_COUPON")
    void foreignCouponParks() {
        queued();
        store.plant(PREFIX + "PLANTED000000001", 99, 0, NOW);

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.PARKED);

        assertThat(issuance().getLastOutcome()).isEqualTo(FindOrCreate.FOREIGN_COUPON);
        assertThat(store.generateCalls).isZero();
        verify(voucherService, never()).issueFromGetMore(any());
    }

    @Test
    @DisplayName("keeping the voucher fails: rolled back, PENDING as ERROR; the coupon is left for the next attempt")
    void settleFailureRetries() {
        queued();
        doThrow(new IllegalStateException("database went away")).when(voucherService).issueFromGetMore(any());

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.PENDING);

        assertThat(events).contains("rollback");
        assertThat(issuance().getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.PENDING);
        assertThat(issuance().getLastOutcome()).isEqualTo("ERROR");
        assertThat(store.on(PREFIX)).as("bound and still there, adopted next time").hasSize(1);
        assertThat(store.deleted).isEmpty();
    }

    @Test
    @DisplayName("back-off: retry-after doubled per attempt, the doublings capped first, at most 15 minutes")
    void backOff() {
        assertThat(issuer.backOff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(issuer.backOff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(issuer.backOff(5)).isEqualTo(Duration.ofSeconds(480));
        assertThat(issuer.backOff(6)).isEqualTo(Duration.ofMinutes(15));
        assertThat(issuer.backOff(64)).isEqualTo(Duration.ofMinutes(15));
        assertThat(issuer.backOff(100)).isEqualTo(Duration.ofMinutes(15));
        assertThat(issuer.backOff(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(15));

        properties.getIssuance().setRetryAfter(Duration.ofSeconds(1));
        assertThat(issuer.backOff(64)).as("never overflows: 2^5 at most").isEqualTo(Duration.ofSeconds(32));
        assertThat(issuer.backOff(100)).isEqualTo(Duration.ofSeconds(32));
    }

    @Test
    @DisplayName("a claim younger than stale-claim is left to its attempt; an older one is taken over")
    void freshClaimLeftAloneStaleOneReclaimed() {
        queued();
        issuance().claim(NOW.minusMinutes(1));

        assertThat(attempt(Trigger.OPERATOR)).isEqualTo(Outcome.IN_PROGRESS);
        assertThat(events).doesNotContain("search", "generate");

        issuance().claim(NOW.minusMinutes(6));
        assertThat(attempt(Trigger.BORROWER_READ)).isEqualTo(Outcome.ISSUED);
        assertThat(issuance().getAttempts()).isEqualTo(3);
        assertThat(issuance().getFirstClaimedAt()).as("the adoption floor stays the first claim")
                .isEqualTo(NOW.minusMinutes(1));
    }

    @Test
    @DisplayName("the claim is taken over while the coupon is made: nothing kept, and the coupon it bound withdrawn")
    void lostClaimWithdrawsItsCoupon() {
        queued();
        store.onGenerate = () -> issuance().claim(NOW.plusSeconds(1));   // another attempt took over

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.CLAIM_LOST);

        verify(voucherService, never()).issueFromGetMore(any());
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(store.deleted).containsExactly(CODE);
        assertThat(counted("claim_lost")).isEqualTo(1);
    }

    @Test
    @DisplayName("lost claim and the withdrawal fails: the coupon is kept sealed; an abandoned issuance cancels again")
    void lostClaimWithdrawalFails() {
        queued();
        store.onGenerate = () -> {
            issuance().cancelling(NOW);
            issuance().abandoned(NOW);
        };
        store.failDeleting = true;

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.CLAIM_LOST);

        assertThat(issuance().getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.CANCELLING);
        assertThat(issuance().sealedCodes().stream().map(vault::decrypt)).containsExactly(CODE);
    }

    @Test
    @DisplayName("the loan is cancelled while its coupon is made: CANCELLING, the coupon withdrawn, ABANDONED later")
    void loanCancelledDuringTheCall() {
        queued();
        store.onGenerate = () -> loan.cancel(NOW, "hc-approver", "Employment status changed to RESIGNED");

        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.CANCELLING);

        verify(voucherService, never()).issueFromGetMore(any());
        assertThat(store.deleted).containsExactly(CODE);
        assertThat(issuance().getStatus()).as("a claim could still be minting for stale-claim")
                .isEqualTo(StaffLoanVoucherIssuanceStatus.CANCELLING);
        assertThat(issuance().getLastOutcome()).isEqualTo(StaffLoanVoucherIssuer.WAITING_FOR_CLAIM);
        assertThat(counted("cancelled")).isEqualTo(1);

        clock.advance(Duration.ofMinutes(5));
        assertThat(issuer.cleanup(issuance().getId())).isEqualTo(Cleanup.WITHDRAWN);
        assertThat(issuance().getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.ABANDONED);
        assertThat(issuance().getLastOutcome()).isEqualTo("WITHDRAWN");
        assertThat(audits()).extracting(AuditLog::getEventType).contains(StaffLoanVoucherIssuer.ABANDONED_EVENT);
    }

    @Test
    @DisplayName("checked before any call: OFF stays PENDING; another mode, no voucher keys, or past due parks")
    void parkedBeforeAnyCall() {
        queued();
        properties.setMode(GetMoreMode.OFF);
        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.PENDING);
        assertThat(issuance().getLastOutcome()).isEqualTo(StaffLoanVoucherIssuer.GETMORE_OFF);

        properties.setMode(GetMoreMode.LIVE);
        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.PARKED);
        assertThat(issuance().getLastOutcome()).isEqualTo(StaffLoanVoucherIssuer.MODE_MISMATCH);

        properties.setMode(GetMoreMode.SIMULATED);
        issuer = issuer(GetMoreFakes.vaultOff());
        assertThat(issuer.attempt("SGL-2026-000164", Trigger.OPERATOR, Deadline.none())).isEqualTo(Outcome.PARKED);
        assertThat(issuance().getLastOutcome()).isEqualTo(StaffLoanVoucherIssuer.VOUCHERS_UNAVAILABLE);

        issuer = issuer(vault);
        loan = loan(LocalDate.of(2026, 10, 14));   // payday is today: 00:00 in Harare has passed
        assertThat(attempt(Trigger.OPERATOR)).isEqualTo(Outcome.PARKED);
        assertThat(issuance().getLastOutcome()).isEqualTo(StaffLoanVoucherIssuer.PAST_DUE);

        assertThat(events).as("GetMore was never asked").doesNotContain("rule", "search", "generate");
        assertThat(issuance().getAttempts()).isZero();
    }

    @Test
    @DisplayName("the inline budget is checked between calls: past it, nothing more is sent and it waits")
    void inlineDeadline() {
        queued();
        store.onSearch = () -> clock.advance(Duration.ofSeconds(11));

        assertThat(issuer.attempt("SGL-2026-000164", Trigger.ACCEPTANCE,
                Deadline.after(clock, Duration.ofSeconds(10)))).isEqualTo(Outcome.PENDING);

        assertThat(issuance().getLastOutcome()).isEqualTo("UNAVAILABLE_DEADLINE");
        assertThat(store.generateCalls).isZero();
        assertThat(issuance().getGenerateCalls()).isZero();
    }

    @Test
    @DisplayName("no issuance (a loans voucher, or accepted while OFF): nothing to do; ISSUED is final")
    void noIssuanceAndFinal() {
        assertThat(attempt(Trigger.ACCEPTANCE)).isEqualTo(Outcome.NONE);
        assertThat(issuer.attempt("SGL-2026-999999", Trigger.ACCEPTANCE, Deadline.none())).isEqualTo(Outcome.NONE);

        queued();
        attempt(Trigger.ACCEPTANCE);
        assertThat(attempt(Trigger.OPERATOR)).isEqualTo(Outcome.CLOSED);
        assertThat(store.generateCalls).isEqualTo(1);
    }

    // ---- cleanup --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("cleanup of an issuance never claimed: ABANDONED without asking GetMore")
    void cleanupNeverClaimed() {
        queued().cancelling(NOW);

        assertThat(issuer.cleanup(issuance().getId())).isEqualTo(Cleanup.WITHDRAWN);

        assertThat(issuance().getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.ABANDONED);
        assertThat(events).doesNotContain("search", "delete");
        assertThat(issuer.cleanup(issuance().getId())).isEqualTo(Cleanup.NOT_CANCELLING);
    }

    @Test
    @DisplayName("cleanup withdraws every unused coupon on the prefix and every kept code; a used one parks")
    void cleanupWithdrawsAndParksAUsedCoupon() {
        StaffLoanVoucherIssuance row = queued();
        row.claim(NOW.minusMinutes(10));
        row.keepSealedCodes(List.of(vault.encrypt(PREFIX + "ORPHAN0000000001")), NOW);
        row.cancelling(NOW);
        store.plant(PREFIX + "UNUSED0000000001", 43, 0, NOW);
        store.plant(PREFIX + "UNUSED0000000002", 43, 0, NOW);

        assertThat(issuer.cleanup(row.getId())).isEqualTo(Cleanup.WITHDRAWN);
        assertThat(store.deleted).containsExactlyInAnyOrder(PREFIX + "UNUSED0000000001", PREFIX + "UNUSED0000000002");
        assertThat(row.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.ABANDONED);
        assertThat(row.getMintedCodesCiphertext()).isNull();

        rows.clear();
        StaffLoanVoucherIssuance second = queued();
        second.claim(NOW.minusMinutes(10));
        second.cancelling(NOW);
        store.plant(second.getCouponPrefix() + "UNUSED0000000003", 43, 0, NOW);
        store.plant(second.getCouponPrefix() + "SPENT00000000004", 43, 1, NOW);

        assertThat(issuer.cleanup(second.getId())).isEqualTo(Cleanup.COUPON_USED);
        assertThat(second.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.PARKED);
        assertThat(second.getLastOutcome()).isEqualTo(StaffLoanVoucherIssuer.COUPON_USED_AFTER_CANCEL);
        assertThat(second.getLastError()).contains("****0004").doesNotContain("SPENT00000000004");
        assertThat(store.deleted).contains(second.getCouponPrefix() + "UNUSED0000000003");
    }

    @Test
    @DisplayName("cleanup that cannot withdraw: stays CANCELLING, with why, to be tried again")
    void cleanupFailureStaysCancelling() {
        StaffLoanVoucherIssuance row = queued();
        row.claim(NOW.minusMinutes(10));
        row.cancelling(NOW);
        store.plant(PREFIX + "UNUSED0000000001", 43, 0, NOW);
        store.failDeleting = true;

        assertThat(issuer.cleanup(row.getId())).isEqualTo(Cleanup.FAILED);
        assertThat(row.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.CANCELLING);
        assertThat(row.getLastOutcome()).isEqualTo("WITHDRAW_FAILED");

        store.failDeleting = false;
        store.searchFailure = new GetMoreUnavailableException(Operation.SEARCH_COUPONS,
                GetMoreUnavailableException.Reason.READ_FAILED);
        assertThat(issuer.cleanup(row.getId())).isEqualTo(Cleanup.FAILED);
        assertThat(row.getLastOutcome()).isEqualTo("CLEANUP_FAILED");

        store.searchFailure = null;
        assertThat(issuer.cleanup(row.getId())).isEqualTo(Cleanup.WITHDRAWN);
    }

    // ---- a borrower's read, and Credit ---------------------------------------------------------------------------

    @Test
    @DisplayName("a borrower's read moves on a due issuance and cleans up a cancelling one, never a not-due one")
    void refreshFor() {
        StaffLoanVoucherIssuance row = queued();
        row.retryAt(NOW.plusMinutes(1), "UNAVAILABLE_NOT_REACHED", null, NOW);

        issuer.refreshFor(9L);
        assertThat(events).doesNotContain("search");

        clock.advance(Duration.ofMinutes(1));
        issuer.refreshFor(9L);
        assertThat(row.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.ISSUED);

        rows.clear();
        StaffLoanVoucherIssuance cancelling = queued();
        cancelling.cancelling(NOW);
        issuer.refreshFor(9L);
        assertThat(cancelling.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.ABANDONED);
    }

    @Test
    @DisplayName("Credit's retry: a parked one goes PENDING and is tried; generateAgain only after a lost mint")
    void operatorRetry() {
        queued();
        store.generateBehaviour = spec -> {
            throw new GetMoreOutcomeUnknownException(Operation.GENERATE, "read timed out");
        };
        attempt(Trigger.ACCEPTANCE);
        clock.advance(Duration.ofMinutes(1));
        attempt(Trigger.BORROWER_READ);
        assertThat(issuance().getLastOutcome()).isEqualTo(FindOrCreate.GENERATED_NOT_FOUND);
        when(authService.getLoggedInUsername()).thenReturn("credit1");

        StaffLoanVoucherIssuanceResult searchedOnly = issuer.retry(164L, "GetMore restored", false);
        assertThat(searchedOnly.issuance().status()).as("without generateAgain it only searches")
                .isEqualTo(StaffLoanVoucherIssuanceStatus.PARKED);
        assertThat(store.generateCalls).isEqualTo(1);

        store.generateBehaviour = store::mint;
        StaffLoanVoucherIssuanceResult again = issuer.retry(164L, "GetMore confirms no coupon was made", true);

        assertThat(again.issuance().status()).isEqualTo(StaffLoanVoucherIssuanceStatus.ISSUED);
        assertThat(again.issuance().generateAllowance()).isEqualTo(2);
        assertThat(again.issuance().loanReference()).isEqualTo("SGL-2026-000164");
        assertThat(again.loan().status()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(store.generateCalls).isEqualTo(2);
        assertThat(audits()).filteredOn(a -> a.getActorId().equals("credit1")).extracting(AuditLog::getEventType)
                .contains(StaffLoanVoucherIssuer.RETRIED_EVENT, StaffLoanVoucherIssuer.GENERATE_ALLOWED_EVENT);
    }

    @Test
    @DisplayName("Credit's retry refuses: OFF, a loan not awaiting, past due, generateAgain elsewhere, a live call")
    void operatorRetryRefusals() {
        properties.setMode(GetMoreMode.OFF);
        assertThatThrownBy(() -> issuer.retry(164L, "why", false)).isInstanceOf(GetMoreUnavailableException.class)
                .satisfies(e -> assertThat(((GetMoreUnavailableException) e).reason())
                        .isEqualTo(GetMoreUnavailableException.Reason.OFF));
        properties.setMode(GetMoreMode.SIMULATED);

        StaffLoanVoucherIssuance row = queued();
        row.park(StaffLoanVoucherIssuer.PAST_DUE, "payday began", NOW);
        assertThatThrownBy(() -> issuer.retry(164L, "why", false)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("cancel the loan");

        row.park(FindOrCreate.FOREIGN_COUPON, "planted", NOW);
        assertThatThrownBy(() -> issuer.retry(164L, "why", true)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("generateAgain is accepted only after");

        row.claim(NOW.minusSeconds(5));
        assertThatThrownBy(() -> issuer.retry(164L, "why", false)).isInstanceOf(IssuanceInProgressException.class)
                .hasMessage("A voucher for SGL-2026-000164 is being issued right now; try again in a minute.");

        loan.cancel(NOW, "credit1", "error");
        row.cancelling(NOW);
        row.abandoned(NOW);
        assertThatThrownBy(() -> issuer.retry(164L, "why", false)).isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000164 is CANCELLED; only a loan awaiting its voucher can be issued"
                        + " one");
    }

    @Test
    @DisplayName("Credit's retry of a loan accepted while GetMore was OFF makes its issuance, then tries it")
    void operatorRetryQueuesALoanAcceptedWhileOff() {
        StaffLoanVoucherIssuanceResult result = issuer.retry(164L, "accepted while GetMore was off", false);

        assertThat(result.issuance().status()).isEqualTo(StaffLoanVoucherIssuanceStatus.ISSUED);
        assertThat(audits()).extracting(AuditLog::getEventType).contains(StaffLoanVoucherIssuer.QUEUED_EVENT);

        loan = loan(LocalDate.of(2026, 11, 20));
        rows.clear();
        loan.getMerchant().setVoucherStore(VoucherStore.LOANS);
        assertThatThrownBy(() -> issuer.retry(164L, "why", false)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("is not a GetMore store");
    }

    @Test
    @DisplayName("the issuance list names each loan and counts kept codes, never showing one")
    void issuances() {
        StaffLoanVoucherIssuance row = queued();
        row.keepSealedCodes(List.of(vault.encrypt(CODE)), NOW);

        assertThat(issuer.issuances(null, org.springframework.data.domain.PageRequest.of(0, 20)).getContent())
                .singleElement().satisfies(response -> {
                    assertThat(response.loanReference()).isEqualTo("SGL-2026-000164");
                    assertThat(response.mintedCodes()).isEqualTo(1);
                    assertThat(response.toString()).doesNotContain(CODE);
                });
    }
}

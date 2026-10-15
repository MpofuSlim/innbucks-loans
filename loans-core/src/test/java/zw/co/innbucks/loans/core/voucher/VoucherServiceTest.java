package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Issuing a voucher (FR-SGL-033, 035) and what staff can do with one: reveal its code on the record (FR-SGL-040),
 * cancel it before it is spent, and send it again to the same number (FR-SGL-037). And a GetMore coupon kept as a
 * voucher: its code fingerprinted normalised and sealed as GetMore returned it, shown verbatim, cancelled only with its
 * loan and only when nothing was ever spent from it.
 */
class VoucherServiceTest {

    private static final IssueVoucherCommand COMMAND = new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN,
            " BRNET-20261001-0007 ", "SGL-2026-000143", 3L, 12L, "E1012", "Chipo Banda", "0772123123",
            new BigDecimal("300"), "USD");

    private final VoucherCodeVault vault = VoucherCodeVaultTest.vault();
    private VoucherRepository vouchers;
    private MerchantRepository merchants;
    private VoucherDeliveryDispatcher dispatcher;
    private AuditService audit;
    private VoucherRedemptionRepository redemptions;
    private VoucherRefundRepository refunds;
    private VoucherSyncIssueRepository issues;
    private GetMoreAmountBinding binding;
    private VoucherService service;

    @BeforeEach
    void setUp() {
        vouchers = mock(VoucherRepository.class);
        when(vouchers.saveAndFlush(any())).thenAnswer(i -> {
            Voucher voucher = i.getArgument(0);
            ReflectionTestUtils.setField(voucher, "id", 7L);
            return voucher;
        });
        when(vouchers.save(any())).thenAnswer(i -> i.getArgument(0));
        merchants = mock(MerchantRepository.class);
        when(merchants.findById(3L)).thenAnswer(i -> Optional.of(TestVouchers.getMore()));
        dispatcher = mock(VoucherDeliveryDispatcher.class);
        audit = mock(AuditService.class);
        redemptions = mock(VoucherRedemptionRepository.class);
        refunds = mock(VoucherRefundRepository.class);
        issues = mock(VoucherSyncIssueRepository.class);
        binding = mock(GetMoreAmountBinding.class);
        when(binding.partialSpending()).thenReturn(true);
    }

    private VoucherService withClock(LocalDateTime utc) {
        AuthService auth = mock(AuthService.class);
        when(auth.getLoggedInUsername()).thenReturn("support1");
        return new VoucherService(vouchers, merchants, redemptions, refunds, issues,
                mock(VoucherDeliveryRepository.class), vault, new VoucherCodeGenerator(new Random(7)), dispatcher,
                new VoucherProperties(), auth, audit,
                new MarketTimeZone("ZW", Clock.fixed(utc.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)), binding);
    }

    @Test
    @DisplayName("issuing stores the code only fingerprinted, sealed and as its last four, good to the end of day 30")
    void issue() {
        service = withClock(TestVouchers.ISSUED_AT);

        VoucherResponse response = service.issue(COMMAND);

        ArgumentCaptor<Voucher> saved = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers).saveAndFlush(saved.capture());
        Voucher voucher = saved.getValue();
        String code = vault.decrypt(voucher.getCodeCiphertext());
        assertThat(code).hasSize(16).matches("[1-9][0-9]{15}");
        assertThat(VoucherCodes.normalize(code)).contains(code);
        assertThat(voucher.getCodeHmac()).isEqualTo(vault.fingerprint(code));
        assertThat(voucher.getCodeLast4()).isEqualTo(code.substring(12));
        assertThat(voucher.getCodeLength()).isEqualTo(16);
        assertThat(voucher.getDisbursementReference()).isEqualTo("BRNET-20261001-0007");
        assertThat(voucher.getMerchant().getMerchantCode()).isEqualTo("getmore-groceries");
        assertThat(voucher.getCustomerMsisdn()).isEqualTo("+263772123123");
        assertThat(voucher.getFaceValue()).isEqualTo(new BigDecimal("300.00"));
        assertThat(voucher.getRedeemedAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.ISSUED);
        assertThat(voucher.getDeliveryStatus()).isEqualTo(VoucherDeliveryStatus.PENDING);
        assertThat(voucher.getIssuedAt()).isEqualTo(TestVouchers.ISSUED_AT);
        assertThat(voucher.getExpiresAt()).isEqualTo(TestVouchers.EXPIRES_AT);
        assertThat(response.maskedCode()).isEqualTo("**** **** **** " + code.substring(12));
        assertThat(response.customerMsisdn()).isEqualTo("****3123");
        assertThat(response.merchantCode()).isEqualTo("getmore-groceries");
        assertThat(response.merchantName()).isEqualTo("GetMore Groceries");
        verify(dispatcher).afterCommit(7L, "system");
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit).record(audited.capture());
        AuditLog row = audited.getValue().build();
        assertThat(row.getEventType()).isEqualTo("VOUCHER_ISSUED");
        assertThat(row.getDetail()).doesNotContain(code).contains("merchant:getmore-groceries")
                .contains("faceValue:300.00 USD");
    }

    @Test
    @DisplayName("issued again for the same disbursement, the first voucher is returned and nothing is sent")
    void issueIsOncePerDisbursement() {
        service = withClock(TestVouchers.ISSUED_AT);
        Voucher earlier = TestVouchers.issued(vault).build();
        when(vouchers.findByDisbursementReference("BRNET-20261001-0007")).thenReturn(Optional.of(earlier));

        assertThat(service.issue(COMMAND).id()).isEqualTo(7L);

        verify(vouchers, never()).saveAndFlush(any());
        verifyNoInteractions(dispatcher, audit);
    }

    @Test
    @DisplayName("the same disbursement for a different loan, merchant or amount is a 409, not a second voucher")
    void sameDisbursementDifferentPayout() {
        service = withClock(TestVouchers.ISSUED_AT);
        when(vouchers.findByDisbursementReference("BRNET-20261001-0007"))
                .thenReturn(Optional.of(TestVouchers.issued(vault).faceValue(new BigDecimal("250.00")).build()));

        assertThatThrownBy(() -> service.issue(COMMAND)).isInstanceOf(ConflictException.class)
                .hasMessage("Disbursement BRNET-20261001-0007 already has voucher 7, for a different loan, merchant"
                        + " or amount");

        when(vouchers.findByDisbursementReference("BRNET-20261001-0007")).thenReturn(Optional.of(TestVouchers
                .issued(vault).merchant(TestVouchers.merchant(9L, "pick-n-pay", "Pick n Pay")).build()));

        assertThatThrownBy(() -> service.issue(COMMAND)).isInstanceOf(ConflictException.class);
        verify(vouchers, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an unreachable number, a zero or fractional-cent amount, no merchant or an unknown one, or no keys:"
            + " nothing is issued")
    void issueRefusals() {
        service = withClock(TestVouchers.ISSUED_AT);
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                null, null, "E1", "Name", "0772123123", BigDecimal.TEN, "USD"))).isInstanceOf(ValidationException.class)
                .hasMessage("merchantId is required");
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                99L, null, "E1", "Name", "0772123123", BigDecimal.TEN, "USD"))).isInstanceOf(ValidationException.class)
                .hasMessage("merchantId 99 is not a merchant");
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                3L, null, "E1", "Name", "0242700000", BigDecimal.TEN, "USD"))).isInstanceOf(ValidationException.class)
                .hasMessageStartingWith("customerMsisdn");
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                3L, null, "E1", "Name", "0772123123", new BigDecimal("10.005"), "USD")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                3L, null, "E1", "Name", "0772123123", BigDecimal.ZERO, "USD"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                3L, null, "E1", "Name", "0772123123", BigDecimal.TEN, "usd"))).isInstanceOf(ValidationException.class);
        verify(vouchers, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("reveal: the code in full and as digits, audited with who and why, never logged")
    void reveal() {
        service = withClock(TestVouchers.ISSUED_AT.plusDays(2));
        when(vouchers.findById(7L)).thenReturn(Optional.of(TestVouchers.issued(vault).build()));

        VoucherCodeResponse code = service.reveal(7L, "Customer on the phone");

        assertThat(code.code()).isEqualTo("4829 1506 7331 8406");
        assertThat(code.scanValue()).isEqualTo("4829150673318406");
        assertThat(code.toString()).doesNotContain("4829");
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit).record(audited.capture());
        AuditLog row = audited.getValue().build();
        assertThat(row.getEventType()).isEqualTo("VOUCHER_CODE_REVEALED");
        assertThat(row.getActorId()).isEqualTo("support1");
        assertThat(row.getDetail()).isEqualTo("reason:Customer on the phone;status:ISSUED");
    }

    @Test
    @DisplayName("cancel: only an untouched, unexpired voucher, with the reason on the record")
    void cancel() {
        service = withClock(TestVouchers.ISSUED_AT.plusDays(3));
        Voucher voucher = TestVouchers.issued(vault).build();
        when(vouchers.lockById(7L)).thenReturn(Optional.of(voucher));

        VoucherResponse cancelled = service.cancel(7L, " Paid to the wrong member ");

        assertThat(cancelled.status()).isEqualTo(VoucherStatus.CANCELLED);
        assertThat(cancelled.cancelledBy()).isEqualTo("support1");
        assertThat(cancelled.cancellationReason()).isEqualTo("Paid to the wrong member");
        assertThatThrownBy(() -> service.cancel(7L, "again")).isInstanceOf(ConflictException.class)
                .hasMessage("Voucher 7 is already CANCELLED");

        when(vouchers.lockById(7L)).thenReturn(Optional.of(TestVouchers.issued(vault)
                .status(VoucherStatus.PARTIALLY_REDEEMED).redeemedAmount(new BigDecimal("1.00")).build()));
        assertThatThrownBy(() -> service.cancel(7L, "x")).hasMessage("Voucher 7 has been partly spent and cannot be"
                + " cancelled");

        when(vouchers.lockById(7L)).thenReturn(Optional.of(TestVouchers.issued(vault).build()));
        VoucherService later = withClock(TestVouchers.EXPIRES_AT.plusSeconds(1));
        assertThatThrownBy(() -> later.cancel(7L, "x")).hasMessage("Voucher 7 has expired and cannot be cancelled");
    }

    @Test
    @DisplayName("resend: re-queued to the same number when SENT or FAILED; refused while sending or once closed")
    void resend() {
        service = withClock(TestVouchers.ISSUED_AT.plusDays(1));
        when(vouchers.lockById(7L)).thenReturn(Optional.of(TestVouchers.issued(vault)
                .deliveryStatus(VoucherDeliveryStatus.FAILED).deliveredChannel(null).build()));

        VoucherResponse queued = service.resend(7L);

        assertThat(queued.deliveryStatus()).isEqualTo(VoucherDeliveryStatus.PENDING);
        verify(dispatcher).afterCommit(7L, "support1");

        for (VoucherDeliveryStatus busy : List.of(VoucherDeliveryStatus.PENDING, VoucherDeliveryStatus.SENDING)) {
            when(vouchers.lockById(7L)).thenReturn(Optional.of(TestVouchers.issued(vault).deliveryStatus(busy)
                    .deliveredChannel(null).deliveryUpdatedAt(TestVouchers.ISSUED_AT.plusDays(1).minusMinutes(1))
                    .build()));
            assertThatThrownBy(() -> service.resend(7L)).hasMessage("Voucher 7 is being sent already");
        }
        when(vouchers.lockById(7L)).thenReturn(Optional.of(TestVouchers.issued(vault)
                .deliveryStatus(VoucherDeliveryStatus.SENDING).deliveredChannel(null)
                .deliveryUpdatedAt(TestVouchers.ISSUED_AT.plusDays(1).minusMinutes(16)).build()));
        assertThat(service.resend(7L).deliveryStatus()).as("stuck SENDING").isEqualTo(VoucherDeliveryStatus.PENDING);

        when(vouchers.lockById(7L)).thenReturn(Optional.of(TestVouchers.issued(vault).status(VoucherStatus.REDEEMED)
                .redeemedAmount(new BigDecimal("300.00")).build()));
        assertThatThrownBy(() -> service.resend(7L)).hasMessage("Voucher 7 is REDEEMED: there is nothing left to"
                + " spend, so it is not sent");
    }

    @Test
    @DisplayName("the borrower sees their voucher with its code while it can be spent, and without it after")
    void forBorrower() {
        service = withClock(TestVouchers.ISSUED_AT.plusDays(2));
        Voucher issued = TestVouchers.issued(vault).build();
        when(vouchers.findFirstByStaffMemberIdAndLoanAccountOrderByIdDesc(12L, "SGL-2026-000143"))
                .thenReturn(Optional.of(issued));

        BorrowerVoucherResponse shown = service.forBorrower(12L, "SGL-2026-000143").orElseThrow();

        assertThat(shown.status()).isEqualTo(VoucherStatus.ISSUED);
        assertThat(shown.code()).isEqualTo("4829 1506 7331 8406");
        assertThat(shown.scanValue()).isEqualTo(TestVouchers.CODE);
        assertThat(shown.maskedCode()).isEqualTo("**** **** **** 8406");
        assertThat(shown.balance()).isEqualByComparingTo(shown.faceValue());
        assertThat(shown.store()).isEqualTo(VoucherCodeSource.LOANS);
        assertThat(shown.partialSpending()).as("loans' own setting").isTrue();
        assertThat(shown.spent()).isEqualByComparingTo("0");
        assertThat(shown.simulated()).as("GetMore only").isNull();
        assertThat(shown.balanceAsOf()).as("GetMore only").isNull();
        assertThat(shown.toString()).doesNotContain(TestVouchers.CODE).doesNotContain("4829 1506");
        verifyNoInteractions(audit);

        service = withClock(TestVouchers.EXPIRES_AT.plusSeconds(1));
        BorrowerVoucherResponse lapsed = service.forBorrower(12L, "SGL-2026-000143").orElseThrow();
        assertThat(lapsed.status()).isEqualTo(VoucherStatus.EXPIRED);
        assertThat(lapsed.code()).isNull();
        assertThat(lapsed.scanValue()).isNull();
        assertThat(service.forBorrower(13L, "SGL-2026-000143")).isEmpty();
    }
    // ---- GetMore coupons ----------------------------------------------------------------------------------------

    private static final LocalDateTime GM_NOW = TestVouchers.GETMORE_ISSUED_AT;

    private static GetMoreVoucherCommand getMoreCommand(String codeAsReturned, long couponId) {
        return new GetMoreVoucherCommand(164L, "SGL-2026-000164", 3L, 9L, "E1064", "Rutendo Chari", "0771640064",
                new BigDecimal("150"), "USD", GetMoreMode.SIMULATED, couponId, 43L, codeAsReturned,
                TestVouchers.GETMORE_PREFIX, null);
    }

    @Test
    @DisplayName("a GetMore coupon is kept fingerprinted in its normalised form, sealed exactly as GetMore returned it")
    void issueFromGetMore() {
        service = withClock(GM_NOW);
        String asReturned = TestVouchers.GETMORE_CODE.toLowerCase(java.util.Locale.ROOT);

        VoucherResponse response = service.issueFromGetMore(getMoreCommand(asReturned, 1207L));

        ArgumentCaptor<Voucher> saved = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers).saveAndFlush(saved.capture());
        Voucher voucher = saved.getValue();
        assertThat(voucher.getCodeHmac()).isEqualTo(vault.fingerprint(TestVouchers.GETMORE_CODE));
        assertThat(vault.decrypt(voucher.getCodeCiphertext())).isEqualTo(asReturned);
        assertThat(voucher.getCodeLast4()).isEqualTo("8B1C");
        assertThat(voucher.getCodeLength()).isEqualTo(29);
        assertThat(voucher.getCodeSource()).isEqualTo(VoucherCodeSource.GETMORE);
        assertThat(voucher.getStoreMode()).isEqualTo(GetMoreMode.SIMULATED);
        assertThat(voucher.getStaffLoanId()).isEqualTo(164L);
        assertThat(voucher.getExternalCouponId()).isEqualTo(1207L);
        assertThat(voucher.getExternalRuleId()).isEqualTo(43L);
        assertThat(voucher.getCodePrefix()).isEqualTo(TestVouchers.GETMORE_PREFIX);
        assertThat(voucher.getDisbursementReference()).isEqualTo("GETMORE-SIM-1207");
        assertThat(voucher.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(voucher.getHeldAmount()).isEqualByComparingTo("0");
        assertThat(voucher.getCustomerMsisdn()).isEqualTo("+263771640064");
        assertThat(voucher.getExpiresAt()).isEqualTo(TestVouchers.GETMORE_EXPIRES_AT);
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.ISSUED);
        assertThat(voucher.getDeliveryStatus()).isEqualTo(VoucherDeliveryStatus.PENDING);
        assertThat(response.maskedCode()).isEqualTo("****8B1C");
        assertThat(response.codeSource()).isEqualTo(VoucherCodeSource.GETMORE);
        verify(dispatcher).afterCommit(7L, "system");
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit).record(audited.capture());
        AuditLog row = audited.getValue().build();
        assertThat(row.getEventType()).isEqualTo("VOUCHER_ISSUED");
        assertThat(row.getDetail()).contains("code:****8B1C", "source:getmore;mode:SIMULATED;coupon:1207;rule:43")
                .doesNotContainIgnoringCase(TestVouchers.GETMORE_CODE).doesNotContain("7KQ2M9XW");
        assertThat(LIVE_REFERENCE).isEqualTo("GETMORE-1207");
    }

    private static final String LIVE_REFERENCE = VoucherService.getMoreDisbursementReference(GetMoreMode.LIVE, 1207L);

    @Test
    @DisplayName("a GetMore voucher lapses when its issuance's claim reckoned; never already lapsed, nor past the full"
            + " validity")
    void issueFromGetMoreWithTheClaimsExpiry() {
        // Kept at 00:00:10 on 15 October in Harare; claimed at 23:59:50 the day before, so the last day checked
        // against GetMore's rule was 13 November, not 14 November.
        LocalDateTime keptAt = LocalDateTime.of(2026, 10, 14, 22, 0, 10);
        service = withClock(keptAt);
        LocalDateTime claimed = service.expiryFor(LocalDateTime.of(2026, 10, 14, 21, 59, 50));
        assertThat(claimed).isEqualTo(LocalDateTime.of(2026, 11, 13, 21, 59, 59, 999_999_000));
        GetMoreVoucherCommand base = getMoreCommand(TestVouchers.GETMORE_CODE, 1207L);
        java.util.function.Function<LocalDateTime, GetMoreVoucherCommand> expiring = at -> new GetMoreVoucherCommand(
                base.staffLoanId(), base.loanAccount(), base.merchantId(), base.staffMemberId(),
                base.customerReference(), base.customerName(), base.customerMsisdn(), base.faceValue(),
                base.currency(), base.storeMode(), base.couponId(), base.ruleId(), base.codeAsStored(),
                base.codePrefix(), at);

        service.issueFromGetMore(expiring.apply(claimed));

        ArgumentCaptor<Voucher> saved = ArgumentCaptor.forClass(Voucher.class);
        verify(vouchers).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(claimed);

        assertThatThrownBy(() -> service.issueFromGetMore(expiring.apply(keptAt)))
                .isInstanceOf(ValidationException.class).hasMessageStartingWith("expiresAt");
        assertThatThrownBy(() -> service.issueFromGetMore(expiring.apply(service.expiryFor(keptAt).plusDays(1))))
                .isInstanceOf(ValidationException.class).hasMessageStartingWith("expiresAt");
    }

    @Test
    @DisplayName("kept again for the same coupon: the voucher already kept, nothing sent; another coupon is a 409")
    void issueFromGetMoreIsOncePerLoan() {
        service = withClock(GM_NOW);
        Voucher kept = TestVouchers.getMoreIssued(vault).build();
        when(vouchers.findByDisbursementReference("GETMORE-SIM-1207")).thenReturn(Optional.of(kept));

        assertThat(service.issueFromGetMore(getMoreCommand(TestVouchers.GETMORE_CODE, 1207L)).id()).isEqualTo(9L);
        verify(vouchers, never()).saveAndFlush(any());
        verifyNoInteractions(dispatcher, audit);

        when(vouchers.findByStaffLoanId(164L)).thenReturn(Optional.of(kept));
        assertThatThrownBy(() -> service.issueFromGetMore(getMoreCommand("SIM-K7Q2M3XW-AAAAAAAAAAAAAAAA", 1300L)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Loan SGL-2026-000164 already has voucher 9 (GETMORE-SIM-1207), not of GetMore coupon"
                        + " 1300");
        verify(vouchers, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a GetMore code off the issuance's prefix, or one that is not a code, is refused before anything")
    void issueFromGetMoreRefusals() {
        service = withClock(GM_NOW);
        assertThatThrownBy(() -> service.issueFromGetMore(getMoreCommand("OTHER-K7Q2M3XW-7KQ2M9XWD4RT8B1C", 1207L)))
                .isInstanceOf(ValidationException.class).hasMessageNotContaining("7KQ2M9XW");
        assertThatThrownBy(() -> service.issueFromGetMore(getMoreCommand("SIM-K7Q2M3XW-7KQ2!", 1207L)))
                .isInstanceOf(ValidationException.class);
        verify(vouchers, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a GetMore voucher is not cancelled on its own: 409, cancel the loan instead")
    void getMoreCancelledOnlyWithItsLoan() {
        service = withClock(GM_NOW.plusDays(1));
        when(vouchers.lockById(9L)).thenReturn(Optional.of(TestVouchers.getMoreIssued(vault).build()));

        assertThatThrownBy(() -> service.cancel(9L, "lost phone")).isInstanceOf(ConflictException.class)
                .hasMessage("Voucher 9 is the GetMore coupon of loan SGL-2026-000164: cancel the loan instead (POST"
                        + " /lending/v1/staff-loans/164/cancel)");
        verify(vouchers, never()).save(any());
    }

    @Test
    @DisplayName("reveal and the borrower's view give a GetMore code verbatim, never regrouped")
    void getMoreCodeVerbatim() {
        service = withClock(GM_NOW.plusDays(1));
        Voucher voucher = TestVouchers.getMoreIssued(vault).build();
        when(vouchers.findById(9L)).thenReturn(Optional.of(voucher));
        when(vouchers.findFirstByStaffMemberIdAndLoanAccountOrderByIdDesc(9L, "SGL-2026-000164"))
                .thenReturn(Optional.of(voucher));

        VoucherCodeResponse revealed = service.reveal(9L, "Borrower on the phone");
        assertThat(revealed.code()).isEqualTo(TestVouchers.GETMORE_CODE);
        assertThat(revealed.scanValue()).isEqualTo(TestVouchers.GETMORE_CODE);

        BorrowerVoucherResponse shown = service.forBorrower(9L, "SGL-2026-000164").orElseThrow();
        assertThat(shown.code()).isEqualTo(TestVouchers.GETMORE_CODE);
        assertThat(shown.scanValue()).isEqualTo(TestVouchers.GETMORE_CODE);
        assertThat(shown.maskedCode()).isEqualTo("****8B1C");
        assertThat(shown.store()).isEqualTo(VoucherCodeSource.GETMORE);
        assertThat(shown.partialSpending()).isTrue();
        assertThat(shown.simulated()).isTrue();
        assertThat(shown.balanceAsOf()).as("never synced: as issued").isEqualTo(TestVouchers.GETMORE_ISSUED_AT);
        assertThat(shown.balance()).isEqualByComparingTo("150.00");
        assertThat(shown.toString()).doesNotContain(TestVouchers.GETMORE_CODE);
    }

    @Test
    @DisplayName("a GetMore voucher's borrower balance is what can still be spent at GetMore: less what is held")
    void getMoreBorrowerBalance() {
        service = withClock(GM_NOW.plusDays(1));
        LocalDateTime synced = GM_NOW.plusMinutes(20);
        when(vouchers.findFirstByStaffMemberIdAndLoanAccountOrderByIdDesc(9L, "SGL-2026-000164"))
                .thenReturn(Optional.of(TestVouchers.getMoreIssued(vault).status(VoucherStatus.PARTIALLY_REDEEMED)
                        .redeemedAmount(new BigDecimal("45.50")).heldAmount(new BigDecimal("20.00"))
                        .lastRedeemedAt(GM_NOW.plusMinutes(11)).lastRedeemedOutlet("GetMore Avondale")
                        .syncedAt(synced).storeMode(GetMoreMode.LIVE).build()));
        when(binding.partialSpending()).thenReturn(false);

        BorrowerVoucherResponse shown = service.forBorrower(9L, "SGL-2026-000164").orElseThrow();

        assertThat(shown.balance()).isEqualByComparingTo("84.50");
        assertThat(shown.spent()).isEqualByComparingTo("45.50");
        assertThat(shown.balanceAsOf()).isEqualTo(synced);
        assertThat(shown.partialSpending()).isFalse();
        assertThat(shown.simulated()).as("absent on LIVE").isNull();
    }

    @Test
    @DisplayName("cancelled with its loan only when untouched: a held or spent voucher, or one lapsed, is refused")
    void cancelGetMoreForLoan() {
        service = withClock(GM_NOW.plusDays(1));
        LocalDateTime now = GM_NOW.plusDays(1);
        Voucher untouched = TestVouchers.getMoreIssued(vault).build();
        when(vouchers.lockById(9L)).thenReturn(Optional.of(untouched));

        VoucherResponse cancelled = service.cancelGetMoreForLoan(9L, "credit1", " Accepted by mistake ", now);
        assertThat(cancelled.status()).isEqualTo(VoucherStatus.CANCELLED);
        assertThat(untouched.getCancelledBy()).isEqualTo("credit1");
        assertThat(untouched.getCancellationReason()).isEqualTo("Accepted by mistake");

        when(vouchers.lockById(9L)).thenReturn(Optional.of(TestVouchers.getMoreIssued(vault)
                .heldAmount(new BigDecimal("10.00")).build()));
        assertThatThrownBy(() -> service.cancelGetMoreForLoan(9L, "credit1", "x", now))
                .isInstanceOf(ConflictException.class).hasMessageContaining("10.00 held at the till");

        when(vouchers.lockById(9L)).thenReturn(Optional.of(TestVouchers.getMoreIssued(vault)
                .status(VoucherStatus.PARTIALLY_REDEEMED).redeemedAmount(new BigDecimal("45.50"))
                .lastRedeemedAt(now.minusHours(1)).lastRedeemedOutlet("GetMore Avondale").build()));
        assertThatThrownBy(() -> service.cancelGetMoreForLoan(9L, "credit1", "x", now))
                .isInstanceOf(ConflictException.class).hasMessageContaining("45.50 spent");

        when(vouchers.lockById(9L)).thenReturn(Optional.of(TestVouchers.getMoreIssued(vault)
                .status(VoucherStatus.ISSUED).refundedAmount(new BigDecimal("5.00")).build()));
        assertThatThrownBy(() -> service.cancelGetMoreForLoan(9L, "credit1", "x", now))
                .as("spent and refunded back to nothing is still used").isInstanceOf(ConflictException.class);

        when(vouchers.lockById(9L)).thenReturn(Optional.of(TestVouchers.getMoreIssued(vault).build()));
        assertThatThrownBy(() -> service.cancelGetMoreForLoan(9L, "credit1", "x",
                TestVouchers.GETMORE_EXPIRES_AT.plusSeconds(1))).as("lapsed on its own")
                .isInstanceOf(ConflictException.class);

        Voucher bankStopped = TestVouchers.getMoreIssued(vault).status(VoucherStatus.EXPIRED)
                .stoppedReason(VoucherStopReason.BANK_STOPPED).expiresAt(now.minusMinutes(5)).build();
        when(vouchers.lockById(9L)).thenReturn(Optional.of(bankStopped));
        assertThat(service.cancelGetMoreForLoan(9L, "credit1", "x", now).status())
                .as("stopped by the bank with nothing spent").isEqualTo(VoucherStatus.CANCELLED);

        when(vouchers.lockById(9L)).thenReturn(Optional.of(TestVouchers.getMoreIssued(vault)
                .status(VoucherStatus.EXPIRED).stoppedReason(VoucherStopReason.STORE_WITHDRAWN)
                .expiresAt(now.minusMinutes(5)).couponWithdrawnAt(now.minusMinutes(5)).build()));
        assertThat(service.cancelGetMoreForLoan(9L, "credit1", "x", now).status())
                .as("its coupon gone at GetMore, recorded withdrawn, nothing spent: a cancellation's own withdrawal")
                .isEqualTo(VoucherStatus.CANCELLED);

        when(vouchers.lockById(9L)).thenReturn(Optional.of(TestVouchers.getMoreIssued(vault)
                .status(VoucherStatus.EXPIRED).stoppedReason(VoucherStopReason.SIMULATOR_EXPIRED)
                .expiresAt(now.minusMinutes(5)).couponWithdrawnAt(now.minusMinutes(5)).build()));
        assertThatThrownBy(() -> service.cancelGetMoreForLoan(9L, "credit1", "x", now))
                .as("expired by the simulator").isInstanceOf(ConflictException.class);

        when(vouchers.lockById(7L)).thenReturn(Optional.of(TestVouchers.issued(vault).build()));
        assertThatThrownBy(() -> service.cancelGetMoreForLoan(7L, "credit1", "x", now))
                .isInstanceOf(ConflictException.class).hasMessage("Voucher 7 is not a GetMore voucher");
    }

    @Test
    @DisplayName("stopping a GetMore voucher: it lapses now, never later than it would have; a closed one is kept")
    void stopGetMoreNow() {
        LocalDateTime now = GM_NOW.plusDays(2);
        service = withClock(now);
        Voucher open = TestVouchers.getMoreIssued(vault).build();
        when(vouchers.lockById(9L)).thenReturn(Optional.of(open));

        assertThat(service.stopGetMoreNow(9L, VoucherStopReason.BANK_STOPPED, now)).isTrue();
        assertThat(open.getStatus()).isEqualTo(VoucherStatus.EXPIRED);
        assertThat(open.getStoppedReason()).isEqualTo(VoucherStopReason.BANK_STOPPED);
        assertThat(open.getExpiresAt()).isEqualTo(now);
        service.markCouponWithdrawn(9L, now);
        service.markCouponWithdrawn(9L, now.plusMinutes(1));
        assertThat(open.getCouponWithdrawnAt()).as("the first confirmation is kept").isEqualTo(now);

        assertThat(service.stopGetMoreNow(9L, VoucherStopReason.STORE_WITHDRAWN, now.plusMinutes(1))).isFalse();
        assertThat(open.getStoppedReason()).isEqualTo(VoucherStopReason.BANK_STOPPED);
        assertThat(open.getExpiresAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("the simulator's expire-now stops a simulated voucher; a live one is refused")
    void expireNowForSimulation() {
        LocalDateTime now = GM_NOW.plusDays(3).plusNanos(1_500);
        service = withClock(now);
        Voucher simulated = TestVouchers.getMoreIssued(vault).build();
        when(vouchers.lockById(9L)).thenReturn(Optional.of(simulated));

        VoucherResponse expired = service.expireNowForSimulation(9L);

        assertThat(expired.status()).isEqualTo(VoucherStatus.EXPIRED);
        assertThat(expired.stoppedReason()).isEqualTo(VoucherStopReason.SIMULATOR_EXPIRED);
        assertThat(simulated.getExpiresAt()).as("to the microsecond the column keeps")
                .isEqualTo(GM_NOW.plusDays(3).plusNanos(1_000));
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit).record(audited.capture());
        assertThat(audited.getValue().build().getEventType()).isEqualTo("VOUCHER_EXPIRED_BY_SIMULATOR");

        when(vouchers.lockById(9L)).thenReturn(Optional.of(TestVouchers.getMoreIssued(vault)
                .storeMode(GetMoreMode.LIVE).disbursementReference("GETMORE-1207").build()));
        assertThatThrownBy(() -> service.expireNowForSimulation(9L)).isInstanceOf(ConflictException.class)
                .hasMessage("Voucher 9 is not a simulated GetMore voucher");
    }

    @Test
    @DisplayName("a borrower's spends and refunds in time order, each with what was left; a refund at its sale's outlet")
    void spendsFor() {
        service = withClock(GM_NOW.plusDays(1));
        LocalDateTime at = GM_NOW.plusMinutes(10);
        Voucher voucher = TestVouchers.getMoreIssued(vault).status(VoucherStatus.PARTIALLY_REDEEMED)
                .redeemedAmount(new BigDecimal("90.00")).refundedAmount(new BigDecimal("10.00"))
                .lastRedeemedAt(at.plusMinutes(5)).lastRedeemedOutlet("GetMore Avondale")
                .syncedAt(at.plusMinutes(10)).build();
        when(vouchers.findFirstByStaffMemberIdAndLoanAccountOrderByIdDesc(9L, "SGL-2026-000164"))
                .thenReturn(Optional.of(voucher));
        when(redemptions.findByVoucherIdOrderByIdAsc(9L)).thenReturn(List.of(
                spend(12L, "SIM000000012", "45.50", "104.50", at),
                spend(13L, "SIM000000013", "54.50", "50.00", at.plusMinutes(5))));
        when(refunds.findByVoucherIdOrderByIdAsc(9L)).thenReturn(List.of(VoucherRefund.builder().id(1L).voucherId(9L)
                .merchantId(3L).merchantReference("GMS:CM:3").storeMode(GetMoreMode.SIMULATED).externalOrderId(12L)
                .externalCreditmemoId(3L).externalCreditmemoNumber("SIMCM00000003").amount(new BigDecimal("10.00"))
                .balanceAfter(new BigDecimal("60.00")).refundedAt(at.plusMinutes(9)).recordedAt(at.plusMinutes(10))
                .recordedBy("getmore-simulator").build()));

        BorrowerVoucherSpendsResponse spends = service.spendsFor(9L, "SGL-2026-000164");

        assertThat(spends.spent()).isEqualByComparingTo("90.00");
        assertThat(spends.balance()).isEqualByComparingTo("60.00");
        assertThat(spends.balanceAsOf()).isEqualTo(at.plusMinutes(10));
        assertThat(spends.simulated()).isTrue();
        assertThat(spends.movements()).extracting(BorrowerVoucherSpendsResponse.Movement::kind,
                        BorrowerVoucherSpendsResponse.Movement::amount,
                        BorrowerVoucherSpendsResponse.Movement::balanceAfter,
                        BorrowerVoucherSpendsResponse.Movement::outlet,
                        BorrowerVoucherSpendsResponse.Movement::orderNumber)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BorrowerVoucherSpendsResponse.Kind.SPEND,
                                new BigDecimal("45.50"), new BigDecimal("104.50"), "GetMore Avondale",
                                "SIM000000012"),
                        org.assertj.core.groups.Tuple.tuple(BorrowerVoucherSpendsResponse.Kind.SPEND,
                                new BigDecimal("54.50"), new BigDecimal("50.00"), "GetMore Avondale",
                                "SIM000000013"),
                        org.assertj.core.groups.Tuple.tuple(BorrowerVoucherSpendsResponse.Kind.REFUND,
                                new BigDecimal("10.00"), new BigDecimal("60.00"), "GetMore Avondale",
                                "SIM000000012"));
        assertThatThrownBy(() -> service.spendsFor(9L, "SGL-2026-000999")).isInstanceOf(NotFoundException.class)
                .hasMessage("Loan SGL-2026-000999 has no voucher yet");
    }

    @Test
    @DisplayName("staff's detail of a GetMore voucher adds its refunds and open sync issues; loans' own has neither")
    void detailAddsRefundsAndIssues() {
        service = withClock(GM_NOW.plusDays(1));
        when(vouchers.findById(9L)).thenReturn(Optional.of(TestVouchers.getMoreIssued(vault).build()));
        when(issues.findByVoucherIdAndClearedAtIsNullOrderByIdAsc(9L)).thenReturn(List.of(VoucherSyncIssue.builder()
                .id(4L).voucherId(9L).kind(VoucherSyncIssueKind.CURRENCY_MISMATCH).blocking(true)
                .externalRef("order:12").detail("order SIM000000012 is in ZWG, the voucher in USD")
                .firstSeenAt(GM_NOW).lastSeenAt(GM_NOW).build()));

        VoucherDetailResponse detail = service.voucher(9L);

        assertThat(detail.refunds()).isEmpty();
        assertThat(detail.syncIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.kind()).isEqualTo(VoucherSyncIssueKind.CURRENCY_MISMATCH);
            assertThat(issue.loanAccount()).isEqualTo("SGL-2026-000164");
            assertThat(issue.blocking()).isTrue();
        });
        assertThat(detail.voucher().storeMode()).isEqualTo(GetMoreMode.SIMULATED);
        assertThat(detail.voucher().refundedAmount()).isEqualByComparingTo("0");

        when(vouchers.findById(7L)).thenReturn(Optional.of(TestVouchers.issued(vault).build()));
        VoucherDetailResponse loans = service.voucher(7L);
        assertThat(loans.refunds()).isNull();
        assertThat(loans.syncIssues()).isNull();
        assertThat(loans.voucher().codeSource()).isEqualTo(VoucherCodeSource.LOANS);
        assertThat(loans.voucher().storeMode()).isNull();
        assertThat(loans.voucher().refundedAmount()).as("absent for loans' own").isNull();
    }

    @Test
    @DisplayName("GetMore's confirmations of the coupon's expiry and withdrawal keep the first; withdrawal is audited")
    void couponConfirmations() {
        service = withClock(GM_NOW);
        Voucher voucher = TestVouchers.getMoreIssued(vault).build();
        when(vouchers.lockById(9L)).thenReturn(Optional.of(voucher));

        service.markCouponExpirySet(9L, GM_NOW.plusSeconds(2));
        service.markCouponExpirySet(9L, GM_NOW.plusSeconds(9));
        assertThat(voucher.getCouponExpirySetAt()).isEqualTo(GM_NOW.plusSeconds(2));

        service.markCouponWithdrawn(9L, GM_NOW.plusDays(1));
        service.markCouponWithdrawn(9L, GM_NOW.plusDays(2));
        assertThat(voucher.getCouponWithdrawnAt()).isEqualTo(GM_NOW.plusDays(1));
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit).record(audited.capture());
        assertThat(audited.getValue().build().getEventType()).isEqualTo("VOUCHER_COUPON_WITHDRAWN");
    }

    private static VoucherRedemption spend(long order, String number, String amount, String after, LocalDateTime at) {
        return VoucherRedemption.builder().id(order).voucherId(9L).merchantId(3L)
                .merchantReference("GMS:O:" + order + ":" + new BigDecimal(amount).movePointRight(2).intValue())
                .amount(new BigDecimal(amount)).balanceAfter(new BigDecimal(after)).outletId("store-2")
                .outletName("GetMore Avondale").redeemedBy("getmore-simulator").redeemedAt(at)
                .source(VoucherRedemptionSource.GETMORE_SYNC).storeMode(GetMoreMode.SIMULATED).externalOrderId(order)
                .externalOrderNumber(number).recordedAt(at.plusMinutes(1)).build();
    }
}

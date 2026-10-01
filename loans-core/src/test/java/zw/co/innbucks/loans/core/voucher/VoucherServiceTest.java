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
import zw.co.innbucks.loans.core.exception.ValidationException;

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
 * cancel it before it is spent, and send it again to the same number (FR-SGL-037).
 */
class VoucherServiceTest {

    private static final IssueVoucherCommand COMMAND = new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN,
            " BRNET-20261001-0007 ", "SGL-2026-000143", 12L, "E1012", "Chipo Banda", "0772123123",
            new BigDecimal("300"), "USD");

    private final VoucherCodeVault vault = VoucherCodeVaultTest.vault();
    private VoucherRepository vouchers;
    private VoucherDeliveryDispatcher dispatcher;
    private AuditService audit;
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
        dispatcher = mock(VoucherDeliveryDispatcher.class);
        audit = mock(AuditService.class);
    }

    private VoucherService withClock(LocalDateTime utc) {
        AuthService auth = mock(AuthService.class);
        when(auth.getLoggedInUsername()).thenReturn("support1");
        return new VoucherService(vouchers, mock(VoucherRedemptionRepository.class),
                mock(VoucherDeliveryRepository.class), vault, new VoucherCodeGenerator(new Random(7)), dispatcher,
                new VoucherProperties(), auth, audit,
                new MarketTimeZone("ZW", Clock.fixed(utc.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)));
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
        assertThat(voucher.getCustomerMsisdn()).isEqualTo("+263772123123");
        assertThat(voucher.getFaceValue()).isEqualTo(new BigDecimal("300.00"));
        assertThat(voucher.getRedeemedAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.ISSUED);
        assertThat(voucher.getDeliveryStatus()).isEqualTo(VoucherDeliveryStatus.PENDING);
        assertThat(voucher.getIssuedAt()).isEqualTo(TestVouchers.ISSUED_AT);
        assertThat(voucher.getExpiresAt()).isEqualTo(TestVouchers.EXPIRES_AT);
        assertThat(response.maskedCode()).isEqualTo("**** **** **** " + code.substring(12));
        assertThat(response.customerMsisdn()).isEqualTo("****3123");
        verify(dispatcher).afterCommit(7L, "system");
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit).record(audited.capture());
        AuditLog row = audited.getValue().build();
        assertThat(row.getEventType()).isEqualTo("VOUCHER_ISSUED");
        assertThat(row.getDetail()).doesNotContain(code).contains("faceValue:300.00 USD");
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
    @DisplayName("the same disbursement for a different loan or amount is a 409, not a second voucher")
    void sameDisbursementDifferentPayout() {
        service = withClock(TestVouchers.ISSUED_AT);
        when(vouchers.findByDisbursementReference("BRNET-20261001-0007"))
                .thenReturn(Optional.of(TestVouchers.issued(vault).faceValue(new BigDecimal("250.00")).build()));

        assertThatThrownBy(() -> service.issue(COMMAND)).isInstanceOf(ConflictException.class)
                .hasMessage("Disbursement BRNET-20261001-0007 already has voucher 7, for a different loan or amount");
    }

    @Test
    @DisplayName("an unreachable number, a zero or fractional-cent amount, or no keys: nothing is issued")
    void issueRefusals() {
        service = withClock(TestVouchers.ISSUED_AT);
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                null, "E1", "Name", "0242700000", BigDecimal.TEN, "USD"))).isInstanceOf(ValidationException.class)
                .hasMessageStartingWith("customerMsisdn");
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                null, "E1", "Name", "0772123123", new BigDecimal("10.005"), "USD")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                null, "E1", "Name", "0772123123", BigDecimal.ZERO, "USD"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.issue(new IssueVoucherCommand(VoucherProduct.STAFF_GROCERY_LOAN, "D1", "L1",
                null, "E1", "Name", "0772123123", BigDecimal.TEN, "usd"))).isInstanceOf(ValidationException.class);
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
        assertThat(shown.toString()).doesNotContain(TestVouchers.CODE).doesNotContain("4829 1506");
        verifyNoInteractions(audit);

        service = withClock(TestVouchers.EXPIRES_AT.plusSeconds(1));
        BorrowerVoucherResponse lapsed = service.forBorrower(12L, "SGL-2026-000143").orElseThrow();
        assertThat(lapsed.status()).isEqualTo(VoucherStatus.EXPIRED);
        assertThat(lapsed.code()).isNull();
        assertThat(lapsed.scanValue()).isNull();
        assertThat(service.forBorrower(13L, "SGL-2026-000143")).isEmpty();
    }
}

package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.notifications.NotificationDeliveryException;
import zw.co.innbucks.loans.core.notifications.SmsNotificationClient;
import zw.co.innbucks.loans.core.notifications.WhatsAppNotificationClient;
import zw.co.innbucks.loans.core.voucher.VoucherDelivery.Status;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sending a voucher (FR-SGL-034, FR-SGL-037): on each channel in turn until one accepts it, the code dashed and in full,
 * every attempt logged without it, a voucher no channel accepted left FAILED for follow-up, and never sent twice.
 */
class VoucherDeliverySenderTest {

    private static final LocalDateTime NOW = TestVouchers.ISSUED_AT.plusSeconds(1);
    private static final String MESSAGE = "InnBucks Staff Grocery Loan. Your GetMore voucher is 4829-1506-7331-8406,"
            + " worth USD 300.00, valid until 31 Oct 2026. Show it at any GetMore till.";

    private final List<VoucherDelivery> logged = new ArrayList<>();
    private Voucher voucher;
    private boolean claimable = true;
    private VoucherProperties properties;
    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private VoucherDeliverySender sender;

    @BeforeEach
    void setUp() {
        VoucherCodeVault vault = VoucherCodeVaultTest.vault();
        voucher = TestVouchers.issued(vault).deliveryStatus(VoucherDeliveryStatus.SENDING).deliveredChannel(null)
                .build();
        VoucherRepository vouchers = mock(VoucherRepository.class);
        when(vouchers.claimDelivery(7L, VoucherDeliveryStatus.PENDING, VoucherDeliveryStatus.SENDING, NOW))
                .thenAnswer(i -> claimable ? 1 : 0);
        when(vouchers.findById(7L)).thenAnswer(i -> Optional.of(voucher));
        when(vouchers.save(any())).thenAnswer(i -> voucher = i.getArgument(0));
        VoucherDeliveryRepository deliveries = mock(VoucherDeliveryRepository.class);
        when(deliveries.saveAll(any())).thenAnswer(i -> {
            Iterable<VoucherDelivery> rows = i.getArgument(0);
            rows.forEach(logged::add);
            return rows;
        });
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        properties = new VoucherProperties();
        MarketTimeZone market = new MarketTimeZone("ZW", Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        sender = new VoucherDeliverySender(vouchers, deliveries, vault, sms, whatsApp, properties, market,
                mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("WhatsApp first: the dashed code in full, the upstream reply withheld from the logs, one SENT attempt")
    void whatsAppAccepted() {
        assertThat(sender.send(7L, "system")).isTrue();

        verify(whatsApp).sendCustomNotification("+263772123123", MESSAGE, true);
        verifyNoInteractions(sms);
        assertThat(voucher.getDeliveryStatus()).isEqualTo(VoucherDeliveryStatus.SENT);
        assertThat(voucher.getDeliveredChannel()).isEqualTo(VoucherChannel.WHATSAPP);
        assertThat(logged).singleElement().satisfies(attempt -> {
            assertThat(attempt.getChannel()).isEqualTo(VoucherChannel.WHATSAPP);
            assertThat(attempt.getStatus()).isEqualTo(Status.SENT);
            assertThat(attempt.getGatewayReference()).as("the WhatsApp gateway takes no reference").isNull();
            assertThat(attempt.getTemplate()).isEqualTo("VOUCHER_ISSUED");
            assertThat(attempt.getTemplateVersion()).isEqualTo(1);
            assertThat(attempt.getRequestedBy()).isEqualTo("system");
            assertThat(attempt.toString()).doesNotContain(TestVouchers.CODE).doesNotContain("4829-1506");
        });
    }

    @Test
    @DisplayName("WhatsApp refused: the SMS takes it, and both attempts are logged")
    void smsFallback() {
        doThrow(new NotificationDeliveryException("WhatsApp gateway rejected the message: HTTP 400"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString(), eq(true));

        sender.send(7L, "support1");

        verify(sms).sendSms(eq("+263772123123"), eq(MESSAGE), anyString(), eq(true));
        assertThat(voucher.getDeliveryStatus()).isEqualTo(VoucherDeliveryStatus.SENT);
        assertThat(voucher.getDeliveredChannel()).isEqualTo(VoucherChannel.SMS);
        assertThat(logged).extracting(VoucherDelivery::getChannel, VoucherDelivery::getStatus,
                        VoucherDelivery::getFailureReason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(VoucherChannel.WHATSAPP, Status.FAILED,
                                "WhatsApp gateway rejected the message: HTTP 400"),
                        org.assertj.core.groups.Tuple.tuple(VoucherChannel.SMS, Status.SENT, null));
        assertThat(logged.get(1).getGatewayReference()).startsWith("LOANS-VCH-");
        assertThat(logged).allSatisfy(attempt -> assertThat(attempt.getRequestedBy()).isEqualTo("support1"));
    }

    @Test
    @DisplayName("every channel refused: FAILED, for the follow-up list (FR-SGL-037)")
    void everyChannelRefused() {
        doThrow(new NotificationDeliveryException("SMS down")).when(sms).sendSms(anyString(), anyString(),
                anyString(), eq(true));
        doThrow(new NotificationDeliveryException("WhatsApp is not configured")).when(whatsApp)
                .sendCustomNotification(anyString(), anyString(), eq(true));

        sender.send(7L, "system");

        assertThat(voucher.getDeliveryStatus()).isEqualTo(VoucherDeliveryStatus.FAILED);
        assertThat(voucher.getDeliveredChannel()).isNull();
        assertThat(logged).extracting(VoucherDelivery::getChannel, VoucherDelivery::getStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(VoucherChannel.WHATSAPP, Status.FAILED),
                        org.assertj.core.groups.Tuple.tuple(VoucherChannel.SMS, Status.FAILED));
    }

    @Test
    @DisplayName("the channels go in the configured order: SMS first when so set")
    void configuredOrder() {
        properties.setDeliveryChannels(List.of(VoucherChannel.SMS, VoucherChannel.WHATSAPP));

        sender.send(7L, "system");

        verify(sms).sendSms(eq("+263772123123"), eq(MESSAGE), anyString(), eq(true));
        verify(whatsApp, never()).sendCustomNotification(anyString(), anyString(), eq(true));
        assertThat(voucher.getDeliveredChannel()).isEqualTo(VoucherChannel.SMS);
    }

    @Test
    @DisplayName("a resend after a partial purchase states what is left, not the face value")
    void resendStatesTheBalance() {
        voucher = TestVouchers.issued(VoucherCodeVaultTest.vault()).status(VoucherStatus.PARTIALLY_REDEEMED)
                .redeemedAmount(new java.math.BigDecimal("180.00")).build();

        sender.send(7L, "support1");

        verify(whatsApp).sendCustomNotification("+263772123123", MESSAGE.replace("USD 300.00", "USD 120.00"), true);
    }

    @Test
    @DisplayName("a delivery someone else claimed is not sent again")
    void claimedElsewhere() {
        claimable = false;

        assertThat(sender.send(7L, "system")).isFalse();

        verifyNoInteractions(sms, whatsApp);
        assertThat(logged).isEmpty();
    }
}

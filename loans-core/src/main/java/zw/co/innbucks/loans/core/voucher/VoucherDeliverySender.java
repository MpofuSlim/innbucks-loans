package zw.co.innbucks.loans.core.voucher;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.notifications.SmsNotificationClient;
import zw.co.innbucks.loans.core.notifications.WhatsAppNotificationClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends one voucher to its customer (FR-SGL-034): on each configured channel in turn until one accepts it, by default
 * SMS through the InnBucks notification API and then WhatsApp. Every attempt is logged, sent or not, and none holds the
 * code. When every channel refuses, the voucher is FAILED: on the failed-delivery list for operations to follow up
 * (FR-SGL-037), and still readable in full by those entitled to.
 *
 * <p>Not subject to a customer's opt-out: the voucher is what the loan was paid out as, a service message (FR-GEN-005).
 * The delivery is claimed first (PENDING to SENDING, in its own short transaction), so it is never sent twice; the code
 * is unsealed only then, and only in memory; the gateways are called outside any transaction; and the outcome is
 * recorded in a second one.
 */
@Slf4j
@Component
public class VoucherDeliverySender {

    private static final int FAILURE_LENGTH = 255;

    private final VoucherRepository voucherRepository;
    private final VoucherDeliveryRepository deliveryRepository;
    private final VoucherCodeVault vault;
    private final SmsNotificationClient smsClient;
    private final WhatsAppNotificationClient whatsAppClient;
    private final VoucherProperties properties;
    private final MarketTimeZone marketTimeZone;
    private final TransactionTemplate transactionTemplate;

    public VoucherDeliverySender(VoucherRepository voucherRepository, VoucherDeliveryRepository deliveryRepository,
                                 VoucherCodeVault vault, SmsNotificationClient smsClient,
                                 WhatsAppNotificationClient whatsAppClient, VoucherProperties properties,
                                 MarketTimeZone marketTimeZone, PlatformTransactionManager transactionManager) {
        this.voucherRepository = voucherRepository;
        this.deliveryRepository = deliveryRepository;
        this.vault = vault;
        this.smsClient = smsClient;
        this.whatsAppClient = whatsAppClient;
        this.properties = properties;
        this.marketTimeZone = marketTimeZone;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** What a claimed delivery is sent with. The message holds the code, so it is kept out of toString. */
    private record Claim(Long voucherId, String recipient, String message) {
        @Override
        public String toString() {
            return "Claim[voucherId=" + voucherId + "]";
        }
    }

    /**
     * Sends the voucher if its delivery is still PENDING.
     *
     * @param requestedBy who asked: {@code system} when it was issued, the staff member for a resend
     * @return whether a gateway was called
     */
    public boolean send(Long voucherId, String requestedBy) {
        if (!vault.isConfigured()) {
            log.warn("Voucher {} not sent: the voucher code keys are not configured; it stays PENDING", voucherId);
            return false;
        }
        Claim claim = transactionTemplate.execute(status -> claim(voucherId));
        if (claim == null) {
            return false;
        }
        List<VoucherDelivery> attempts = new ArrayList<>();
        VoucherChannel delivered = null;
        for (VoucherChannel channel : properties.getDeliveryChannels()) {
            String reference = channel == VoucherChannel.SMS ? "LOANS-VCH-" + UUID.randomUUID() : null;
            try {
                if (channel == VoucherChannel.SMS) {
                    smsClient.sendSms(claim.recipient(), claim.message(), reference, true);
                } else {
                    whatsAppClient.sendCustomNotification(claim.recipient(), claim.message(), true);
                }
                attempts.add(attempt(claim, channel, reference, requestedBy, null));
                delivered = channel;
                break;
            } catch (RuntimeException failure) {
                attempts.add(attempt(claim, channel, reference, requestedBy, failure));
                log.warn("Voucher {} not sent by {} to {}: {}", voucherId, channel,
                        MsisdnUtils.mask(claim.recipient()), failure.getMessage());
            }
        }
        VoucherChannel channel = delivered;
        try {
            transactionTemplate.executeWithoutResult(status -> record(voucherId, attempts, channel));
        } catch (RuntimeException ex) {
            // It stays SENDING, so it is never sent again on its own; a resend clears it.
            log.error("Voucher {} was {} but the outcome could not be recorded", voucherId,
                    channel == null ? "not delivered" : "sent by " + channel, ex);
        }
        if (channel == null) {
            log.error("Voucher {} could not be sent to {} on any channel: it is on the failed-delivery list for"
                    + " follow-up (FR-SGL-037)", voucherId, MsisdnUtils.mask(claim.recipient()));
        } else {
            log.info("Voucher {} sent to {} by {}", voucherId, MsisdnUtils.mask(claim.recipient()), channel);
        }
        return true;
    }

    /** Claims the delivery; null when it is not this caller's to send. */
    private Claim claim(Long voucherId) {
        if (voucherRepository.claimDelivery(voucherId, VoucherDeliveryStatus.PENDING, VoucherDeliveryStatus.SENDING,
                marketTimeZone.nowUtc()) == 0) {
            return null;
        }
        Voucher voucher = voucherRepository.findById(voucherId).orElseThrow();
        String code = vault.decrypt(voucher.getCodeCiphertext());
        return new Claim(voucherId, voucher.getCustomerMsisdn(), VoucherMessage.text(code, voucher.getCurrency(),
                voucher.balance(), marketTimeZone.atMarketFromUtc(voucher.getExpiresAt())));
    }

    private VoucherDelivery attempt(Claim claim, VoucherChannel channel, String reference, String requestedBy,
                                    RuntimeException failure) {
        return VoucherDelivery.builder()
                .voucherId(claim.voucherId())
                .channel(channel)
                .recipient(claim.recipient())
                .template(VoucherMessage.TEMPLATE)
                .templateVersion(VoucherMessage.VERSION)
                .status(failure == null ? VoucherDelivery.Status.SENT : VoucherDelivery.Status.FAILED)
                .gatewayReference(reference)
                .failureReason(failure == null ? null : StringUtils.abbreviate(StringUtils.defaultIfBlank(
                        failure.getMessage(), failure.getClass().getSimpleName()), FAILURE_LENGTH))
                .requestedBy(requestedBy)
                .attemptedAt(marketTimeZone.nowUtc())
                .build();
    }

    private void record(Long voucherId, List<VoucherDelivery> attempts, VoucherChannel delivered) {
        deliveryRepository.saveAll(attempts);
        Voucher voucher = voucherRepository.findById(voucherId).orElseThrow();
        voucher.finishDelivery(delivered, marketTimeZone.nowUtc());
        voucherRepository.save(voucher);
    }
}

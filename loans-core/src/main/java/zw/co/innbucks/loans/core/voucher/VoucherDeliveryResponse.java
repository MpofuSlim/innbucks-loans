package zw.co.innbucks.loans.core.voucher;

import zw.co.innbucks.loans.core.MsisdnUtils;

import java.time.LocalDateTime;

/** One attempt to send a voucher, as the delivery log holds it (FR-GEN-004). */
public record VoucherDeliveryResponse(Long id, VoucherChannel channel, String recipient, String template,
                                      int templateVersion, VoucherDelivery.Status status, String gatewayReference,
                                      String failureReason, String requestedBy, LocalDateTime attemptedAt) {

    public static VoucherDeliveryResponse of(VoucherDelivery delivery) {
        return new VoucherDeliveryResponse(delivery.getId(), delivery.getChannel(),
                MsisdnUtils.mask(delivery.getRecipient()), delivery.getTemplate(), delivery.getTemplateVersion(),
                delivery.getStatus(), delivery.getGatewayReference(), delivery.getFailureReason(),
                delivery.getRequestedBy(), delivery.getAttemptedAt());
    }
}

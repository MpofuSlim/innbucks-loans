package zw.co.innbucks.loans.core.voucher;

import lombok.Getter;

/** A till's validation or redemption refused, for {@link #getRefusal()}; nothing was redeemed. */
@Getter
public class VoucherRefusedException extends RuntimeException {

    private final VoucherRefusal refusal;

    public VoucherRefusedException(VoucherRefusal refusal) {
        this(refusal, refusal.message());
    }

    public VoucherRefusedException(VoucherRefusal refusal, String message) {
        super(message);
        this.refusal = refusal;
    }
}

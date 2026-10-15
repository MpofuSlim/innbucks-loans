package zw.co.innbucks.loans.core.getmore;

/**
 * {@link GenerateGuard#beforeGenerate()} refused: as many generates as this issuance is allowed may already have
 * reached GetMore (Magento's generate has no idempotency key), and a coupon minted then never showed up on its prefix.
 * Nothing was sent; only an operator's explicit "generate again" allows another.
 */
public class GenerateNotAllowedException extends RuntimeException {

    public GenerateNotAllowedException(String message) {
        super(message);
    }
}

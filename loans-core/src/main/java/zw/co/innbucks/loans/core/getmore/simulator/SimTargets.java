package zw.co.innbucks.loans.core.getmore.simulator;

/** How a staging test action names its voucher: by its loan or by its code, exactly one of the two. */
final class SimTargets {

    private SimTargets() {
    }

    static boolean exactlyOne(String loanReference, String code) {
        return isBlank(loanReference) != isBlank(code);
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

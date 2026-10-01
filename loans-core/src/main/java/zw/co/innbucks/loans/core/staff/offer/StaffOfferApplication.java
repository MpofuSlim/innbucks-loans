package zw.co.innbucks.loans.core.staff.offer;

/**
 * The outcome of a borrower applying in the SuperApp (FR-SGL-025): the offer to take up, and whether it was made just
 * now, or why there is none.
 *
 * @param offer       the offer they hold or were just made; null when there is none
 * @param created     whether it was made on demand by this application
 * @param verdict     why they may not be offered, when they may not; ELIGIBLE otherwise
 * @param unavailable why no offer can be made to anyone just now, when that is why there is none
 */
public record StaffOfferApplication(StaffOffer offer, boolean created, StaffOfferVerdict verdict, String unavailable) {

    static StaffOfferApplication held(StaffOffer offer) {
        return new StaffOfferApplication(offer, false, StaffOfferVerdict.ELIGIBLE, null);
    }

    static StaffOfferApplication made(StaffOffer offer) {
        return new StaffOfferApplication(offer, true, StaffOfferVerdict.ELIGIBLE, null);
    }

    static StaffOfferApplication declined(StaffOfferVerdict verdict) {
        return new StaffOfferApplication(null, false, verdict, null);
    }

    static StaffOfferApplication unavailable(String reason) {
        return new StaffOfferApplication(null, false, StaffOfferVerdict.ELIGIBLE, reason);
    }
}

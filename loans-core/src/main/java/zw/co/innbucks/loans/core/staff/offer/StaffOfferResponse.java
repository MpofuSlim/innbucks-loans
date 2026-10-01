package zw.co.innbucks.loans.core.staff.offer;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.staff.StaffMember;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A pre-approved offer and where it stands. An ACTIVE offer past its expiry reads as EXPIRED, closed at its expiry,
 * whether or not a run has closed it yet.
 *
 * @param amount             the most they may borrow: their grade's limit when it was issued, or Credit's override
 * @param runId              the weekly run that issued it; absent for an offer made when the borrower applied
 * @param origin             RUN (a weekly run issued it) or APPLY (made when the borrower chose "Apply" in the
 *                           SuperApp)
 * @param gradeLimitChangeId the approved grade-limit change for their grade
 * @param limitOverrideId    the limit override the amount came from, when Credit set one
 * @param replacesOfferId    the open offer it replaced, when it was a refresh
 * @param closedReason       why it was WITHDRAWN, or the loan it was TAKEN_UP as
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffOfferResponse(
        Long id,
        String employeeNumber,
        String fullName,
        String grade,
        String scoreBand,
        BigDecimal amount,
        Long gradeLimitChangeId,
        Long limitOverrideId,
        LocalDate cycleStart,
        Long runId,
        StaffOfferOrigin origin,
        StaffOfferStatus status,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt,
        Long replacesOfferId,
        LocalDateTime closedAt,
        String closedReason) {

    static StaffOfferResponse of(StaffOffer offer, StaffMember member, LocalDateTime now) {
        boolean lapsed = offer.getStatus() == StaffOfferStatus.ACTIVE && !offer.getExpiresAt().isAfter(now);
        return new StaffOfferResponse(offer.getId(), member == null ? null : member.getEmployeeNumber(),
                member == null ? null : member.getFullName(), offer.getGrade(), offer.getScoreBand(), offer.getAmount(),
                offer.getGradeLimitChangeId(), offer.getLimitOverrideId(), offer.getCycleStart(), offer.getRunId(),
                offer.getOrigin(), lapsed ? StaffOfferStatus.EXPIRED : offer.getStatus(), offer.getIssuedAt(), offer.getExpiresAt(),
                offer.getReplacesOfferId(), lapsed ? offer.getExpiresAt() : offer.getClosedAt(),
                offer.getClosedReason());
    }
}

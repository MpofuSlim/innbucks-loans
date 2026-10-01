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
 * @param amount             the grade's limit when it was issued
 * @param gradeLimitChangeId the approved grade-limit change the amount comes from
 * @param replacesOfferId    the open offer it replaced, when it was a refresh
 * @param closedReason       why it was WITHDRAWN
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
        LocalDate cycleStart,
        Long runId,
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
                offer.getGradeLimitChangeId(), offer.getCycleStart(), offer.getRunId(),
                lapsed ? StaffOfferStatus.EXPIRED : offer.getStatus(), offer.getIssuedAt(), offer.getExpiresAt(),
                offer.getReplacesOfferId(), lapsed ? offer.getExpiresAt() : offer.getClosedAt(),
                offer.getClosedReason());
    }
}

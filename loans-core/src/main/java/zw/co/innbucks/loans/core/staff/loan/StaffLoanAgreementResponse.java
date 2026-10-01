package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.instrument.InstrumentType;

import java.time.LocalDateTime;

/**
 * The agreement a loan was accepted under, with its evidence (FR-SGL-027, FR-SGL-028). {@code intact} is false when the
 * text or the evidence no longer matches what was sealed at acceptance.
 */
public record StaffLoanAgreementResponse(InstrumentType instrumentType, int templateVersion, String title,
                                         String content, String contentSha256, String acceptedBy,
                                         LocalDateTime acceptedAt, String deviceId, String ipAddress,
                                         String forwardedFor, String userAgent, String authenticationMethod,
                                         String assertionId, String evidenceSha256, boolean intact) {
}

package zw.co.innbucks.loans.core.staff.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.borrower.AssertionRejectedException;
import zw.co.innbucks.loans.core.borrower.AssertionUses;
import zw.co.innbucks.loans.core.borrower.BorrowerAssertionUse;
import zw.co.innbucks.loans.core.borrower.BorrowerPhones;
import zw.co.innbucks.loans.core.borrower.BorrowerSignInUnavailableException;
import zw.co.innbucks.loans.core.borrower.MiddlewareAssertionVerifier;
import zw.co.innbucks.loans.core.borrower.NotOnStaffRegisterException;
import zw.co.innbucks.loans.core.borrower.VerifiedAssertion;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplate;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplateService;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.instrument.SigningContext;
import zw.co.innbucks.loans.core.instrument.StaffLoanTerms;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.offer.StaffOffer;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferApplication;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferAssessment;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunService;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferStatus;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferVerdict;
import zw.co.innbucks.loans.core.voucher.VoucherService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Staff Grocery Loan as a borrower takes it in the SuperApp (FR-SGL-025 to FR-SGL-031), all of it acting for the
 * staff member the borrower session names:
 * <ol>
 *   <li>{@link #home}: the tile, showing the loan they hold, an offer to take up, that they may apply, or why not;</li>
 *   <li>{@link #apply}: "Apply" without an offer, made on demand on the weekly run's terms;</li>
 *   <li>{@link #quote}: the disclosure for the amount they chose, and the agreement filled with it;</li>
 *   <li>{@link #accept}: accepting that agreement with a fresh PIN or biometric, which makes the loan.</li>
 * </ol>
 * Who may borrow is decided the same way at every step as by the weekly run ({@link StaffOfferVerdict}), so an offer
 * the borrower still holds is refused at acceptance if, say, they have since fallen into arrears (FR-SGL-013,
 * FR-SGL-014). A declined borrower is told why in plain words (FR-SGL-029). A loan accepted waits for disbursement
 * through the bank's system (BR.NET, FR-SGL-032), which pays GetMore and issues the voucher; nothing here moves money.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffLoanJourneyService {

    static final String ACCEPTED = "STAFF_LOAN_ACCEPTED";
    static final String ACCEPT_REFUSED = "STAFF_LOAN_ACCEPT_REFUSED";
    static final String DEVICE_HEADER = "X-Device-Id";
    /** Visible ASCII, as the signed instruments take it: an app's installation id, a hardware id. */
    private static final Pattern DEVICE_ID = Pattern.compile("[\\x21-\\x7E]{1,128}");
    private static final char SEPARATOR = '\u001F';
    /** The acceptance time as the seal writes it: UTC, always six decimals, as the column holds it. */
    private static final DateTimeFormatter SEALED_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

    private final StaffMemberRepository memberRepository;
    private final StaffOfferRepository offerRepository;
    private final StaffOfferRunService offerService;
    private final StaffLoanRepository loanRepository;
    private final StaffLoanAgreementRepository agreementRepository;
    private final StaffLoanPolicy policy;
    private final InstrumentTemplateService templateService;
    private final MiddlewareAssertionVerifier verifier;
    private final AssertionUses assertionUses;
    private final VoucherService voucherService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * The Staff Grocery Loan tile: the loan they hold, the offer they can take up, whether they may apply, or why not.
     */
    @Transactional(readOnly = true)
    public StaffLoanHome home(Long staffMemberId) {
        StaffMember member = member(staffMemberId);
        Optional<StaffLoan> held = loanRepository.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(member.getId(),
                StaffLoanStatus.OPEN);
        if (held.isPresent()) {
            return StaffLoanHome.holding(view(held.get()));
        }
        StaffOfferAssessment assessment = offerService.assess(member);
        if (assessment.verdict() != StaffOfferVerdict.ELIGIBLE) {
            return StaffLoanHome.unavailable(StaffLoanDecline.of(assessment.verdict()));
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        Optional<StaffOffer> offer = offerRepository.findByStaffMemberIdAndStatus(member.getId(),
                StaffOfferStatus.ACTIVE).filter(candidate -> candidate.isOpenAt(now));
        if (offer.isPresent()) {
            return StaffLoanHome.offered(offerView(offer.get()));
        }
        return assessment.unavailable() == null ? StaffLoanHome.mayApply()
                : StaffLoanHome.unavailable(StaffLoanDecline.TEMPORARILY_UNAVAILABLE);
    }

    /**
     * "Apply" (FR-SGL-025): the offer they hold, or one made now if they may be offered.
     *
     * @throws StaffLoanDeclinedException they may not borrow now, with the reason in plain words
     */
    public StaffLoanAppliedOffer apply(Long staffMemberId) {
        StaffOfferApplication application = offerService.apply(member(staffMemberId).getId());
        if (application.offer() == null) {
            throw new StaffLoanDeclinedException(application.unavailable() != null
                    ? StaffLoanDecline.TEMPORARILY_UNAVAILABLE : StaffLoanDecline.of(application.verdict()));
        }
        return new StaffLoanAppliedOffer(offerView(application.offer()), application.created());
    }

    /**
     * What borrowing {@code amount} from the offer means, shown before acceptance (FR-SGL-026), and the agreement to
     * accept, filled with it (FR-SGL-027). Changes nothing.
     *
     * @throws StaffLoanDeclinedException         the offer cannot be taken up, or they may not borrow now
     * @throws StaffLoanRequestInvalidException   the amount is not one the offer can be drawn in
     * @throws StaffLoanTermsUnavailableException no agreement has been published
     */
    @Transactional(readOnly = true)
    public StaffLoanQuote quote(Long staffMemberId, StaffLoanQuoteRequest request) {
        StaffMember member = member(staffMemberId);
        StaffOffer offer = offerToTakeUp(member, request.offerId(), marketTimeZone.nowUtc());
        requireEligible(member);
        requireAmount(offer, request.amount());
        LocalDate today = marketTimeZone.today();
        StaffLoanTerms.Signing terms = policy.signing(member, request.amount(), today);
        InstrumentTemplate template = agreementInForce();
        String content = StaffLoanTerms.render(template.getBody(), terms);
        return new StaffLoanQuote(offer.getId(), terms.amount(), terms.currency(), terms.interestRate(),
                money(BigDecimal.ZERO), money(BigDecimal.ZERO), terms.totalRepayable(), terms.repaymentDate(),
                collection(terms), terms.merchantName(), redemption(terms), terms.voucherValidityDays(),
                terms.unredeemedVoucherTerms(), new StaffLoanAgreementText(template.getVersion(), template.getTitle(),
                content, AuditService.sha256Hex(content)));
    }

    /**
     * Accepts the loan (FR-SGL-027, FR-SGL-028): the borrower took the quote's offer and amount, read its agreement,
     * and has just entered their PIN or used biometrics, which the middleware's fresh assertion proves. Everything is
     * checked again first: the offer is still open, they may still borrow (one loan at a time, no arrears), the
     * amount, and that the agreement is the one that would be signed now. Then, in one transaction, the assertion is
     * spent, the loan made AWAITING_DISBURSEMENT, the offer taken up, and the agreement recorded with its evidence.
     * Under the register's lock, so neither a second acceptance nor a register change lands in the middle.
     *
     * @throws StaffLoanDeclinedException         the offer cannot be taken up, or they may not borrow now
     * @throws StaffLoanRequestInvalidException   the amount, or the device id
     * @throws StaffLoanTermsUnavailableException no agreement has been published
     * @throws StaffLoanTermsChangedException     the agreement accepted is not the one that would be signed now
     * @throws AssertionRejectedException         the assertion is forged, expired, used, or another phone's
     * @throws StepUpRequiredException            it is genuine but not a PIN or biometric from just now
     */
    @Transactional
    public StaffLoanView accept(Long staffMemberId, AcceptStaffLoanRequest request, SigningContext signing) {
        String deviceId = signing.deviceId();
        if (deviceId == null || deviceId.isBlank()) {
            throw new StaffLoanRequestInvalidException(DEVICE_HEADER, "The device accepting the loan is required: send"
                    + " it in the " + DEVICE_HEADER + " header");
        }
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw new StaffLoanRequestInvalidException(DEVICE_HEADER, "The device id must be 1 to 128 visible"
                    + " characters");
        }
        memberRepository.lockRegister(StaffRegisterService.REGISTER_LOCK);
        StaffMember member = member(staffMemberId);
        LocalDateTime now = marketTimeZone.nowUtc().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = marketTimeZone.localDay(now);
        StaffOffer offer = offerToTakeUp(member, request.offerId(), now);
        requireEligible(member);
        requireAmount(offer, request.amount());
        StaffLoanTerms.Signing terms = policy.signing(member, request.amount(), today);
        InstrumentTemplate template = agreementInForce();
        String content = StaffLoanTerms.render(template.getBody(), terms);
        String contentSha256 = AuditService.sha256Hex(content);
        if (template.getVersion() != request.agreementVersion() || !contentSha256.equals(request.agreementSha256())) {
            log.info("Staff member {} accepted agreement v{} that is no longer what would be signed (v{} in force)",
                    member.getEmployeeNumber(), request.agreementVersion(), template.getVersion());
            throw new StaffLoanTermsChangedException();
        }
        VerifiedAssertion assertion = stepUp(member, request.assertion());

        String reference = String.format("SGL-%d-%06d", today.getYear(), loanRepository.nextReferenceNumber());
        String borrower = JwtService.BORROWER_USERNAME_PREFIX + member.getEmployeeNumber();
        StaffLoan loan = loanRepository.save(StaffLoan.builder()
                .reference(reference)
                .staffMemberId(member.getId())
                .offerId(offer.getId())
                .employeeNumber(member.getEmployeeNumber())
                .fullName(member.getFullName())
                .msisdn(member.getMsisdn())
                .grade(member.getGrade())
                .amount(terms.amount())
                .currency(terms.currency())
                .interestRate(terms.interestRate())
                .totalRepayable(terms.totalRepayable())
                .dueDate(terms.repaymentDate())
                .unredeemedVoucherTreatment(policy.unredeemedVoucherTreatment())
                .status(StaffLoanStatus.AWAITING_DISBURSEMENT)
                .acceptedAt(now)
                .build());
        offer.takeUp(now, reference);
        StaffLoanAgreement unsealed = StaffLoanAgreement.builder()
                .staffLoanId(loan.getId())
                .instrumentType(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT)
                .templateVersion(template.getVersion())
                .title(template.getTitle())
                .content(content)
                .contentSha256(contentSha256)
                .acceptedBy(borrower)
                .acceptedAt(now)
                .deviceId(deviceId)
                .ipAddress(Optional.ofNullable(clean(signing.ipAddress(), 64)).orElse("unknown"))
                .forwardedFor(clean(signing.forwardedFor(), 512))
                .userAgent(clean(signing.userAgent(), 512))
                .authenticationMethod(Optional.ofNullable(clean(String.join(" ", assertion.methods()), 64))
                        .orElse("unknown"))
                .assertionId(assertion.jti())
                .build();
        agreementRepository.save(unsealed.toBuilder().evidenceSha256(evidenceSha256(unsealed)).build());
        auditService.record(AuditLog.builder()
                .eventType(ACCEPTED)
                .entityType("STAFF_LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(borrower).channelUsed("superapp")
                .detail("reference:" + reference + ";offer:" + offer.getId() + ";amount:" + loan.getAmount()
                        + ";dueDate:" + loan.getDueDate() + ";agreement:v" + template.getVersion()
                        + ";assertion:" + assertion.jti()));
        log.info("Staff member {} accepted Staff Grocery Loan {} for {} {}, due {}", member.getEmployeeNumber(),
                reference, loan.getCurrency(), loan.getAmount(), loan.getDueDate());
        return view(loan);
    }

    /**
     * The open offer {@code offerId}, if it is the member's; any other answer is the same refusal, so ids reveal
     * nothing.
     */
    private StaffOffer offerToTakeUp(StaffMember member, Long offerId, LocalDateTime now) {
        return offerRepository.findById(offerId)
                .filter(offer -> offer.getStaffMemberId().equals(member.getId()) && offer.isOpenAt(now))
                .orElseThrow(() -> new StaffLoanDeclinedException(StaffLoanDecline.OFFER_NOT_AVAILABLE));
    }

    private void requireEligible(StaffMember member) {
        StaffOfferVerdict verdict = offerService.assess(member).verdict();
        if (verdict != StaffOfferVerdict.ELIGIBLE) {
            throw new StaffLoanDeclinedException(StaffLoanDecline.of(verdict));
        }
    }

    private void requireAmount(StaffOffer offer, BigDecimal amount) {
        policy.amountProblem(offer.getAmount(), amount).ifPresent(problem -> {
            throw new StaffLoanRequestInvalidException("amount", problem);
        });
    }

    private InstrumentTemplate agreementInForce() {
        return templateService.currentTemplate(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT)
                .orElseThrow(() -> {
                    log.error("A Staff Grocery Loan cannot be accepted: no STAFF_GROCERY_LOAN_AGREEMENT is published"
                            + " (POST /lending/v1/instrument-templates)");
                    return new StaffLoanTermsUnavailableException();
                });
    }

    /**
     * The middleware's assertion that the borrower has just entered their PIN or used biometrics, for their own phone,
     * spent so it approves nothing else. A refusal is audited, in its own transaction so it outlives the rollback.
     */
    private VerifiedAssertion stepUp(StaffMember member, String compact) {
        if (!verifier.isConfigured()) {
            throw new BorrowerSignInUnavailableException();
        }
        try {
            VerifiedAssertion assertion = verifier.verify(compact);
            if (!BorrowerPhones.asRegistered(assertion.phone()).map(member.getMsisdn()::equals).orElse(false)) {
                throw new AssertionRejectedException("assertion for another phone");
            }
            if (!verifier.isFreshStepUp(assertion)) {
                refused(member, "not a fresh PIN or biometric: " + String.join(",", assertion.methods()));
                throw new StepUpRequiredException();
            }
            assertionUses.spend(assertion, BorrowerAssertionUse.Purpose.STEP_UP, member.getId());
            return assertion;
        } catch (AssertionRejectedException rejected) {
            refused(member, "assertion rejected: " + rejected.getMessage());
            throw rejected;
        }
    }

    private void refused(StaffMember member, String reason) {
        log.warn("Staff Grocery Loan acceptance refused for {}: {}", member.getEmployeeNumber(), reason);
        auditService.record(AuditLog.builder()
                .eventType(ACCEPT_REFUSED)
                .entityType("STAFF_MEMBER").entityId(String.valueOf(member.getId()))
                .actorId(JwtService.BORROWER_USERNAME_PREFIX + member.getEmployeeNumber()).channelUsed("superapp")
                .detail("reason:" + reason));
    }

    private StaffMember member(Long staffMemberId) {
        return memberRepository.findById(staffMemberId).orElseThrow(NotOnStaffRegisterException::new);
    }

    private StaffLoanOfferView offerView(StaffOffer offer) {
        return new StaffLoanOfferView(offer.getId(), money(offer.getAmount()), policy.currency(),
                offer.getExpiresAt(), policy.drawRules(offer.getAmount()));
    }

    private StaffLoanView view(StaffLoan loan) {
        return new StaffLoanView(loan.getReference(), loan.getStatus(),
                StaffLoanView.message(loan.getStatus(), policy.merchantName()), loan.getAmount(), loan.getCurrency(),
                loan.getTotalRepayable(), loan.outstanding(), loan.getDueDate(), policy.merchantName(),
                loan.getAcceptedAt(), loan.getStatus() == StaffLoanStatus.AWAITING_DISBURSEMENT ? null
                        : voucherService.forBorrower(loan.getStaffMemberId(), loan.getReference()).orElse(null));
    }

    private static String collection(StaffLoanTerms.Signing terms) {
        return String.format("%s %s will be collected automatically from your salary on %s. There is nothing for you"
                + " to pay yourself.", terms.currency(), terms.totalRepayable(), terms.repaymentDate());
    }

    private static String redemption(StaffLoanTerms.Signing terms) {
        return String.format("You receive a voucher for %s %s to spend at %s only, valid for %d days. The loan is never"
                        + " paid into your wallet.", terms.currency(), terms.amount(), terms.merchantName(),
                terms.voucherValidityDays());
    }

    /**
     * The seal: a SHA-256 over every field of the record, the text by its own fingerprint, joined by U+001F, absent as
     * empty: staff loan id, instrument type, template version, title, content SHA-256, accepted by, accepted at (UTC,
     * {@code yyyy-MM-ddTHH:mm:ss.SSSSSS}), device id, IP address, forwarded for, user agent, authentication method,
     * assertion id.
     */
    static String evidenceSha256(StaffLoanAgreement agreement) {
        return AuditService.sha256Hex(String.join(String.valueOf(SEPARATOR),
                String.valueOf(agreement.getStaffLoanId()),
                agreement.getInstrumentType().name(),
                String.valueOf(agreement.getTemplateVersion()),
                agreement.getTitle(),
                agreement.getContentSha256(),
                agreement.getAcceptedBy(),
                SEALED_TIME.format(agreement.getAcceptedAt()),
                agreement.getDeviceId(),
                agreement.getIpAddress(),
                Optional.ofNullable(agreement.getForwardedFor()).orElse(""),
                Optional.ofNullable(agreement.getUserAgent()).orElse(""),
                agreement.getAuthenticationMethod(),
                agreement.getAssertionId()));
    }

    /** Control characters removed, cut to the column's size; blank is absent. */
    private static String clean(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String printable = value.replaceAll("\\p{Cntrl}", "").strip();
        if (printable.isEmpty()) {
            return null;
        }
        return printable.length() > maxLength ? printable.substring(0, maxLength) : printable;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}

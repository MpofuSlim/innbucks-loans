package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.borrower.AssertionRejectedException;
import zw.co.innbucks.loans.core.borrower.AssertionUses;
import zw.co.innbucks.loans.core.borrower.BorrowerAssertionUse;
import zw.co.innbucks.loans.core.borrower.BorrowerProperties;
import zw.co.innbucks.loans.core.borrower.BorrowerSignInUnavailableException;
import zw.co.innbucks.loans.core.borrower.MiddlewareAssertionVerifier;
import zw.co.innbucks.loans.core.borrower.NotOnStaffRegisterException;
import zw.co.innbucks.loans.core.borrower.TestAssertionSigner;
import zw.co.innbucks.loans.core.borrower.VerifiedAssertion;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplate;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplateService;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.instrument.SigningContext;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.StaffLoanMerchantService;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffGradeLimit;
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
import zw.co.innbucks.loans.core.voucher.BorrowerVoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SuperApp journey (FR-SGL-025 to FR-SGL-031) for Chipo Banda (E1012), holding offer 31 for USD 300.00, on
 * Thursday 1 October 2026 at 09:10:41 in Harare. Assertions are signed by staging's test signer and checked by the real
 * verifier, so the step-up rules are the production ones.
 */
class StaffLoanJourneyServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T07:10:41Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 10, 1, 7, 10, 41);
    private static final MarketTimeZone ZW = new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC));
    private static final StaffGradeLimit C4 = new StaffGradeLimit(1L, "C4", "Band C", new BigDecimal("300.00"),
            LocalDate.of(2026, 9, 1), "credit2", LocalDateTime.of(2026, 8, 31, 8, 0));
    private static final String BODY = "I, {{borrowerName}}, borrow {{currency}} {{amount}} and repay"
            + " {{totalRepayable}} on {{repaymentDate}}.";

    private final StaffMember chipo = StaffMember.builder().id(2L).employeeNumber("E1012").fullName("Chipo Banda")
            .nationalId("632223334C55").msisdn("263773456789").grade("C4").department("Treasury")
            .employmentStatus(StaffEmploymentStatus.ACTIVE).build();
    private final StaffOffer offer = offer(31L, chipo.getId(), "300.00", NOW_UTC.plusDays(4));

    private final StaffMemberRepository members = mock(StaffMemberRepository.class);
    private final StaffOfferRepository offers = mock(StaffOfferRepository.class);
    private final StaffOfferRunService offerService = mock(StaffOfferRunService.class);
    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    private final StaffLoanAgreementRepository agreements = mock(StaffLoanAgreementRepository.class);
    private final InstrumentTemplateService templates = mock(InstrumentTemplateService.class);
    private final AssertionUses assertionUses = mock(AssertionUses.class);
    private final VoucherService vouchers = mock(VoucherService.class);
    private final AuditService audit = mock(AuditService.class);
    private final BorrowerProperties borrowerProperties = testAssertions();
    private final TestAssertionSigner signer = new TestAssertionSigner(borrowerProperties, ZW);
    private final StaffLoanPolicy policy = new StaffLoanPolicy(new StaffLoanProperties(), new VoucherProperties());
    private final StaffLoanMerchantService merchants = mock(StaffLoanMerchantService.class);
    private Merchant merchant = merchant(3L, "getmore-groceries", "GetMore Groceries");
    private final List<StaffLoan> saved = new ArrayList<>();
    private StaffOfferVerdict verdict = StaffOfferVerdict.ELIGIBLE;
    private String unavailable;
    private final StaffLoanJourneyService journey = new StaffLoanJourneyService(members, offers, offerService, loans,
            agreements, policy, merchants, templates, new MiddlewareAssertionVerifier(borrowerProperties, ZW, signer),
            assertionUses, vouchers, audit, ZW);

    {
        when(merchants.current()).thenAnswer(i -> Optional.ofNullable(merchant));
        when(members.findById(2L)).thenReturn(Optional.of(chipo));
        when(offers.findById(31L)).thenReturn(Optional.of(offer));
        when(offers.findByStaffMemberIdAndStatus(2L, StaffOfferStatus.ACTIVE)).thenReturn(Optional.of(offer));
        when(offerService.assess(any())).thenAnswer(i -> new StaffOfferAssessment(verdict, unavailable, C4, null));
        when(templates.currentTemplate(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT))
                .thenReturn(Optional.of(template(1)));
        when(loans.nextReferenceNumber()).thenReturn(143L);
        when(loans.save(any())).thenAnswer(i -> {
            StaffLoan loan = i.getArgument(0);
            StaffLoan withId = copyWithId(loan, 143L);
            saved.add(withId);
            return withId;
        });
        when(loans.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(anyLong(), any())).thenReturn(Optional.empty());
    }

    // --- The tile ---

    @Test
    @DisplayName("holding an open offer: the tile shows it, with the amounts it can be drawn in")
    void homeShowsTheOffer() {
        StaffLoanHome home = journey.home(2L);

        assertThat(home.loan()).isNull();
        assertThat(home.canApply()).isFalse();
        assertThat(home.unavailable()).isNull();
        assertThat(home.offer()).isEqualTo(new StaffLoanOfferView(31L, new BigDecimal("300.00"), "USD",
                NOW_UTC.plusDays(4), new DrawRules(new BigDecimal("10.00"), new BigDecimal("5.00"),
                new BigDecimal("300.00"), "USD")));
    }

    @Test
    @DisplayName("no open offer: they may apply; offers not makeable just now: not available, in plain words")
    void homeOffersToApply() {
        when(offers.findByStaffMemberIdAndStatus(2L, StaffOfferStatus.ACTIVE)).thenReturn(Optional.empty());
        assertThat(journey.home(2L)).isEqualTo(new StaffLoanHome(null, null, true, null));

        offer.setExpiresAt(NOW_UTC);
        when(offers.findByStaffMemberIdAndStatus(2L, StaffOfferStatus.ACTIVE)).thenReturn(Optional.of(offer));
        assertThat(journey.home(2L).canApply()).as("an offer at its expiry is not open").isTrue();

        unavailable = "The staff register was last reconciled ...";
        assertThat(journey.home(2L).unavailable()).isEqualTo(new StaffLoanUnavailable(
                StaffLoanDecline.TEMPORARILY_UNAVAILABLE, StaffLoanDecline.TEMPORARILY_UNAVAILABLE.message()));
    }

    @ParameterizedTest
    @EnumSource(value = StaffOfferVerdict.class, mode = EnumSource.Mode.EXCLUDE, names = "ELIGIBLE")
    @DisplayName("a member who may not borrow is told why in plain words, and is not shown the offer they hold")
    void homeExplains(StaffOfferVerdict reason) {
        verdict = reason;

        StaffLoanHome home = journey.home(2L);

        assertThat(home.offer()).isNull();
        assertThat(home.canApply()).isFalse();
        assertThat(home.unavailable().reason()).isEqualTo(StaffLoanDecline.of(reason));
        assertThat(home.unavailable().message()).doesNotContain("override", "reconciliation", "verdict", "score");
    }

    @Test
    @DisplayName("holding a loan: the tile shows it, with its voucher once paid out")
    void homeShowsTheLoan() {
        StaffLoan awaiting = loan(StaffLoanStatus.AWAITING_DISBURSEMENT);
        when(loans.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(eq(2L), any())).thenReturn(Optional.of(awaiting));

        StaffLoanView view = journey.home(2L).loan();

        assertThat(view.reference()).isEqualTo("SGL-2026-000143");
        assertThat(view.statusMessage()).isEqualTo("Your loan is approved. Your voucher will be sent to you once the"
                + " loan is paid out to GetMore Groceries.");
        assertThat(view.outstandingBalance()).isEqualByComparingTo("300.00");
        assertThat(view.repaymentDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(view.voucher()).isNull();
        verify(vouchers, never()).forBorrower(anyLong(), any());

        BorrowerVoucherResponse voucher = new BorrowerVoucherResponse(VoucherStatus.ISSUED,
                new BigDecimal("300.00"), new BigDecimal("300.00"), "USD", NOW_UTC.plusDays(30),
                "**** **** **** 8406", "4829 1506 7331 8406", "4829150673318406");
        when(vouchers.forBorrower(2L, "SGL-2026-000143")).thenReturn(Optional.of(voucher));
        when(loans.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(eq(2L), any()))
                .thenReturn(Optional.of(loan(StaffLoanStatus.DISBURSED)));
        assertThat(journey.home(2L).loan().voucher()).isEqualTo(voucher);
    }

    @Test
    @DisplayName("a loan keeps the merchant it was accepted for, whichever is set for new loans since")
    void loanKeepsItsMerchant() {
        merchant = merchant(9L, "pick-n-pay", "Pick n Pay");
        when(loans.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(eq(2L), any()))
                .thenReturn(Optional.of(loan(StaffLoanStatus.DISBURSED)));

        StaffLoanView view = journey.home(2L).loan();

        assertThat(view.merchantName()).isEqualTo("GetMore Groceries");
        assertThat(view.statusMessage()).as("paid out, voucher not issued yet")
                .isEqualTo("Your loan has been paid out to GetMore Groceries. Your voucher will be sent to you.");
    }

    @Test
    @DisplayName("with no merchant set, a borrower without a loan is told loans are unavailable, and none can be taken")
    void noMerchantSet() {
        merchant = null;

        assertThat(journey.home(2L).unavailable().reason()).isEqualTo(StaffLoanDecline.TEMPORARILY_UNAVAILABLE);
        assertThatThrownBy(() -> journey.apply(2L)).isInstanceOf(StaffLoanDeclinedException.class)
                .hasMessage(StaffLoanDecline.TEMPORARILY_UNAVAILABLE.message());
        assertThatThrownBy(() -> journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300"))))
                .isInstanceOf(StaffLoanDeclinedException.class)
                .hasMessage(StaffLoanDecline.TEMPORARILY_UNAVAILABLE.message());
        verify(offerService, never()).apply(anyLong());
        assertThat(saved).isEmpty();
    }

    // --- Their loans ---

    @Test
    @DisplayName("their loans: every one, newest first, as the tile shows a loan; vouchers looked up once paid out")
    void history() {
        StaffLoan repaid = loan(StaffLoanStatus.REPAID);
        StaffLoan cancelled = loan(StaffLoanStatus.CANCELLED);
        when(loans.findByStaffMemberId(eq(2L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cancelled, repaid)));
        BorrowerVoucherResponse lapsed = new BorrowerVoucherResponse(VoucherStatus.EXPIRED, new BigDecimal("300.00"),
                new BigDecimal("120.00"), "USD", NOW_UTC.plusDays(30), "**** **** **** 8406", null, null);
        when(vouchers.forBorrower(2L, "SGL-2026-000143")).thenReturn(Optional.of(lapsed));

        List<StaffLoanView> views = journey.loans(2L, PageRequest.of(1, 5)).getContent();

        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
        verify(loans).findByStaffMemberId(eq(2L), asked.capture());
        assertThat(asked.getValue().getPageNumber()).isEqualTo(1);
        assertThat(asked.getValue().getPageSize()).isEqualTo(5);
        assertThat(asked.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
        assertThat(views).extracting(StaffLoanView::status)
                .containsExactly(StaffLoanStatus.CANCELLED, StaffLoanStatus.REPAID);
        assertThat(views.get(0).statusMessage())
                .isEqualTo("This loan was cancelled before it was paid out. Nothing is owed.");
        assertThat(views.get(0).outstandingBalance()).isEqualByComparingTo("0.00");
        assertThat(views.get(0).voucher()).isNull();
        assertThat(views.get(1).statusMessage()).isEqualTo("Repaid in full. Thank you.");
        assertThat(views.get(1).voucher()).isEqualTo(lapsed);
        verify(vouchers, times(1)).forBorrower(anyLong(), any());
    }

    @Test
    @DisplayName("their loans: someone no longer on the register is refused, never shown another's")
    void historyOfSomeoneUnknown() {
        assertThatThrownBy(() -> journey.loans(9L, PageRequest.of(0, 20)))
                .isInstanceOf(NotOnStaffRegisterException.class);
        verify(loans, never()).findByStaffMemberId(anyLong(), any());
    }

    // --- Apply ---

    @Test
    @DisplayName("apply: the offer made or held; a decline in plain words")
    void apply() {
        when(offerService.apply(2L)).thenReturn(application(offer, true, StaffOfferVerdict.ELIGIBLE, null));
        assertThat(journey.apply(2L).created()).isTrue();
        assertThat(journey.apply(2L).offer().offerId()).isEqualTo(31L);

        when(offerService.apply(2L)).thenReturn(application(null, false, StaffOfferVerdict.ACTIVE_LOAN, null));
        assertThatThrownBy(() -> journey.apply(2L)).isInstanceOf(StaffLoanDeclinedException.class)
                .satisfies(e -> assertThat(((StaffLoanDeclinedException) e).decline())
                        .isEqualTo(StaffLoanDecline.HAS_ACTIVE_LOAN))
                .hasMessage("You already have a Staff Grocery Loan. You can take another once it has been repaid.");

        when(offerService.apply(2L)).thenReturn(application(null, false, StaffOfferVerdict.ELIGIBLE, "stale"));
        assertThatThrownBy(() -> journey.apply(2L)).isInstanceOf(StaffLoanDeclinedException.class)
                .hasMessage(StaffLoanDecline.TEMPORARILY_UNAVAILABLE.message());
    }

    // --- The quote ---

    @Test
    @DisplayName("the quote names the merchant set now; changing it changes the agreement, so an old quote is refused")
    void quoteNamesTheMerchantSetNow() {
        StaffLoanQuote getMore = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));
        when(templates.currentTemplate(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT)).thenReturn(Optional.of(
                InstrumentTemplate.builder().id(2L).instrumentType(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT)
                        .version(1).title("Staff Grocery Loan Agreement")
                        .body("I, {{borrowerName}}, spend it at {{merchantName}}.").publishedBy("admin")
                        .publishedAt(LocalDateTime.of(2026, 9, 30, 8, 0)).build()));
        StaffLoanQuote atGetMore = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));
        merchant = merchant(9L, "pick-n-pay", "Pick n Pay");

        StaffLoanQuote atPickNPay = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));

        assertThat(getMore.merchantName()).isEqualTo("GetMore Groceries");
        assertThat(atPickNPay.merchantName()).isEqualTo("Pick n Pay");
        assertThat(atPickNPay.agreement().content()).isEqualTo("I, Chipo Banda, spend it at Pick n Pay.");
        assertThatThrownBy(() -> journey.accept(2L, request(atGetMore, signer.sign("+263773456789", List.of("pin"))
                .assertion()), signing("a1f3c9e2-7b4d"))).isInstanceOf(StaffLoanTermsChangedException.class);
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("the quote discloses the terms (FR-SGL-026) and fills the agreement with them, fingerprinted")
    void quote() {
        StaffLoanQuote quote = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("250")));

        assertThat(quote.amount()).isEqualByComparingTo("250.00");
        assertThat(quote.interestRate()).isZero();
        assertThat(quote.interest()).isEqualByComparingTo("0.00");
        assertThat(quote.fees()).isEqualByComparingTo("0.00");
        assertThat(quote.totalRepayable()).isEqualByComparingTo("250.00");
        assertThat(quote.repaymentDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(quote.collection()).isEqualTo("USD 250.00 will be collected automatically from your salary on"
                + " 2026-11-20. There is nothing for you to pay yourself.");
        assertThat(quote.redemption()).isEqualTo("You receive a voucher for USD 250.00 to spend at GetMore Groceries"
                + " only, valid for 30 days. The loan is never paid into your wallet.");
        assertThat(quote.unredeemedVoucher()).isEqualTo(UnredeemedVoucherTreatment.DEBT_STANDS.terms());
        assertThat(quote.agreement().version()).isEqualTo(1);
        assertThat(quote.agreement().content())
                .isEqualTo("I, Chipo Banda, borrow USD 250.00 and repay 250.00 on 2026-11-20.");
        assertThat(quote.agreement().contentSha256()).isEqualTo(AuditService.sha256Hex(quote.agreement().content()));
    }

    @Test
    @DisplayName("an offer that is not theirs, not open, or unknown is one refusal; so is a member who may not borrow")
    void quoteRefusals() {
        StaffOffer someoneElses = offer(32L, 7L, "300.00", NOW_UTC.plusDays(4));
        when(offers.findById(32L)).thenReturn(Optional.of(someoneElses));
        StaffOffer taken = offer(33L, 2L, "300.00", NOW_UTC.plusDays(4));
        taken.setStatus(StaffOfferStatus.TAKEN_UP);
        when(offers.findById(33L)).thenReturn(Optional.of(taken));
        for (long offerId : new long[]{32L, 33L, 99L}) {
            assertThatThrownBy(() -> journey.quote(2L, new StaffLoanQuoteRequest(offerId, new BigDecimal("300"))))
                    .isInstanceOf(StaffLoanDeclinedException.class)
                    .hasMessage(StaffLoanDecline.OFFER_NOT_AVAILABLE.message());
        }

        verdict = StaffOfferVerdict.ARREARS;
        assertThatThrownBy(() -> journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300"))))
                .isInstanceOf(StaffLoanDeclinedException.class)
                .hasMessage(StaffLoanDecline.OVERDUE_BALANCE.message());
    }

    @Test
    @DisplayName("an amount the offer cannot be drawn in is a field error; no published agreement is a 503")
    void quoteAmountAndTerms() {
        assertThatThrownBy(() -> journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("301"))))
                .isInstanceOf(StaffLoanRequestInvalidException.class)
                .satisfies(e -> assertThat(((StaffLoanRequestInvalidException) e).getFields()).containsKey("amount"));

        when(templates.currentTemplate(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300"))))
                .isInstanceOf(StaffLoanTermsUnavailableException.class);
    }

    // --- Accepting ---

    @Test
    @DisplayName("accepting makes the loan as quoted, takes up the offer, spends the assertion, seals the agreement")
    void accept() {
        StaffLoanQuote quote = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));

        StaffLoanView view = journey.accept(2L, request(quote, signer.sign("+263773456789", List.of("pin"))
                .assertion()), signing("a1f3c9e2-7b4d"));

        assertThat(saved).singleElement().satisfies(loan -> {
            assertThat(loan.getReference()).isEqualTo("SGL-2026-000143");
            assertThat(loan.getStaffMemberId()).isEqualTo(2L);
            assertThat(loan.getOfferId()).isEqualTo(31L);
            assertThat(loan.getEmployeeNumber()).isEqualTo("E1012");
            assertThat(loan.getMsisdn()).isEqualTo("263773456789");
            assertThat(loan.getAmount()).isEqualByComparingTo("300.00");
            assertThat(loan.getInterestRate()).isZero();
            assertThat(loan.getTotalRepayable()).isEqualByComparingTo("300.00");
            assertThat(loan.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
            assertThat(loan.getUnredeemedVoucherTreatment()).isEqualTo(UnredeemedVoucherTreatment.DEBT_STANDS);
            assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
            assertThat(loan.getAcceptedAt()).isEqualTo(NOW_UTC);
            assertThat(loan.getMerchant().getMerchantCode()).isEqualTo("getmore-groceries");
        });
        assertThat(view.status()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(view.merchantName()).isEqualTo("GetMore Groceries");
        assertThat(offer.getStatus()).isEqualTo(StaffOfferStatus.TAKEN_UP);
        assertThat(offer.getClosedReason()).isEqualTo("Taken up as SGL-2026-000143");

        ArgumentCaptor<VerifiedAssertion> spent = ArgumentCaptor.forClass(VerifiedAssertion.class);
        verify(assertionUses).spend(spent.capture(), eq(BorrowerAssertionUse.Purpose.STEP_UP), eq(2L));
        verify(members).lockRegister(StaffRegisterService.REGISTER_LOCK);

        ArgumentCaptor<StaffLoanAgreement> agreement = ArgumentCaptor.forClass(StaffLoanAgreement.class);
        verify(agreements).save(agreement.capture());
        assertThat(agreement.getValue()).satisfies(a -> {
            assertThat(a.getStaffLoanId()).isEqualTo(143L);
            assertThat(a.getInstrumentType()).isEqualTo(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT);
            assertThat(a.getTemplateVersion()).isEqualTo(1);
            assertThat(a.getContent()).isEqualTo(quote.agreement().content());
            assertThat(a.getContentSha256()).isEqualTo(quote.agreement().contentSha256());
            assertThat(a.getAcceptedBy()).isEqualTo("borrower:E1012");
            assertThat(a.getAcceptedAt()).isEqualTo(NOW_UTC);
            assertThat(a.getDeviceId()).isEqualTo("a1f3c9e2-7b4d");
            assertThat(a.getIpAddress()).isEqualTo("10.0.4.17");
            assertThat(a.getForwardedFor()).isEqualTo("196.27.112.45");
            assertThat(a.getAuthenticationMethod()).isEqualTo("pin");
            assertThat(a.getAssertionId()).isEqualTo(spent.getValue().jti());
            assertThat(a.getEvidenceSha256()).isEqualTo(StaffLoanJourneyService.evidenceSha256(a));
        });
        assertThat(events()).containsExactly(StaffLoanJourneyService.ACCEPTED);
    }

    @Test
    @DisplayName("no device, or a device id that is not visible characters: a field error before anything is read")
    void acceptNeedsTheDevice() {
        StaffLoanQuote quote = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));
        String assertion = signer.sign("+263773456789", List.of("pin")).assertion();
        for (String device : new String[]{null, " ", "has space", "x".repeat(129)}) {
            assertThatThrownBy(() -> journey.accept(2L, request(quote, assertion), signing(device)))
                    .isInstanceOf(StaffLoanRequestInvalidException.class)
                    .satisfies(e -> assertThat(((StaffLoanRequestInvalidException) e).getFields())
                            .containsKey("X-Device-Id"));
        }
        verify(members, never()).lockRegister(anyLong());
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("an agreement version or text other than the one in force now is refused: they must read it again")
    void acceptRequiresTheAgreementInForce() {
        StaffLoanQuote quote = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));
        String assertion = signer.sign("+263773456789", List.of("pin")).assertion();

        assertThatThrownBy(() -> journey.accept(2L, new AcceptStaffLoanRequest(31L, new BigDecimal("300"), 1,
                "0".repeat(64), assertion), signing("device-1"))).isInstanceOf(StaffLoanTermsChangedException.class);
        assertThatThrownBy(() -> journey.accept(2L, new AcceptStaffLoanRequest(31L, new BigDecimal("250"), 1,
                quote.agreement().contentSha256(), assertion), signing("device-1")))
                .as("the 300 agreement for a 250 loan").isInstanceOf(StaffLoanTermsChangedException.class);
        when(templates.currentTemplate(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT))
                .thenReturn(Optional.of(template(2)));
        assertThatThrownBy(() -> journey.accept(2L, request(quote, assertion), signing("device-1")))
                .isInstanceOf(StaffLoanTermsChangedException.class);
        assertThat(saved).isEmpty();
        verify(assertionUses, never()).spend(any(), any(), any());
    }

    @Test
    @DisplayName("an assertion for another phone, forged, or replayed is rejected and audited; nothing is made")
    void acceptRejectsTheWrongAssertion() {
        StaffLoanQuote quote = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));

        assertThatThrownBy(() -> journey.accept(2L, request(quote, signer.sign("+263771112223", List.of("pin"))
                .assertion()), signing("device-1"))).isInstanceOf(AssertionRejectedException.class);
        assertThatThrownBy(() -> journey.accept(2L, request(quote, "eyJhbGciOiJub25lIn0.e30."), signing("device-1")))
                .isInstanceOf(AssertionRejectedException.class);
        doThrow(new AssertionRejectedException("replayed jti")).when(assertionUses).spend(any(), any(), any());
        assertThatThrownBy(() -> journey.accept(2L, request(quote, signer.sign("+263773456789", List.of("pin"))
                .assertion()), signing("device-1"))).isInstanceOf(AssertionRejectedException.class);

        assertThat(saved).isEmpty();
        assertThat(offer.getStatus()).isEqualTo(StaffOfferStatus.ACTIVE);
        assertThat(events()).containsOnly(StaffLoanJourneyService.ACCEPT_REFUSED).hasSize(3);
    }

    @Test
    @DisplayName("a genuine assertion that is not a PIN or biometric (an OTP) asks for the step-up, spending nothing")
    void acceptNeedsAFreshStepUp() {
        StaffLoanQuote quote = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));

        assertThatThrownBy(() -> journey.accept(2L, request(quote, signer.sign("0773456789", List.of("otp"))
                .assertion()), signing("device-1"))).isInstanceOf(StepUpRequiredException.class)
                .hasMessage("Confirm with your PIN or biometrics to accept the loan.");
        verify(assertionUses, never()).spend(any(), any(), any());
        assertThat(saved).isEmpty();
        assertThat(events()).containsExactly(StaffLoanJourneyService.ACCEPT_REFUSED);
    }

    @Test
    @DisplayName("acceptance re-checks the borrower: one loan at a time (FR-SGL-013), no arrears (FR-SGL-014)")
    void acceptRechecksTheBorrower() {
        StaffLoanQuote quote = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));
        String assertion = signer.sign("+263773456789", List.of("pin")).assertion();

        verdict = StaffOfferVerdict.ACTIVE_LOAN;
        assertThatThrownBy(() -> journey.accept(2L, request(quote, assertion), signing("device-1")))
                .isInstanceOf(StaffLoanDeclinedException.class)
                .hasMessage(StaffLoanDecline.HAS_ACTIVE_LOAN.message());
        verdict = StaffOfferVerdict.ARREARS;
        assertThatThrownBy(() -> journey.accept(2L, request(quote, assertion), signing("device-1")))
                .isInstanceOf(StaffLoanDeclinedException.class)
                .hasMessage(StaffLoanDecline.OVERDUE_BALANCE.message());
        verify(assertionUses, never()).spend(any(), any(), any());
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("with no middleware key there is nothing to check a step-up against: 503, not a rejection")
    void acceptWithoutAKey() {
        BorrowerProperties off = new BorrowerProperties();
        StaffLoanJourneyService unconfigured = new StaffLoanJourneyService(members, offers, offerService, loans,
                agreements, policy, merchants, templates,
                new MiddlewareAssertionVerifier(off, ZW, new TestAssertionSigner(off, ZW)),
                assertionUses, vouchers, audit, ZW);
        StaffLoanQuote quote = journey.quote(2L, new StaffLoanQuoteRequest(31L, new BigDecimal("300")));

        assertThatThrownBy(() -> unconfigured.accept(2L, request(quote, "x"), signing("device-1")))
                .isInstanceOf(BorrowerSignInUnavailableException.class);
    }

    // --- Fixtures ---

    private static AcceptStaffLoanRequest request(StaffLoanQuote quote, String assertion) {
        return new AcceptStaffLoanRequest(quote.offerId(), quote.amount(), quote.agreement().version(),
                quote.agreement().contentSha256(), assertion);
    }

    private static SigningContext signing(String deviceId) {
        return new SigningContext(deviceId, "10.0.4.17", "196.27.112.45", "InnBucks/2.4.1 (Android 14)", "pin", null);
    }

    private static StaffOffer offer(long id, long memberId, String amount, LocalDateTime expiresAt) {
        return StaffOffer.builder().id(id).staffMemberId(memberId).runId(1L).cycleStart(LocalDate.of(2026, 9, 28))
                .grade("C4").scoreBand("Band C").gradeLimitChangeId(1L).amount(new BigDecimal(amount))
                .issuedAt(LocalDateTime.of(2026, 9, 28, 6, 0)).expiresAt(expiresAt).status(StaffOfferStatus.ACTIVE)
                .build();
    }

    private static InstrumentTemplate template(int version) {
        return InstrumentTemplate.builder().id((long) version)
                .instrumentType(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT)
                .version(version).title("Staff Grocery Loan Agreement").body(BODY).publishedBy("admin")
                .publishedAt(LocalDateTime.of(2026, 9, 30, 8, 0)).build();
    }

    private StaffLoan loan(StaffLoanStatus status) {
        return StaffLoan.builder().id(143L).reference("SGL-2026-000143").staffMemberId(2L).offerId(31L)
                .employeeNumber("E1012").fullName("Chipo Banda").msisdn("263773456789").grade("C4")
                .amount(new BigDecimal("300.00")).currency("USD").interestRate(BigDecimal.ZERO)
                .totalRepayable(new BigDecimal("300.00")).dueDate(LocalDate.of(2026, 11, 20))
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS).status(status)
                .acceptedAt(NOW_UTC).merchant(merchant(3L, "getmore-groceries", "GetMore Groceries")).build();
    }

    private static Merchant merchant(long id, String code, String name) {
        Merchant merchant = Merchant.builder().merchantCode(code).companyName(name)
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).staffLoanMerchant(true).build();
        merchant.setId(id);
        return merchant;
    }

    private static StaffLoan copyWithId(StaffLoan loan, long id) {
        return StaffLoan.builder().id(id).reference(loan.getReference()).staffMemberId(loan.getStaffMemberId())
                .offerId(loan.getOfferId()).employeeNumber(loan.getEmployeeNumber()).fullName(loan.getFullName())
                .msisdn(loan.getMsisdn()).grade(loan.getGrade()).merchant(loan.getMerchant()).amount(loan.getAmount())
                .currency(loan.getCurrency())
                .interestRate(loan.getInterestRate()).totalRepayable(loan.getTotalRepayable())
                .dueDate(loan.getDueDate()).unredeemedVoucherTreatment(loan.getUnredeemedVoucherTreatment())
                .status(loan.getStatus()).acceptedAt(loan.getAcceptedAt()).build();
    }

    private static StaffOfferApplication application(StaffOffer offer, boolean created, StaffOfferVerdict verdict,
                                                     String unavailable) {
        return new StaffOfferApplication(offer, created, verdict, unavailable);
    }

    private List<String> events() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit, atLeastOnce()).record(captor.capture());
        return captor.getAllValues().stream().map(builder -> builder.build().getEventType()).toList();
    }

    private static BorrowerProperties testAssertions() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            BorrowerProperties properties = new BorrowerProperties();
            properties.getTestAssertions().setEnabled(true);
            properties.getTestAssertions().setApiKey("staging-test-assertions-key-0123456789abcdef");
            properties.getTestAssertions().setPrivateKey(Base64.getEncoder().encodeToString(
                    generator.generateKeyPair().getPrivate().getEncoded()));
            return properties;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package zw.co.innbucks.loans.core.loan;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import zw.co.innbucks.loans.core.TextUtils;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.channel.ChannelRepository;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.PendingApplicationException;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.LoanDocumentService;
import zw.co.innbucks.loans.core.files.DecodedFile;
import zw.co.innbucks.loans.core.instrument.InstrumentPreview;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplate;
import zw.co.innbucks.loans.core.instrument.SignedInstrumentService;
import zw.co.innbucks.loans.core.instrument.SigningContext;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.parameter.ParameterService;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaround;
import zw.co.innbucks.loans.core.turnaround.CreditTurnarounds;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.math.BigDecimal.ONE;
import static org.springframework.data.jpa.domain.Specification.where;
import static zw.co.innbucks.loans.core.MsisdnUtils.formatMsisdnInternational;
import static zw.co.innbucks.loans.core.loan.LoanParameterNames.*;
import static zw.co.innbucks.loans.core.loan.LoanSpecification.*;
import static zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl.maskEcNumber;

@Slf4j
@RequiredArgsConstructor
@Service
public class LoanServiceImpl implements LoanService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final String EC_NUMBER_REGEX_FORMAT = "^[0-9]{7}[a-zA-Z]$";
    private final LoanRepository loanRepository;
    private final ParameterService parameterService;
    private final LoanMapper loanMapper;
    private final AuthService authService;
    private final MerchantRepository merchantRepository;
    private final ChannelRepository channelRepository;
    private final Validator validator;
    private final MarketTimeZone marketTimeZone;
    private final LoanDocumentService loanDocumentService;
    private final PayslipFraudDetector payslipFraudDetector;
    private final PayslipReviewService payslipReviewService;
    private final SignedInstrumentService signedInstrumentService;
    private final LoanNotificationService loanNotificationService;
    private final CreditTurnarounds creditTurnarounds;

    /** Newest first, id as the tie-break: a sort on a non-unique column alone would let pages repeat or skip rows. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdDate"), Sort.Order.desc("id"));

    @Override
    public SalesSummaryResponse getSalesSummary(Long userId, LocalDate fromDate, LocalDate toDate) {
        return loanRepository.salesSummary(userId, atStartOfDay(fromDate), atEndOfDay(toDate));
    }

    @Override
    public Page<LoanSummaryResponse> findLoans(LoanSearchCriteria criteria, LoanReadScope scope, Pageable pageable) {
        Specification<Loan> spec = where(withApprovalStatus(criteria.ssbApprovalStatus()))
                .and(withInternalApprovalStatus(criteria.creditApprovalStatus()))
                .and(withDisbursementStatus(criteria.disbursementStatus()))
                .and(withMerchantCode(criteria.merchantCode()))
                .and(withCreatedDateBetween(atStartOfDay(criteria.fromDate()), atEndOfDay(criteria.toDate())));
        if (!scope.platformWide()) {
            // ANDed with any merchant filter above, so a filter naming another merchant finds nothing.
            spec = spec.and(withMerchantCode(scope.merchantCode())).and(createdByUserId(scope.userId()));
        }
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
        Page<Loan> loans = loanRepository.findAll(spec, newestFirst);
        // Read once for the page. The service level is the lender's own measure of its staff, so only staff who read
        // every loan see it.
        Map<Long, CreditTurnaround> turnarounds = scope.platformWide()
                ? creditTurnarounds.of(loans.getContent()) : new HashMap<>();
        return loans.map(loan -> {
            LoanSummaryResponse summary = loanMapper.toSummary(loan);
            summary.setCreditTurnaround(turnarounds.get(loan.getId()));
            return summary;
        });
    }

    @Override
    public LoanResponse getLoan(Long id, LoanReadScope scope) {
        Specification<Loan> spec = where(withId(id));
        if (!scope.platformWide()) {
            // Scope applied IN the query, so an out-of-scope loan is simply not found: the same 404
            // as a missing id, which keeps this from being an existence oracle.
            spec = spec.and(withMerchantCode(scope.merchantCode())).and(createdByUserId(scope.userId()));
        }
        LoanResponse view = loanRepository.findOne(spec)
                .map(loan -> {
                    LoanResponse response = loanMapper.toResponse(loan);
                    if (scope.platformWide()) {
                        response.setCreditTurnaround(creditTurnarounds.of(List.of(loan)).get(loan.getId()));
                    }
                    return response;
                })
                .map(response -> scope.platformWide() ? response : response.withoutPayslipReview())
                .orElseThrow(() -> new NotFoundException("Loan " + id + " not found"));
        view.setDocuments(loanDocumentService.currentSummaries(id));
        return view;
    }


    @Override
    public Optional<Loan> findByReference(String reference) {
        return loanRepository.findById(Long.parseLong(reference));
    }

    /** A filter's days are the market's; the columns hold UTC, so each bound is converted. */
    private LocalDateTime atStartOfDay(LocalDate localDate) {
        return marketTimeZone.startOfDayUtc(localDate);
    }

    private LocalDateTime atEndOfDay(LocalDate localDate) {
        return marketTimeZone.endOfDayUtc(localDate);
    }

    /**
     * Transactional so the applicant lock below spans the pending check AND the
     * insert.
     */
    @Override
    @Transactional
    public LoanApplicationResponse requestLoan(LoanApplicationRequest loanRequest, SigningContext signing) {

        // Identifiers only: the request carries the applicant's KYC and base64 documents.
        log.info("Requesting loan approval: channel {}, ec {}, amount {}, tenor {}", loanRequest.getChannelId(),
                maskEcNumber(loanRequest.getEcNumber()), loanRequest.getAmount(), loanRequest.getTenor());

        // The HTTP body is already checked by @Validated on the controller (one
        // 400 listing every field). This repeats it for any caller that reaches the
        // service directly, so an application missing what InnBucks needs is
        // refused HERE, not accepted and then failed at the InnBucks step after
        // the customer believed they had applied.
        requireCompleteApplication(loanRequest);
        Channel channel = channelFor(loanRequest);

        // Zero-trust content: the attached documents are decoded and checked by their byte signature before
        // the application touches business state, so an undecodable, executable or unrecognised file is refused.
        Map<DocumentType, DecodedFile> documents = loanDocumentService.decodeApplication(loanRequest);
        DecodedFile payslip = documents.get(DocumentType.PAYSLIP);

        // Electronic signature (FR-SSB-013): each published instrument accepted at the version in force, with a
        // signature to sign it and the device it is signed on. Checked before any business state is touched.
        List<InstrumentTemplate> instruments = signedInstrumentService.requireAccepted(loanRequest, documents, signing);

        // Presence of the required fields is enforced declaratively by bean validation
        // (@Valid on the controller). What remains here are the business rules that need
        // runtime context: EC-number format, the 18+ age rule, and (in calculate) the
        // DB-driven amount/tenor ranges and the pending-loan check.

        final String formattedEcNumber = formattedEcNumber(loanRequest);

        requireNetWithinGross(loanRequest.getEmploymentDetail());

        val dateOfBirth = loanRequest.getDateOfBirth();
        if (dateOfBirth.isAfter(marketTimeZone.today().minusYears(18))) {
            throw new IllegalArgumentException("Must be 18+ years");
        }

        final String formattedIdNumber = formattedIdNumber(loanRequest);

        lockApplicant(formattedEcNumber, formattedIdNumber);
        Optional<Long> pendingLoanId = findPendingLoan(formattedEcNumber, formattedIdNumber);

        if (pendingLoanId.isPresent()) {
            log.info("Refusing loan application: loan {} for this applicant is still in flight", pendingLoanId.get());
            throw new PendingApplicationException(pendingLoanId.get());
        }

        // The signed-in caller originated it, whatever channel it came through (FR-SSB-017).
        User originator = authService.getLoggedInUser();
        log.info("Application originated by {} through {}", originator.getUsername(),
                channel == null ? "no channel" : "channel " + channel.getChannelId());

        final Loan loan = newLoan(loanRequest, formattedEcNumber, formattedIdNumber, originator, channel,
                payslip == null ? null : payslip.sha256());

        // Payslip fraud controls (FR-SSB-007). Two applications with one payslip must not pass each other
        // unseen, so the second waits for the first to commit before looking for it.
        if (loan.getPayslipSha256() != null) {
            loanRepository.lockApplicant("loan-application:payslip:" + loan.getPayslipSha256());
        }
        List<PayslipFraudDetector.Finding> findings = payslipFraudDetector.findingsFor(loan);
        if (!findings.isEmpty()) {
            // Held back from SSB lodgement until a credit officer decides; the caller is not told.
            loan.setPayslipReviewStatus(PayslipReviewStatus.PENDING);
        }

        loanRepository.save(loan);
        // Version 1 of each document (FR-SSB-009), committed with the application.
        loanDocumentService.storeApplication(loan, documents, originator.getUsername());
        if (!findings.isEmpty()) {
            payslipReviewService.hold(loan, findings);
        }
        // The instruments as signed, rendered from the loan as saved, with the evidence of the signing.
        signedInstrumentService.sign(loan, instruments, documents, signing, originator.getUsername());
        // The applicant hears it was received once it is committed (FR-SSB-016).
        loanNotificationService.notify(loan, LoanNotice.RECEIVED);

        return new LoanApplicationResponse(loan.getId(), loan.getReference(), loan.getLoanApprovalStatus());
    }

    /**
     * The loan agreement and deduction authority as this application would sign them today, for the applicant to
     * read before accepting (FR-SSB-013). Built exactly as {@link #requestLoan} builds the loan, so the text is the
     * text that will be signed; nothing is saved, and only the terms and identity need be present.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InstrumentPreview> previewInstruments(LoanApplicationRequest loanRequest) {
        Set<ConstraintViolation<LoanApplicationRequest>> violations = validator.validate(loanRequest, Default.class);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
        Channel channel = channelFor(loanRequest);
        return signedInstrumentService.preview(newLoan(loanRequest, formattedEcNumber(loanRequest),
                formattedIdNumber(loanRequest), authService.getLoggedInUser(), channel, null));
    }

    /**
     * Stored upper-cased, as the national ID already is, so the pending check compares like with like
     * ("1234567a" and "1234567A" are one person).
     */
    private static String formattedEcNumber(LoanApplicationRequest loanRequest) {
        String formatted = TextUtils.trimSpecialCharacters(loanRequest.getEcNumber()).toUpperCase();
        if (!formatted.matches(EC_NUMBER_REGEX_FORMAT)) {
            throw new IllegalArgumentException("EC Number is not valid");
        }
        return formatted;
    }

    private static String formattedIdNumber(LoanApplicationRequest loanRequest) {
        return TextUtils.trimSpecialCharacters(loanRequest.getNationalIdNumber()).toUpperCase();
    }

    /**
     * The loan an application becomes, priced for its originator and attributed to them: their merchant, their
     * commission, and the loans they can see and cannot credit-approve (FR-SSB-017). Not saved.
     */
    private Loan newLoan(LoanApplicationRequest loanRequest, String formattedEcNumber, String formattedIdNumber,
                         User originator, Channel channel, String payslipSha256) {
        LoanQuote quote = calculate(loanRequest.quoteRequest(), originator);
        return Loan.builder()
                .principal(quote.getPrincipal())
                .disbursementStatus(LoanDisbursementStatus.PENDING)
                .loanApprovalStatus(LoanApprovalStatus.NEW)
                .internalApprovalStatus(InternalApprovalStatus.PENDING)
                .loanAccountStatus(LoanAccountStatus.PENDING)
                .ecNumber(formattedEcNumber)
                .nationalIdNumber(formattedIdNumber)
                .mobileNumber(formatMsisdnInternational(loanRequest.getMobileNumber()))
                .walletNumber(formatMsisdnInternational(StringUtils.hasText(loanRequest.getWalletNumber())
                        ? loanRequest.getWalletNumber() : loanRequest.getMobileNumber()))
                .payslipSha256(payslipSha256)
                .feeAmount(quote.getFeeAmount())
                .feeRate(quote.getFeeRate())
                .interestRate(quote.getInterestRate())
                .interestAmount(quote.getInterestAmount())
                .monthlyInstallment(quote.getMonthlyInstallment())
                .grossedMonthlyDeduction(quote.getGrossedMonthlyDeduction())
                .commissionRate(quote.getCommissionRate())
                .disbursedAmount(quote.getDisbursedAmount())
                .tenor(quote.getTenor())
                .firstName(loanRequest.getFirstName())
                .lastName(loanRequest.getLastName())
                .dateOfBirth(loanRequest.getDateOfBirth())
                .agentCommission(quote.getAgentCommission())
                .agentCommissionRate(quote.getAgentCommissionRate())
                .providerCommissionRate(quote.getProviderCommissionRate())
                .providerCommission(quote.getProviderCommission())
                .commissionRatePercentage(quote.isCommissionPercentage())
                .numberOfDependencies(loanRequest.getNumberOfDependants())
                .numberOfChildren(loanRequest.getNumberOfChildren())
                .educationLevel(loanRequest.getEducationLevel())
                .maritalStatus(loanRequest.getMaritalStatus())
                .alternateContactNumber(loanRequest.getAlternateContactNumber())
                .placeOfBirth(loanRequest.getPlaceOfBirth())
                .title(loanRequest.getTitle())
                .email(loanRequest.getEmail())
                .address(loanRequest.getAddress())
                .employmentDetail(loanRequest.getEmploymentDetail())
                .payslipDeductions(payslipDeductions(loanRequest.getPayslipDeductions()))
                .nextOfKin(loanRequest.getNextOfKin())
                .witness(loanRequest.getWitness())
                .loanPurpose(loanRequest.getLoanPurpose())
                .lineOfBusiness(loanRequest.getLineOfBusiness())
                .bankingDetail(loanRequest.getBankingDetail())
                .gender(loanRequest.getGender())
                .profession(loanRequest.getProfession())
                .createdBy(originator.getUsername())
                .createdByUser(originator)
                .merchant(originator.getMerchant())
                .channel(channel)
                .loanStartDate(quote.getStartDate())
                .build();
    }

    /** A payslip cannot take home more than it earns: that would be a mistyped figure, not a payslip. */
    private static void requireNetWithinGross(EmploymentDetail employment) {
        if (employment != null && employment.getGrossSalary() != null && employment.getNetSalary() != null
                && employment.getNetSalary().compareTo(employment.getGrossSalary()) > 0) {
            throw new IllegalArgumentException("Net salary cannot exceed gross salary");
        }
    }

    /** Copied, beneficiaries trimmed, in the order captured. */
    private static List<PayslipDeduction> payslipDeductions(List<PayslipDeduction> captured) {
        if (captured == null) {
            return new ArrayList<>();
        }
        List<PayslipDeduction> deductions = new ArrayList<>();
        for (PayslipDeduction deduction : captured) {
            deductions.add(new PayslipDeduction(deduction.getBeneficiary().strip(), deduction.getAmount()));
        }
        return deductions;
    }

    /**
     * Refuses an application that fails the Default or {@link LoanApplicationChecks}
     * constraints, naming every failing field in one message ({@code field: message; ...},
     * full property path, sorted). The web layer checks the same constraints first and
     * answers them as a field map; this covers any caller that reaches the service directly.
     */
    private void requireCompleteApplication(LoanApplicationRequest loanRequest) {
        Set<ConstraintViolation<LoanApplicationRequest>> violations =
                validator.validate(loanRequest, Default.class, LoanApplicationChecks.class);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    private CommissionGroup resolveCommissionGroup(User user, Merchant merchant) {

        if (user == null) {
            return CommissionGroup.builder()
                    .percentage(false)
                    .providerCommission(BigDecimal.ZERO)
                    .agentCommission(BigDecimal.ZERO)
                    .build();
        }

        if (merchant.getCommissionStructure() == CommissionStructure.MERCHANT_DEFINED) {
            return merchant.getCommissionGroup();
        }

        return user.getCommissionGroup();
    }

    /**
     * Serialises applications for the same person across every node. Without it
     * two concurrent submissions both read "nothing pending" before either has
     * inserted, and both are lodged with Ndasenda. Transaction-scoped, so it is
     * held until {@link #requestLoan}'s insert commits. Always EC then national
     * ID, so every submission takes the two in the same order.
     */
    private void lockApplicant(String ecNumber, String nationalIdNumber) {
        loanRepository.lockApplicant("loan-application:ec:" + ecNumber);
        if (!nationalIdNumber.isEmpty()) {
            loanRepository.lockApplicant("loan-application:nid:" + nationalIdNumber);
        }
    }

    /**
     * The id of this applicant's application still in flight, if any — see
     * {@link LoanStatusSnapshot#isInFlight()} for what that means. Matched by EC
     * number and then by national ID, so the same person under a mistyped EC
     * number is caught too. A national ID that normalises to nothing identifies
     * nobody, so it is not matched against other blank rows.
     */
    private Optional<Long> findPendingLoan(String ecNumber, String nationalIdNumber) {
        Optional<Long> pending = firstInFlight(loanRepository.findStatusesByEcNumber(ecNumber));
        if (pending.isEmpty() && !nationalIdNumber.isEmpty()) {
            pending = firstInFlight(loanRepository.findStatusesByNationalId(nationalIdNumber));
        }
        return pending;
    }

    private static Optional<Long> firstInFlight(List<LoanStatusSnapshot> loans) {
        return loans.stream()
                .filter(LoanStatusSnapshot::isInFlight)
                .map(LoanStatusSnapshot::id)
                .findFirst();
    }

    @Override
    public LoanQuote calculate(LoanQuoteRequest request, User loggedInUser) {

        List<AmortizationEntry> schedule = new ArrayList<>();

        final Map<String, String> params = parameterService.getParameterValues(
                COMMISSION_RATE, ADMIN_FEE_RATE, MONTHLY_INTEREST_RATE,
                MINIMUM_LOAN_AMOUNT, MAXIMUM_LOAN_AMOUNT, MINIMUM_LOAN_TENOR, MAXIMUM_LOAN_TENOR);


        if (request.tenor() == null || request.tenor() <= 0) {
            throw new IllegalArgumentException("Loan tenor is required");
        }

        int minLoanTenor = Integer.parseInt(String.valueOf(params.get(MINIMUM_LOAN_TENOR)));
        int maxLoanTenor = Integer.parseInt(String.valueOf(params.get(MAXIMUM_LOAN_TENOR)));

        if (request.tenor() < minLoanTenor || request.tenor() > maxLoanTenor) {
            throw new IllegalArgumentException(String.format("Loan tenor should be between %s and %s", minLoanTenor, maxLoanTenor));
        }

        BigDecimal adminFeeRate = new BigDecimal(params.get(ADMIN_FEE_RATE));
        BigDecimal principalLoanAmount = getPrincipalLoanAmount(request, adminFeeRate);
        BigDecimal minLoanAmount = new BigDecimal(params.get(MINIMUM_LOAN_AMOUNT));

        BigDecimal maxLoanAmount = new BigDecimal(params.get(MAXIMUM_LOAN_AMOUNT));


        BigDecimal adminFeeAmount = principalLoanAmount
                .multiply(adminFeeRate)
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal monthlyInterestRate = new BigDecimal(params.get(MONTHLY_INTEREST_RATE));
        BigDecimal interestRate = percentToFraction(monthlyInterestRate);

        BigDecimal powerValue = interestRate.add(ONE).pow(request.tenor());

        BigDecimal installment = principalLoanAmount.multiply(interestRate).multiply(powerValue)
                .divide(powerValue.subtract(ONE), 2, RoundingMode.HALF_UP);

        BigDecimal disbursementAmount = principalLoanAmount.subtract(adminFeeAmount);

        if (disbursementAmount.compareTo(minLoanAmount) < 0 || disbursementAmount.compareTo(maxLoanAmount) > 0) {
            throw new IllegalArgumentException(String.format("Loan amount should be between %s and %s", minLoanAmount, maxLoanAmount));
        }

        BigDecimal commissionRate = new BigDecimal(params.get(COMMISSION_RATE));

        BigDecimal commissionRateToUse = percentToFraction(commissionRate);

        BigDecimal grossedMonthlyPayment = installment.divide(ONE.subtract(commissionRateToUse), 2, RoundingMode.HALF_UP);

        Merchant merchant = loggedInUser == null ? null : loggedInUser.getMerchant();

        CommissionGroup commissionGroup = resolveCommissionGroup(loggedInUser, merchant);

        BigDecimal totalCommissionAmount = principalLoanAmount.multiply(commissionRateToUse);

        BigDecimal agentCommissionAmount = getCommissionAmount(totalCommissionAmount, commissionGroup.isPercentage(), commissionGroup.getAgentCommission());

        BigDecimal providerCommissionAmount = getCommissionAmount(totalCommissionAmount, commissionGroup.isPercentage(), commissionGroup.getProviderCommission());

        final LoanQuote quote = LoanQuote.builder()
                .principal(principalLoanAmount)
                .tenor(request.tenor())
                .feeAmount(adminFeeAmount)
                .feeRate(adminFeeRate)
                .interestRate(monthlyInterestRate)
                .disbursedAmount(disbursementAmount)
                .amortizationSchedule(schedule)
                .commissionRate(commissionRate)
                .monthlyInstallment(installment)
                .grossedMonthlyDeduction(grossedMonthlyPayment)
                .agentCommission(agentCommissionAmount)
                .providerCommission(providerCommissionAmount)
                .agentCommissionRate(commissionGroup.getAgentCommission())
                .providerCommissionRate(commissionGroup.getProviderCommission())
                .commissionPercentage(commissionGroup.isPercentage())
                .build();

        amortizeLoan(quote);

        return quote;

    }

    /**
     * The channel the application names, or null when it names none (captured in the portal). A channel records
     * where the application came from, never who originated it (FR-SSB-017). One that names no registered channel
     * is refused: taken as "no channel", its applications would be reported as captured in the portal.
     */
    private Channel channelFor(LoanApplicationRequest loanRequest) {
        if (!StringUtils.hasText(loanRequest.getChannelId())) {
            return null;
        }
        return channelRepository.findChannelByChannelId(loanRequest.getChannelId().trim())
                .orElseThrow(() -> new IllegalArgumentException("No channel is registered under that channelId"));
    }

    private BigDecimal getCommissionAmount(BigDecimal totalCommissionAmount, boolean percentage, BigDecimal commissionAmount) {
        // The share is a rate (exact); the amount it produces is money (to the cent).
        return percentage
                ? totalCommissionAmount.multiply(percentToFraction(commissionAmount)).setScale(2, RoundingMode.HALF_UP)
                : commissionAmount;
    }

    /**
     * Percent to fraction ("2.5" -> 0.025), exact: dividing by 100 always
     * terminates, so no scale is needed and none may be imposed. This used to
     * round the FRACTION to two decimals, which priced 2.5% as 3% and 3.75% as
     * 4%. Rates stay exact; only the money amounts they produce are rounded.
     */
    private static BigDecimal percentToFraction(BigDecimal percent) {
        return percent.divide(ONE_HUNDRED);
    }

    private void amortizeLoan(LoanQuote quote) {
        BigDecimal interestRate = percentToFraction(quote.getInterestRate());
        BigDecimal remainingPrincipal = quote.getPrincipal();

        for (int paymentNumber = 1; paymentNumber <= quote.getTenor(); paymentNumber++) {
            BigDecimal interestPayment = remainingPrincipal.multiply(interestRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalPayment = quote.getMonthlyInstallment().subtract(interestPayment).setScale(2, RoundingMode.HALF_UP);
            remainingPrincipal = remainingPrincipal.subtract(principalPayment).setScale(2, RoundingMode.HALF_UP);

            if (remainingPrincipal.compareTo(ONE) < 0) {
                remainingPrincipal = BigDecimal.ZERO;
            }

            AmortizationEntry entry = AmortizationEntry.builder()
                    .interestPayment(interestPayment)
                    .paymentNumber(paymentNumber)
                    .regularMonthlyPayment(quote.getMonthlyInstallment())
                    .grossedMonthlyPayment(quote.getGrossedMonthlyDeduction())
                    .remainingPrincipal(remainingPrincipal)
                    .principalPayment(principalPayment)
                    .build();

            quote.add(entry);
        }
    }

    private BigDecimal getPrincipalLoanAmount(LoanQuoteRequest request, BigDecimal adminFeeRate) {

        if (request.amount() == null) {
            throw new IllegalArgumentException("Loan amount is required");
        }

        if (LoanAmountType.NET_OF_FEES == request.amountType()) {
            return request.amount().divide(ONE.subtract(percentToFraction(adminFeeRate)), 2, RoundingMode.HALF_UP);
        }
        return request.amount();
    }

    public Optional<Loan> findLatestActiveLoanByNationalId(final String nationalIdNumber) {
        log.info("Finding loan by ID Number");
        return loanRepository.findTopByNationalIdNumberAndLoanApprovalStatusIn(TextUtils.trimSpecialCharacters(nationalIdNumber),
                LoanApprovalStatus.activeLoanStatuses);
    }


}

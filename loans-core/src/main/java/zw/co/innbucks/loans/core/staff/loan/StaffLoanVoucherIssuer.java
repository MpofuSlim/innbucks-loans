package zw.co.innbucks.loans.core.staff.loan;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.getmore.ClaimLostException;
import zw.co.innbucks.loans.core.getmore.CouponRequest;
import zw.co.innbucks.loans.core.getmore.Deadline;
import zw.co.innbucks.loans.core.getmore.FindOrCreate;
import zw.co.innbucks.loans.core.getmore.GenerateGuard;
import zw.co.innbucks.loans.core.getmore.GenerateNotAllowedException;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.VoucherStore;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherCommand;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherCodeVault;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Issues a Staff Grocery Loan's voucher as a GetMore coupon (D13 to D15): the loan is accepted, its issuance row is
 * written in that same transaction ({@link #queue}), and once it commits the coupon is found or made at GetMore with no
 * transaction open, then kept as the loan's voucher, which pays the loan out (D5). Never by a job: the acceptance
 * itself (inline, within {@code loans.getmore.issuance.inline-budget}), the borrower's next read, an operator or a
 * cancellation moves it on.
 *
 * <p>One attempt ({@link #attempt}) is three steps, each in a transaction of its own and none held across GetMore:</p>
 * <ol>
 *   <li><b>Claim</b>: the issuance locked and marked CALLING with the time of this claim; refused while another
 *       attempt holds a claim younger than {@code stale-claim}, and parked, without calling GetMore, when GetMore is
 *       in another mode, the voucher keys are missing, or the loan's payday cut-off has passed.</li>
 *   <li><b>Call</b>: {@link GetMoreCoupons#findOrCreate}: the prefix is searched before any generate, a generate is
 *       counted in the issuance before it is sent and never beyond its allowance, and only a coupon made on or after
 *       the first claim (less the clock skew), under the rule, unused, is adopted.</li>
 *   <li><b>Settle</b>: the loan, then the issuance, locked; still this attempt's claim and the loan still awaiting,
 *       the coupon is kept as the voucher ({@link VoucherService#issueFromGetMore}), the loan DISBURSED and the
 *       issuance ISSUED in one commit. A loan cancelled meanwhile sends the issuance to CANCELLING and its coupons are
 *       withdrawn; a claim lost meanwhile withdraws the coupon this attempt bound.</li>
 * </ol>
 *
 * <p>What GetMore could not do is recorded on the issuance and never thrown: unavailable or an unknown outcome waits
 * (back-off from {@code retry-after}, doubling, at most 15 minutes) and the next attempt searches first, so a coupon
 * whose answer was lost is adopted, not made twice; a refusal or a prefix that is not loans' alone parks it for an
 * operator. A cancelled loan's issuance is cleaned up by {@link #cleanup}: every unused coupon on the prefix and every
 * code GetMore returned is withdrawn, and only once no claim can still be minting is it ABANDONED. Codes are logged
 * only as {@code ****8B1C}; the ones kept are sealed by the voucher vault.</p>
 */
@Slf4j
@Component
public class StaffLoanVoucherIssuer {

    static final String ENTITY = "STAFF_LOAN";
    static final String ISSUED_EVENT = "STAFF_LOAN_VOUCHER_ISSUED";
    static final String PARKED_EVENT = "STAFF_LOAN_VOUCHER_PARKED";
    static final String QUEUED_EVENT = "STAFF_LOAN_VOUCHER_QUEUED";
    static final String RETRIED_EVENT = "STAFF_LOAN_VOUCHER_RETRIED";
    static final String GENERATE_ALLOWED_EVENT = "STAFF_LOAN_VOUCHER_GENERATE_ALLOWED";
    static final String ABANDONED_EVENT = "STAFF_LOAN_VOUCHER_ABANDONED";
    static final String METRIC = "loans.getmore.issuance";

    /** Parked outcomes of the claim itself, before GetMore is called. */
    static final String MODE_MISMATCH = "MODE_MISMATCH";
    static final String VOUCHERS_UNAVAILABLE = "VOUCHERS_UNAVAILABLE";
    static final String PAST_DUE = "PAST_DUE";
    static final String GETMORE_OFF = "GETMORE_OFF";
    /** Codes GetMore made that could not be confirmed withdrawn are live, and kept for a cleanup. */
    static final String ORPHAN_LIVE = "ORPHAN_LIVE";
    static final String COUPON_USED_AFTER_CANCEL = "COUPON_USED_AFTER_CANCEL";
    static final String WAITING_FOR_CLAIM = "WAITING_FOR_CLAIM";
    /** After these an operator may allow another generate, stating GetMore holds no usable coupon on the prefix. */
    static final Set<String> GENERATE_AGAIN_OUTCOMES = Set.of(FindOrCreate.GENERATED_NOT_FOUND,
            FindOrCreate.PREFIX_NOT_HONOURED, ORPHAN_LIVE);

    /** Prefixes drawn for one issuance before giving up: the nonce is 40 random bits, so a clash is all but never. */
    static final int PREFIX_DRAWS = 6;
    static final Duration MAX_BACK_OFF = Duration.ofMinutes(15);
    static final int MAX_BACK_OFF_DOUBLINGS = 5;
    /** An operator's attempt may take this long between calls. */
    static final Duration OPERATOR_BUDGET = Duration.ofSeconds(30);
    /** A borrower has one open loan at a time; a few of their issuances are moved on at most per read. */
    static final int ISSUANCES_PER_REFRESH = 5;

    /** What started an attempt. */
    public enum Trigger {
        /** The acceptance itself, inline on its request, within the inline budget. */
        ACCEPTANCE,
        /** The borrower's read of their loan (in the background): respects the back-off, never moves a parked one. */
        BORROWER_READ,
        /** Credit asked for another attempt: moves a parked one too. */
        OPERATOR
    }

    /** What one {@link #attempt} did. */
    public enum Outcome {
        /** The loan has no issuance: its merchant is not a GetMore store, or GetMore was OFF when it was accepted. */
        NONE,
        /** The voucher is kept and the loan DISBURSED. */
        ISSUED,
        /** Nothing to do: issued already, or abandoned with its cancelled loan. */
        CLOSED,
        /** Another attempt holds the claim and may be calling GetMore right now. */
        IN_PROGRESS,
        /** Waiting for its back-off to pass (a borrower's read does not hurry it). */
        NOT_DUE,
        /** Stopped for an operator. */
        PARKED,
        /** GetMore could not answer, or is OFF here; it waits for the next attempt. */
        PENDING,
        /** The loan was cancelled: what may be live at GetMore is being withdrawn. */
        CANCELLING,
        /** Another attempt took the claim over meanwhile; this one wrote nothing more. */
        CLAIM_LOST,
        /** Something unexpected; logged. */
        FAILED
    }

    /** What one {@link #cleanup} did. */
    public enum Cleanup {
        /** It is not CANCELLING (cleaned up already, say): nothing done. */
        NOT_CANCELLING,
        /** Everything is withdrawn and no claim can still be minting: ABANDONED. */
        WITHDRAWN,
        /** Everything found is withdrawn, but a claim made too recently could still mint: it stays CANCELLING. */
        WAITING_FOR_CLAIM,
        /** A coupon on the prefix was used after the loan was cancelled: parked for an operator. */
        COUPON_USED,
        /** GetMore could not be asked or did not withdraw everything: it stays CANCELLING, to be tried again. */
        FAILED;

        /** Whether nothing of the voucher is left live at GetMore, as far as loans can see. */
        public boolean withdrawn() {
            return this == WITHDRAWN || this == WAITING_FOR_CLAIM;
        }
    }

    private final StaffLoanVoucherIssuanceRepository issuanceRepository;
    private final StaffLoanRepository loanRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherService voucherService;
    private final VoucherCodeVault vault;
    private final GetMoreCoupons coupons;
    private final GetMoreProperties properties;
    private final StaffLoanPolicy policy;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;
    private final MeterRegistry meterRegistry;
    private final Supplier<String> nonces;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    @Autowired
    public StaffLoanVoucherIssuer(StaffLoanVoucherIssuanceRepository issuanceRepository,
                                  StaffLoanRepository loanRepository, VoucherRepository voucherRepository,
                                  VoucherService voucherService, VoucherCodeVault vault, GetMoreCoupons coupons,
                                  GetMoreProperties properties, StaffLoanPolicy policy, AuthService authService,
                                  AuditService auditService, MarketTimeZone marketTimeZone,
                                  PlatformTransactionManager transactionManager,
                                  ObjectProvider<MeterRegistry> meterRegistry) {
        this(issuanceRepository, loanRepository, voucherRepository, voucherService, vault, coupons, properties,
                policy, authService, auditService, marketTimeZone, transactionManager, meterRegistry.getIfAvailable(),
                GetMoreCodes::newNonce);
    }

    /** With the nonces prefixes are drawn from; a test passes its own. */
    StaffLoanVoucherIssuer(StaffLoanVoucherIssuanceRepository issuanceRepository, StaffLoanRepository loanRepository,
                           VoucherRepository voucherRepository, VoucherService voucherService, VoucherCodeVault vault,
                           GetMoreCoupons coupons, GetMoreProperties properties, StaffLoanPolicy policy,
                           AuthService authService, AuditService auditService, MarketTimeZone marketTimeZone,
                           PlatformTransactionManager transactionManager, MeterRegistry meterRegistry,
                           Supplier<String> nonces) {
        this.issuanceRepository = issuanceRepository;
        this.loanRepository = loanRepository;
        this.voucherRepository = voucherRepository;
        this.voucherService = voucherService;
        this.vault = vault;
        this.coupons = coupons;
        this.properties = properties;
        this.policy = policy;
        this.authService = authService;
        this.auditService = auditService;
        this.marketTimeZone = marketTimeZone;
        this.meterRegistry = meterRegistry;
        this.nonces = nonces;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
        if (meterRegistry != null) {
            for (String outcome : List.of("issued", "adopted", "parked", "retry", "cancelled", "claim_lost")) {
                Counter.builder(METRIC).tag("outcome", outcome)
                        .description("Attempts to issue a Staff Grocery Loan's GetMore voucher, by how they ended")
                        .register(meterRegistry);
            }
        }
    }

    // ---- queued with the acceptance ------------------------------------------------------------------------------

    /**
     * Writes the loan's issuance, in the acceptance's own transaction: only when GetMore is not OFF here and the loan's
     * merchant is a GetMore store ({@code merchants.voucher_store}). PENDING and due now, under a prefix with a fresh
     * random nonce; nothing is called. Asked again for the same loan, it returns the one already written.
     *
     * @return the issuance, or empty when the loan's voucher is not a GetMore coupon (it waits AWAITING_DISBURSEMENT)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StaffLoanVoucherIssuance> queue(StaffLoan loan) {
        return queueInTransaction(loan);
    }

    private Optional<StaffLoanVoucherIssuance> queueInTransaction(StaffLoan loan) {
        GetMoreMode mode = properties.getMode();
        Merchant merchant = loan.getMerchant();
        if (mode == GetMoreMode.OFF || merchant.getVoucherStore() != VoucherStore.GETMORE) {
            return Optional.empty();
        }
        Optional<StaffLoanVoucherIssuance> earlier = issuanceRepository.findByStaffLoanId(loan.getId());
        if (earlier.isPresent()) {
            return earlier;
        }
        LocalDateTime now = now();
        StaffLoanVoucherIssuance issuance = issuanceRepository.save(StaffLoanVoucherIssuance.builder()
                .staffLoanId(loan.getId())
                .merchantId(merchant.getId())
                .storeMode(mode)
                .couponPrefix(newPrefix(mode))
                .ruleId(properties.getRuleId())
                .faceValue(loan.getAmount())
                .currency(loan.getCurrency())
                .status(StaffLoanVoucherIssuanceStatus.PENDING)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.info("Staff loan {}: GetMore voucher queued ({}, prefix {})", loan.getReference(), mode,
                issuance.getCouponPrefix());
        return Optional.of(issuance);
    }

    /** {@code <configured prefix><nonce>-}, drawn again while the store already has it. */
    private String newPrefix(GetMoreMode mode) {
        String configured = properties.couponPrefixFor(mode);
        for (int draw = 1; draw <= PREFIX_DRAWS; draw++) {
            String prefix = GetMoreCodes.prefixFor(configured, nonces.get());
            if (!issuanceRepository.existsByStoreModeAndCouponPrefix(mode, prefix)) {
                return prefix;
            }
            log.warn("GetMore coupon prefix {} is taken in {} (draw {}); drawing another nonce", prefix, mode, draw);
        }
        throw new IllegalStateException("Could not draw an unused GetMore coupon prefix in " + PREFIX_DRAWS
                + " draws");
    }

    // ---- an attempt --------------------------------------------------------------------------------------------

    /**
     * One attempt at the voucher of loan {@code reference}: claim, call, settle (see the class). Called with no
     * transaction open. Never throws: what happened is on the issuance, and returned.
     */
    public Outcome attempt(String reference, Trigger trigger, Deadline deadline) {
        try {
            GetMoreCalls.requireNoTransaction();
            Long loanId = read(status -> loanRepository.findByReference(reference).map(StaffLoan::getId)
                    .orElse(null));
            return loanId == null ? Outcome.NONE : attemptLoan(loanId, trigger, deadline);
        } catch (RuntimeException unexpected) {
            log.error("GetMore voucher of staff loan {} ({}): the attempt failed unexpectedly", reference, trigger,
                    unexpected);
            return Outcome.FAILED;
        }
    }

    /** {@link #attempt(String, Trigger, Deadline)} for a loan by its id. */
    public Outcome attempt(Long staffLoanId, Trigger trigger, Deadline deadline) {
        try {
            GetMoreCalls.requireNoTransaction();
            return attemptLoan(staffLoanId, trigger, deadline);
        } catch (RuntimeException unexpected) {
            log.error("GetMore voucher of staff loan {} ({}): the attempt failed unexpectedly", staffLoanId, trigger,
                    unexpected);
            return Outcome.FAILED;
        }
    }

    private Outcome attemptLoan(Long staffLoanId, Trigger trigger, Deadline deadline) {
        ClaimStep step = write(status -> claim(staffLoanId, trigger));
        if (step.cleanupId() != null) {
            cleanup(step.cleanupId());
        }
        if (step.claim() == null) {
            return step.outcome();
        }
        Claim claim = step.claim();
        FindOrCreate found;
        try {
            found = coupons.findOrCreate(new CouponRequest(claim.prefix(), claim.ruleId(), claim.faceValue(),
                    claim.currency(), properties.getCodeLength(), claim.voucherLastDay(),
                    claim.firstClaimedAt().minus(properties.getClockSkew()), new ClaimGuard(claim)),
                    deadline == null ? Deadline.none() : deadline);
        } catch (ClaimLostException lost) {
            log.warn("GetMore voucher of staff loan {}: the claim was taken over before anything was sent ({})",
                    claim.reference(), lost.getMessage());
            count("claim_lost");
            return Outcome.CLAIM_LOST;
        } catch (GetMoreUnavailableException unavailable) {
            return retryLater(claim, "UNAVAILABLE_" + unavailable.reason(), unavailable, List.of());
        } catch (GetMoreOutcomeUnknownException unknown) {
            return retryLater(claim, "OUTCOME_UNKNOWN", unknown, unknown.mintedCodes());
        } catch (GetMoreRejectedException rejected) {
            return park(claim, rejected.reason().name(), rejected.getMessage(), List.of());
        } catch (RuntimeException unexpected) {
            log.error("GetMore voucher of staff loan {}: the call failed unexpectedly; taken as an unknown outcome",
                    claim.reference(), unexpected);
            return retryLater(claim, "ERROR", unexpected, List.of());
        }
        return switch (found) {
            case FindOrCreate.Parked parked -> park(claim, parked.liveOrphanCodes().isEmpty() ? parked.outcome()
                    : ORPHAN_LIVE, parked.outcome() + ": " + parked.detail(), parked.liveOrphanCodes());
            case FindOrCreate.Bound bound -> settle(claim, bound, deadline == null ? Deadline.none() : deadline);
        };
    }

    /** [T1] The claim, or why there is none. */
    private ClaimStep claim(Long staffLoanId, Trigger trigger) {
        LocalDateTime now = now();
        StaffLoanVoucherIssuance issuance = issuanceRepository.lockByStaffLoanId(staffLoanId).orElse(null);
        if (issuance == null) {
            return ClaimStep.done(Outcome.NONE);
        }
        switch (issuance.getStatus()) {
            case ISSUED, ABANDONED -> {
                return ClaimStep.done(Outcome.CLOSED);
            }
            case CANCELLING -> {
                return ClaimStep.cleanup(issuance.getId());
            }
            default -> {
            }
        }
        if (issuance.isClaimFresh(now, staleClaim())) {
            return ClaimStep.done(Outcome.IN_PROGRESS);
        }
        if (issuance.getStatus() == StaffLoanVoucherIssuanceStatus.PARKED && trigger != Trigger.OPERATOR) {
            return ClaimStep.done(Outcome.PARKED);
        }
        if (issuance.getStatus() == StaffLoanVoucherIssuanceStatus.PENDING && trigger == Trigger.BORROWER_READ
                && issuance.getNextAttemptAt().isAfter(now)) {
            return ClaimStep.done(Outcome.NOT_DUE);
        }
        GetMoreMode mode = properties.getMode();
        if (mode == GetMoreMode.OFF) {
            issuance.notTried(GETMORE_OFF, now);
            issuanceRepository.save(issuance);
            return ClaimStep.done(Outcome.PENDING);
        }
        StaffLoan loan = loanRepository.findById(staffLoanId).orElseThrow(() -> new NotFoundException(
                "Staff loan " + staffLoanId + " not found"));
        if (loan.getStatus() == StaffLoanStatus.CANCELLED) {
            issuance.cancelling(now);
            issuanceRepository.save(issuance);
            log.info("GetMore voucher of staff loan {}: the loan is cancelled; its coupons are withdrawn",
                    loan.getReference());
            return ClaimStep.cleanup(issuance.getId());
        }
        if (loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT) {
            return ClaimStep.done(parkNow(issuance, loan, "LOAN_" + loan.getStatus(), "the loan is "
                    + loan.getStatus() + ", not awaiting its voucher", now));
        }
        if (issuance.getStoreMode() != mode) {
            return ClaimStep.done(parkNow(issuance, loan, MODE_MISMATCH, "the issuance is for the "
                    + issuance.getStoreMode() + " store and GetMore is " + mode + " on this host", now));
        }
        if (!vault.isConfigured()) {
            return ClaimStep.done(parkNow(issuance, loan, VOUCHERS_UNAVAILABLE, "VOUCHER_CODE_HMAC_KEY and"
                    + " VOUCHER_CODE_ENCRYPTION_KEY are not configured: a GetMore code cannot be stored", now));
        }
        if (!now.isBefore(marketTimeZone.startOfDayUtc(loan.getDueDate()))) {
            return ClaimStep.done(parkNow(issuance, loan, PAST_DUE, "the loan's payday (" + loan.getDueDate()
                    + ") has begun: a voucher issued now would be on no collection; cancel the loan", now));
        }
        issuance.claim(now);
        issuanceRepository.save(issuance);
        LocalDate lastDay = marketTimeZone.localDay(voucherService.expiryFor(now));
        return ClaimStep.claimed(new Claim(issuance.getId(), loan.getId(), loan.getReference(), now,
                issuance.getCouponPrefix(), issuance.getRuleId(), issuance.getFaceValue(), issuance.getCurrency(),
                issuance.getStoreMode(), issuance.getFirstClaimedAt(), lastDay));
    }

    /** Counts a generate before it is sent, each in a transaction of its own (D14). */
    private final class ClaimGuard implements GenerateGuard {

        private final Claim claim;

        ClaimGuard(Claim claim) {
            this.claim = claim;
        }

        @Override
        public void beforeGenerate() {
            writeTransaction.executeWithoutResult(status -> {
                LocalDateTime now = now();
                StaffLoanVoucherIssuance issuance = lockIssuance(claim.issuanceId());
                if (!issuance.isClaimedBy(claim.claimedAt())) {
                    throw new ClaimLostException("issuance " + claim.issuanceId() + " is " + issuance.getStatus()
                            + ", no longer claimed by this attempt");
                }
                if (issuance.getGenerateCalls() >= issuance.getGenerateAllowance()) {
                    throw new GenerateNotAllowedException(issuance.getGenerateCalls() + " generate(s) may already"
                            + " have reached GetMore, the allowance is " + issuance.getGenerateAllowance());
                }
                issuance.countGenerate(now);
                issuanceRepository.save(issuance);
            });
        }

        @Override
        public void notProcessed() {
            writeTransaction.executeWithoutResult(status -> {
                StaffLoanVoucherIssuance issuance = lockIssuance(claim.issuanceId());
                if (issuance.isClaimedBy(claim.claimedAt())) {
                    issuance.giveGenerateBack(now());
                    issuanceRepository.save(issuance);
                }
            });
        }
    }

    /** [T2] Keeps the coupon as the voucher, or follows the loan or the claim where they went meanwhile. */
    private Outcome settle(Claim claim, FindOrCreate.Bound bound, Deadline deadline) {
        GetMoreCoupon coupon = bound.coupon();
        Settled settled;
        try {
            settled = write(status -> settleLocked(claim, bound));
        } catch (RuntimeException failed) {
            log.error("GetMore voucher of staff loan {}: coupon {} ({}) was bound but could not be kept; the next"
                    + " attempt adopts it", claim.reference(), coupon.couponId(),
                    GetMoreCodes.maskedCode(coupon.code()), failed);
            return retryLater(claim, "ERROR", failed, List.of());
        }
        switch (settled.kind()) {
            case ISSUED -> {
                setExpiry(claim, coupon, settled.voucher(), deadline);
                return Outcome.ISSUED;
            }
            case LOAN_CANCELLED -> {
                count("cancelled");
                cleanup(claim.issuanceId());
                return Outcome.CANCELLING;
            }
            case LOAN_NOT_AWAITING -> {
                withdrawUnwanted(claim, coupon);
                return Outcome.PARKED;
            }
            default -> {
                count("claim_lost");
                withdrawUnwanted(claim, coupon);
                return Outcome.CLAIM_LOST;
            }
        }
    }

    private Settled settleLocked(Claim claim, FindOrCreate.Bound bound) {
        LocalDateTime now = now();
        GetMoreCoupon coupon = bound.coupon();
        StaffLoan loan = loanRepository.lockById(claim.staffLoanId()).orElseThrow(() -> new NotFoundException(
                "Staff loan " + claim.staffLoanId() + " not found"));
        StaffLoanVoucherIssuance issuance = lockIssuance(claim.issuanceId());
        if (!issuance.isClaimedBy(claim.claimedAt())) {
            log.warn("GetMore voucher of staff loan {}: coupon {} ({}) was bound, but the claim was taken over"
                            + " meanwhile (the issuance is {}); it is withdrawn", loan.getReference(),
                    coupon.couponId(), GetMoreCodes.maskedCode(coupon.code()), issuance.getStatus());
            return Settled.of(SettledKind.CLAIM_LOST, null);
        }
        if (loan.getStatus() == StaffLoanStatus.CANCELLED) {
            issuance.cancelling(now);
            issuanceRepository.save(issuance);
            log.info("GetMore voucher of staff loan {}: the loan was cancelled while coupon {} ({}) was made;"
                            + " withdrawing it", loan.getReference(), coupon.couponId(),
                    GetMoreCodes.maskedCode(coupon.code()));
            return Settled.of(SettledKind.LOAN_CANCELLED, null);
        }
        if (loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT) {
            parkNow(issuance, loan, "LOAN_" + loan.getStatus(), "the loan became " + loan.getStatus()
                    + " while coupon " + coupon.couponId() + " was made; it is withdrawn", now);
            return Settled.of(SettledKind.LOAN_NOT_AWAITING, null);
        }
        VoucherResponse voucher = voucherService.issueFromGetMore(new GetMoreVoucherCommand(loan.getId(),
                loan.getReference(), issuance.getMerchantId(), loan.getStaffMemberId(), loan.getEmployeeNumber(),
                loan.getFullName(), loan.getMsisdn(), issuance.getFaceValue(), issuance.getCurrency(),
                issuance.getStoreMode(), coupon.couponId(), coupon.ruleId(), coupon.code(),
                issuance.getCouponPrefix()));
        loan.voucherIssued(voucher.issuedAt(), voucher.disbursementReference());
        loanRepository.save(loan);
        issuance.issued(voucher.id(), now);
        issuanceRepository.save(issuance);
        auditService.record(AuditLog.builder()
                .eventType(ISSUED_EVENT)
                .entityType(ENTITY).entityId(String.valueOf(loan.getId()))
                .actorId(authService.getLoggedInUsername()).channelUsed("system")
                .stateTransitionDelta("{\"from\":\"AWAITING_DISBURSEMENT\",\"to\":\"DISBURSED\"}")
                .detail("reference:" + loan.getReference() + ";voucher:" + voucher.id() + ";coupon:"
                        + coupon.couponId() + ";code:" + voucher.maskedCode() + ";mode:" + issuance.getStoreMode()
                        + ";disbursement:" + voucher.disbursementReference() + ";adopted:" + bound.adopted()
                        + ";attempts:" + issuance.getAttempts()));
        count(bound.adopted() ? "adopted" : "issued");
        log.info("Staff loan {} DISBURSED as GetMore voucher {} (coupon {}, {}, {}) after {} attempt(s)",
                loan.getReference(), voucher.id(), coupon.couponId(), voucher.maskedCode(),
                bound.adopted() ? "adopted" : "generated", issuance.getAttempts());
        return Settled.of(SettledKind.ISSUED, voucher);
    }

    /**
     * Best effort, after the voucher is kept: tells GetMore when the coupon lapses. Magento stores it and does not
     * enforce it (the simulator does); a lapsed code is withdrawn by the next sync that touches it. Not started once
     * the attempt's budget has run out.
     */
    private void setExpiry(Claim claim, GetMoreCoupon coupon, VoucherResponse voucher, Deadline deadline) {
        try {
            deadline.check(marketTimeZone.clock());
            coupons.setExpiry(coupon, voucher.expiresAt());
            voucherService.markCouponExpirySet(voucher.id(), now());
        } catch (RuntimeException notSet) {
            log.warn("GetMore voucher {} of staff loan {}: the coupon's expiry date was not set at GetMore ({});"
                            + " a lapsed code is still withdrawn by the next sync", voucher.id(), claim.reference(),
                    GetMoreCodes.maskAll(String.valueOf(notSet.getMessage()), List.of(coupon.code())));
        }
    }

    /**
     * A coupon this attempt bound but loans will not keep (the claim was lost, or the loan moved on): withdrawn, unless
     * it is the very coupon another attempt already issued. If it cannot be withdrawn, an abandoned issuance goes back
     * to CANCELLING so a cleanup tries again; a pending, parked or cancelling one finds it on its prefix anyway, and an
     * issued one's voucher sync withdraws unused extras.
     */
    private void withdrawUnwanted(Claim claim, GetMoreCoupon coupon) {
        Long issuedCoupon = read(status -> issuanceRepository.findById(claim.issuanceId())
                .filter(issuance -> issuance.getStatus() == StaffLoanVoucherIssuanceStatus.ISSUED)
                .map(StaffLoanVoucherIssuance::getVoucherId)
                .flatMap(voucherRepository::findById)
                .map(Voucher::getExternalCouponId)
                .orElse(null));
        if (issuedCoupon != null && issuedCoupon == coupon.couponId()) {
            return;
        }
        boolean withdrawn;
        try {
            GetMoreCouponDeletion deletion = coupons.withdraw(List.of(coupon.code()));
            withdrawn = !deletion.failedItems().contains(coupon.code());
        } catch (RuntimeException failed) {
            log.warn("GetMore voucher of staff loan {}: coupon {} ({}) could not be withdrawn: {}", claim.reference(),
                    coupon.couponId(), GetMoreCodes.maskedCode(coupon.code()),
                    GetMoreCodes.maskAll(String.valueOf(failed.getMessage()), List.of(coupon.code())));
            withdrawn = false;
        }
        if (withdrawn) {
            log.info("GetMore voucher of staff loan {}: coupon {} ({}), not kept, is withdrawn", claim.reference(),
                    coupon.couponId(), GetMoreCodes.maskedCode(coupon.code()));
            return;
        }
        writeTransaction.executeWithoutResult(status -> {
            StaffLoanVoucherIssuance issuance = lockIssuance(claim.issuanceId());
            issuance.keepSealedCodes(List.of(vault.encrypt(coupon.code())), now());
            if (issuance.getStatus() == StaffLoanVoucherIssuanceStatus.ABANDONED) {
                issuance.cancelling(now());
            }
            issuanceRepository.save(issuance);
        });
        log.error("GetMore voucher of staff loan {}: coupon {} ({}) is live at GetMore and not kept; it is kept"
                + " sealed on the issuance for a cleanup", claim.reference(), coupon.couponId(),
                GetMoreCodes.maskedCode(coupon.code()));
    }

    /** [T2'] Unavailable, unknown or unexpected: PENDING again, with back-off, while this attempt holds the claim. */
    private Outcome retryLater(Claim claim, String outcome, RuntimeException cause, List<String> minted) {
        String error = masked(cause, minted);
        Boolean written = write(status -> {
            LocalDateTime now = now();
            StaffLoanVoucherIssuance issuance = lockIssuance(claim.issuanceId());
            if (!issuance.isClaimedBy(claim.claimedAt())) {
                return false;
            }
            issuance.keepSealedCodes(seal(issuance, minted), now);
            issuance.retryAt(now.plus(backOff(issuance.getAttempts())), outcome, error, now);
            issuanceRepository.save(issuance);
            return true;
        });
        if (!Boolean.TRUE.equals(written)) {
            log.warn("GetMore voucher of staff loan {}: {} ({}), but the claim was taken over meanwhile; nothing"
                    + " recorded", claim.reference(), outcome, error);
            count("claim_lost");
            return Outcome.CLAIM_LOST;
        }
        log.warn("GetMore voucher of staff loan {}: {} ({}); tried again later{}", claim.reference(), outcome, error,
                minted.isEmpty() ? "" : ", " + minted.size() + " minted code(s) kept sealed");
        count("retry");
        return Outcome.PENDING;
    }

    /** [T2'] Refused, or not loans' to adopt: PARKED for an operator, with any live codes kept sealed. */
    private Outcome park(Claim claim, String outcome, String detail, List<String> liveCodes) {
        String error = GetMoreCodes.maskAll(detail, liveCodes);
        Boolean written = write(status -> {
            LocalDateTime now = now();
            StaffLoanVoucherIssuance issuance = lockIssuance(claim.issuanceId());
            if (!liveCodes.isEmpty()) {
                issuance.keepSealedCodes(seal(issuance, liveCodes), now);
            }
            if (!issuance.isClaimedBy(claim.claimedAt())) {
                issuanceRepository.save(issuance);
                return false;
            }
            StaffLoan loan = loanRepository.findById(claim.staffLoanId()).orElseThrow();
            parkNow(issuance, loan, outcome, error, now);
            return true;
        });
        if (!Boolean.TRUE.equals(written)) {
            log.warn("GetMore voucher of staff loan {}: {}, but the claim was taken over meanwhile", claim.reference(),
                    outcome);
            count("claim_lost");
            return Outcome.CLAIM_LOST;
        }
        return Outcome.PARKED;
    }

    /** Parks it in the caller's transaction, audited. */
    private Outcome parkNow(StaffLoanVoucherIssuance issuance, StaffLoan loan, String outcome, String detail,
                            LocalDateTime now) {
        issuance.park(outcome, detail, now);
        issuanceRepository.save(issuance);
        auditService.record(AuditLog.builder()
                .eventType(PARKED_EVENT)
                .entityType(ENTITY).entityId(String.valueOf(loan.getId()))
                .actorId(authService.getLoggedInUsername()).channelUsed("system")
                .detail("reference:" + loan.getReference() + ";issuance:" + issuance.getId() + ";outcome:" + outcome
                        + ";attempts:" + issuance.getAttempts() + ";detail:" + issuance.getLastError()));
        count("parked");
        log.warn("GetMore voucher of staff loan {} PARKED for an operator: {} ({})", loan.getReference(), outcome,
                issuance.getLastError());
        return Outcome.PARKED;
    }

    /** {@code retry-after} doubled per attempt after the first, the doublings capped first, at most 15 minutes. */
    Duration backOff(int attempts) {
        int doublings = Math.min(Math.max(attempts - 1, 0), MAX_BACK_OFF_DOUBLINGS);
        Duration wait = properties.getIssuance().getRetryAfter().multipliedBy(1L << doublings);
        return wait.compareTo(MAX_BACK_OFF) > 0 ? MAX_BACK_OFF : wait;
    }

    // ---- cleaning up a cancelled loan's issuance ------------------------------------------------------------------

    /**
     * Withdraws what a cancelled loan's issuance may have left live at GetMore (§8.5), with no transaction open: every
     * unused coupon on its prefix and every code GetMore returned to it, in one call. A coupon on the prefix used since
     * parks it for an operator. With everything withdrawn it is ABANDONED, once no claim can still be minting (the
     * stale-claim time after its last claim); until then it stays CANCELLING. One never claimed is ABANDONED without
     * asking GetMore: nothing can have been made for it. Never throws.
     */
    public Cleanup cleanup(Long issuanceId) {
        try {
            GetMoreCalls.requireNoTransaction();
            return cleanupNow(issuanceId);
        } catch (RuntimeException failed) {
            log.warn("GetMore voucher issuance {}: cleanup failed; it stays CANCELLING and is tried again: {}",
                    issuanceId, GetMoreCodes.maskAll(String.valueOf(failed.getMessage()), List.of()));
            leave(issuanceId, "CLEANUP_FAILED", GetMoreCodes.maskAll(String.valueOf(failed.getMessage()), List.of()));
            return Cleanup.FAILED;
        }
    }

    private Cleanup cleanupNow(Long issuanceId) {
        StaffLoanVoucherIssuance found = read(status -> issuanceRepository.findById(issuanceId).orElse(null));
        if (found == null || found.getStatus() != StaffLoanVoucherIssuanceStatus.CANCELLING) {
            return Cleanup.NOT_CANCELLING;
        }
        if (found.neverClaimed() && found.mintedCodeCount() == 0) {
            return finish(issuanceId, "never claimed: nothing can have been made at GetMore");
        }
        GetMoreMode mode = properties.getMode();
        if (found.getStoreMode() != mode) {
            leave(issuanceId, "MODE_MISMATCH", "its coupons are in the " + found.getStoreMode() + " store and"
                    + " GetMore is " + mode + " on this host");
            return Cleanup.FAILED;
        }
        List<String> minted = found.sealedCodes().stream().map(vault::decrypt).toList();
        GetMoreCoupons.OnPrefix onPrefix = coupons.onPrefix(found.getCouponPrefix(), Deadline.none());
        if (!onPrefix.complete()) {
            leave(issuanceId, "CLEANUP_FAILED", "more coupons on prefix " + found.getCouponPrefix() + " than loans"
                    + " reads: not loans' alone");
            return Cleanup.FAILED;
        }
        List<GetMoreCoupon> used = onPrefix.coupons().stream().filter(coupon -> coupon.timesUsed() > 0).toList();
        Set<String> toWithdraw = new LinkedHashSet<>();
        onPrefix.coupons().stream().filter(coupon -> coupon.timesUsed() == 0).map(GetMoreCoupon::code)
                .forEach(toWithdraw::add);
        toWithdraw.addAll(minted);
        GetMoreCouponDeletion deletion = coupons.withdraw(List.copyOf(toWithdraw));
        List<String> failed = deletion.failedItems();
        if (!used.isEmpty()) {
            return usedAfterCancel(issuanceId, used);
        }
        if (!failed.isEmpty()) {
            leave(issuanceId, "WITHDRAW_FAILED", failed.size() + " of " + toWithdraw.size() + " code(s) "
                    + failed.stream().map(GetMoreCodes::maskedCode).toList() + " not withdrawn at GetMore");
            return Cleanup.FAILED;
        }
        log.info("GetMore voucher issuance {}: {} code(s) withdrawn on prefix {} ({} already gone)", issuanceId,
                toWithdraw.size(), found.getCouponPrefix(), deletion.missingItems().size());
        return finish(issuanceId, toWithdraw.size() + " code(s) withdrawn");
    }

    /** ABANDONED once no claim can still be minting; until then CANCELLING, WAITING_FOR_CLAIM. */
    private Cleanup finish(Long issuanceId, String what) {
        Cleanup result = write(status -> {
            LocalDateTime now = now();
            StaffLoanVoucherIssuance issuance = lockIssuance(issuanceId);
            if (issuance.getStatus() != StaffLoanVoucherIssuanceStatus.CANCELLING) {
                return Cleanup.NOT_CANCELLING;
            }
            LocalDateTime lastClaim = issuance.getLastClaimedAt();
            if (lastClaim != null && now.isBefore(lastClaim.plus(staleClaim()))) {
                issuance.cleanupLeft(WAITING_FOR_CLAIM, null, now);
                issuanceRepository.save(issuance);
                return Cleanup.WAITING_FOR_CLAIM;
            }
            issuance.abandoned(now);
            issuanceRepository.save(issuance);
            auditService.record(AuditLog.builder()
                    .eventType(ABANDONED_EVENT)
                    .entityType(ENTITY).entityId(String.valueOf(issuance.getStaffLoanId()))
                    .actorId(authService.getLoggedInUsername()).channelUsed("system")
                    .detail("issuance:" + issuanceId + ";prefix:" + issuance.getCouponPrefix() + ";" + what));
            return Cleanup.WITHDRAWN;
        });
        log.info("GetMore voucher issuance {} of a cancelled loan: {} ({})", issuanceId, result, what);
        return result;
    }

    private Cleanup usedAfterCancel(Long issuanceId, List<GetMoreCoupon> used) {
        String detail = used.size() + " coupon(s) on the prefix used after the loan was cancelled: "
                + used.stream().map(c -> c.couponId() + " (" + GetMoreCodes.maskedCode(c.code()) + ", used "
                + c.timesUsed() + ")").toList();
        writeTransaction.executeWithoutResult(status -> {
            StaffLoanVoucherIssuance issuance = lockIssuance(issuanceId);
            if (issuance.getStatus() != StaffLoanVoucherIssuanceStatus.CANCELLING) {
                return;
            }
            StaffLoan loan = loanRepository.findById(issuance.getStaffLoanId()).orElseThrow();
            parkNow(issuance, loan, COUPON_USED_AFTER_CANCEL, detail, now());
        });
        log.error("GetMore voucher issuance {}: {}; parked for an operator", issuanceId, detail);
        return Cleanup.COUPON_USED;
    }

    /** Still CANCELLING, with why; best effort. */
    private void leave(Long issuanceId, String outcome, String error) {
        try {
            writeTransaction.executeWithoutResult(status -> issuanceRepository.lockById(issuanceId)
                    .filter(issuance -> issuance.getStatus() == StaffLoanVoucherIssuanceStatus.CANCELLING)
                    .ifPresent(issuance -> {
                        issuance.cleanupLeft(outcome, error, now());
                        issuanceRepository.save(issuance);
                    }));
        } catch (RuntimeException notRecorded) {
            log.warn("GetMore voucher issuance {}: could not record the cleanup's outcome ({})", issuanceId,
                    notRecorded.getClass().getSimpleName());
        }
    }

    // ---- a borrower's read, and Credit ---------------------------------------------------------------------------

    /**
     * Moves a staff member's issuances on, on a borrower's read (in the background): one due (PENDING past its
     * back-off, or a stale claim) is attempted, one CANCELLING is cleaned up. A parked one waits for Credit.
     */
    public void refreshFor(Long staffMemberId) {
        GetMoreCalls.requireNoTransaction();
        LocalDateTime now = now();
        List<StaffLoanVoucherIssuance> open = read(status -> issuanceRepository.findForMember(staffMemberId,
                EnumSet.of(StaffLoanVoucherIssuanceStatus.PENDING, StaffLoanVoucherIssuanceStatus.CALLING,
                        StaffLoanVoucherIssuanceStatus.CANCELLING), ISSUANCES_PER_REFRESH));
        for (StaffLoanVoucherIssuance issuance : open) {
            switch (issuance.getStatus()) {
                case CANCELLING -> cleanup(issuance.getId());
                case PENDING -> {
                    if (!issuance.getNextAttemptAt().isAfter(now)) {
                        attempt(issuance.getStaffLoanId(), Trigger.BORROWER_READ, Deadline.none());
                    }
                }
                case CALLING -> {
                    if (!issuance.isClaimFresh(now, staleClaim())) {
                        attempt(issuance.getStaffLoanId(), Trigger.BORROWER_READ, Deadline.none());
                    }
                }
                default -> {
                }
            }
        }
    }

    /**
     * Credit's "try the voucher again" for a loan awaiting its voucher (POST /staff-loans/{id}/voucher-issuance): makes
     * the issuance of a loan accepted while GetMore was OFF, cleans up a cancelling one, puts a parked one back to
     * PENDING (not one parked PAST_DUE: that loan is to be cancelled), and with {@code generateAgain} allows one more
     * generate, which is Credit stating that GetMore holds no usable coupon on the prefix (only after
     * GENERATED_NOT_FOUND, PREFIX_NOT_HONOURED or ORPHAN_LIVE). Then one attempt, within 30 seconds between calls.
     *
     * @throws GetMoreUnavailableException {@code OFF}: GetMore is OFF on this host
     * @throws NotFoundException           no such loan
     * @throws ConflictException           the loan is not awaiting its voucher, its merchant is not a GetMore store,
     *                                     it is past due, or {@code generateAgain} does not apply
     * @throws IssuanceInProgressException an attempt is calling GetMore right now
     */
    public StaffLoanVoucherIssuanceResult retry(Long staffLoanId, String reason, boolean generateAgain) {
        GetMoreCalls.requireNoTransaction();
        if (properties.getMode() == GetMoreMode.OFF) {
            throw new GetMoreUnavailableException(null, GetMoreUnavailableException.Reason.OFF,
                    "GetMore is OFF on this host (GETMORE_MODE): no voucher can be issued");
        }
        String by = authService.getLoggedInUsername();
        String why = reason == null ? "" : reason.strip();
        Long cleanupId = write(status -> prepareRetry(staffLoanId, why, generateAgain, by));
        if (cleanupId != null) {
            cleanup(cleanupId);
        } else {
            attempt(staffLoanId, Trigger.OPERATOR, Deadline.after(marketTimeZone.clock(), OPERATOR_BUDGET));
        }
        return read(status -> {
            StaffLoan loan = loanRepository.findById(staffLoanId).orElseThrow(() -> notFound(staffLoanId));
            StaffLoanVoucherIssuance issuance = issuanceRepository.findByStaffLoanId(staffLoanId).orElseThrow();
            return new StaffLoanVoucherIssuanceResult(StaffLoanVoucherIssuanceResponse.of(issuance,
                    loan.getReference()), StaffLoanResponse.of(loan, marketTimeZone.today(),
                    policy.arrearsGraceDays()));
        });
    }

    /** @return the issuance to clean up, or null to attempt */
    private Long prepareRetry(Long staffLoanId, String reason, boolean generateAgain, String by) {
        LocalDateTime now = now();
        StaffLoan loan = loanRepository.lockById(staffLoanId).orElseThrow(() -> notFound(staffLoanId));
        StaffLoanVoucherIssuance issuance = issuanceRepository.lockByStaffLoanId(staffLoanId).orElse(null);
        if (issuance != null && issuance.getStatus() == StaffLoanVoucherIssuanceStatus.CANCELLING) {
            return issuance.getId();
        }
        if (loan.getStatus() != StaffLoanStatus.AWAITING_DISBURSEMENT) {
            throw new ConflictException("Staff loan " + loan.getReference() + " is " + loan.getStatus()
                    + "; only a loan awaiting its voucher can be issued one");
        }
        if (issuance == null) {
            if (generateAgain) {
                throw new ConflictException("Staff loan " + loan.getReference() + " has no voucher issuance yet:"
                        + " generateAgain applies only to a parked one");
            }
            issuance = queueInTransaction(loan).orElseThrow(() -> new ConflictException("Staff loan "
                    + loan.getReference() + " is for " + loan.getMerchant().getMerchantCode() + ", which is not a"
                    + " GetMore store: its voucher is not a GetMore coupon"));
            audit(QUEUED_EVENT, loan, issuance, by, "reason:" + reason);
            return null;
        }
        if (issuance.isClaimFresh(now, staleClaim())) {
            throw new IssuanceInProgressException(loan.getReference());
        }
        if (issuance.getStatus() == StaffLoanVoucherIssuanceStatus.PARKED) {
            if (PAST_DUE.equals(issuance.getLastOutcome())) {
                throw new ConflictException("Staff loan " + loan.getReference() + " reached its payday cut-off ("
                        + loan.getDueDate() + ") without a voucher: cancel the loan");
            }
            if (generateAgain) {
                if (!GENERATE_AGAIN_OUTCOMES.contains(issuance.getLastOutcome())) {
                    throw new ConflictException("generateAgain is accepted only after "
                            + GENERATE_AGAIN_OUTCOMES.stream().sorted().toList() + "; the voucher of "
                            + loan.getReference() + " is parked " + issuance.getLastOutcome());
                }
                issuance.allowAnotherGenerate(now);
                audit(GENERATE_ALLOWED_EVENT, loan, issuance, by, "reason:" + reason + ";allowance:"
                        + issuance.getGenerateAllowance() + ";after:" + issuance.getLastOutcome());
            }
            String parkedAs = issuance.getLastOutcome();
            issuance.requeue(now);
            issuanceRepository.save(issuance);
            audit(RETRIED_EVENT, loan, issuance, by, "reason:" + reason + ";wasParked:" + parkedAs);
            return null;
        }
        if (generateAgain) {
            throw new ConflictException("generateAgain applies only to a parked voucher issuance; the voucher of "
                    + loan.getReference() + " is " + issuance.getStatus());
        }
        audit(RETRIED_EVENT, loan, issuance, by, "reason:" + reason + ";was:" + issuance.getStatus());
        return null;
    }

    /** Issuances, newest first, optionally of one status. */
    public Page<StaffLoanVoucherIssuanceResponse> issuances(StaffLoanVoucherIssuanceStatus status,
                                                            Pageable pageable) {
        return read(transaction -> {
            Collection<StaffLoanVoucherIssuanceStatus> statuses = status == null
                    ? EnumSet.allOf(StaffLoanVoucherIssuanceStatus.class) : EnumSet.of(status);
            Page<StaffLoanVoucherIssuance> page = issuanceRepository.findByStatusInOrderByIdDesc(statuses,
                    PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
            List<Long> loanIds = page.getContent().stream().map(StaffLoanVoucherIssuance::getStaffLoanId).distinct()
                    .toList();
            Map<Long, String> references = loanIds.isEmpty() ? Map.of() : loanRepository.findAllById(loanIds)
                    .stream().collect(Collectors.toMap(StaffLoan::getId, StaffLoan::getReference));
            return page.map(issuance -> StaffLoanVoucherIssuanceResponse.of(issuance,
                    references.get(issuance.getStaffLoanId())));
        });
    }

    private void audit(String event, StaffLoan loan, StaffLoanVoucherIssuance issuance, String by, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(event)
                .entityType(ENTITY).entityId(String.valueOf(loan.getId()))
                .actorId(by).channelUsed("admin-portal")
                .detail("reference:" + loan.getReference() + ";issuance:" + issuance.getId() + ";status:"
                        + issuance.getStatus() + ";" + detail));
        log.info("Staff loan {} voucher issuance {}: {} by {}", loan.getReference(), issuance.getId(), event, by);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Seals codes not already kept on the issuance, each on its own. */
    private List<String> seal(StaffLoanVoucherIssuance issuance, List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Set<String> kept = issuance.sealedCodes().stream().map(vault::decrypt).collect(Collectors.toSet());
        List<String> sealed = new ArrayList<>();
        for (String code : new LinkedHashSet<>(codes)) {
            if (code != null && !code.isBlank() && !kept.contains(code)) {
                sealed.add(vault.encrypt(code));
            }
        }
        return sealed;
    }

    private static String masked(RuntimeException cause, List<String> codes) {
        String text = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return GetMoreCodes.maskAll(text, codes);
    }

    private StaffLoanVoucherIssuance lockIssuance(Long issuanceId) {
        return issuanceRepository.lockById(issuanceId).orElseThrow(() -> new NotFoundException(
                "Voucher issuance " + issuanceId + " not found"));
    }

    private Duration staleClaim() {
        return properties.getIssuance().getStaleClaim();
    }

    private LocalDateTime now() {
        return marketTimeZone.nowUtc().truncatedTo(ChronoUnit.MICROS);
    }

    private void count(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC, "outcome", outcome).increment();
        }
    }

    private <T> T write(TransactionCallback<T> work) {
        return writeTransaction.execute(work);
    }

    private <T> T read(TransactionCallback<T> work) {
        return readTransaction.execute(work);
    }

    private static NotFoundException notFound(Long staffLoanId) {
        return new NotFoundException("Staff loan " + staffLoanId + " not found");
    }

    /** What the claim found: an outcome to return, an issuance to clean up first, or a claim to work. */
    private record ClaimStep(Outcome outcome, Claim claim, Long cleanupId) {

        static ClaimStep done(Outcome outcome) {
            return new ClaimStep(outcome, null, null);
        }

        static ClaimStep cleanup(Long issuanceId) {
            return new ClaimStep(Outcome.CANCELLING, null, issuanceId);
        }

        static ClaimStep claimed(Claim claim) {
            return new ClaimStep(null, claim, null);
        }
    }

    /** One attempt's claim: what it calls GetMore with. Holds no code. */
    private record Claim(Long issuanceId, Long staffLoanId, String reference, LocalDateTime claimedAt, String prefix,
                         long ruleId, BigDecimal faceValue, String currency, GetMoreMode storeMode,
                         LocalDateTime firstClaimedAt, LocalDate voucherLastDay) {

        Claim {
            Objects.requireNonNull(claimedAt, "claimedAt");
            Objects.requireNonNull(firstClaimedAt, "firstClaimedAt");
        }
    }

    private enum SettledKind { ISSUED, LOAN_CANCELLED, LOAN_NOT_AWAITING, CLAIM_LOST }

    private record Settled(SettledKind kind, VoucherResponse voucher) {

        static Settled of(SettledKind kind, VoucherResponse voucher) {
            return new Settled(kind, voucher);
        }
    }
}

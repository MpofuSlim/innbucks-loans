package zw.co.innbucks.loans.core.voucher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Grocery vouchers (FR-SGL-033 to FR-SGL-040): issuing one for a disbursement, and what staff can see and do with them.
 * The merchant's side is {@link VoucherRedemptionService}; the daily report {@link VoucherSettlementService}.
 *
 * <p>Staff see a code masked. Only a VOUCHER_SUPPORT user sees it in full, one voucher at a time, with a reason that
 * goes on the audit record with their name (FR-SGL-040). A voucher is only ever sent to the number it was issued to:
 * nobody can redirect one to another phone.
 *
 * <p>A Staff Grocery Loan's voucher can be a GetMore Groceries coupon instead ({@link #issueFromGetMore}): its code is
 * GetMore's, shown exactly as GetMore made it, spent at GetMore, and its spends and refunds are read back from GetMore
 * by {@link GetMoreVoucherSync}, never from a till. The methods that change one lock it ({@code lockById}), so they
 * wait for a sync, a delivery or each other rather than race. It is cancelled only with its loan.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherService {

    static final String ENTITY = "VOUCHER";
    private static final int CODE_ATTEMPTS = 5;
    /** A delivery claimed this long ago and still SENDING lost its outcome (a restart mid-send); it may be resent. */
    static final Duration STALE_SENDING = Duration.ofMinutes(15);
    private static final Set<VoucherStatus> OPEN = EnumSet.of(VoucherStatus.ISSUED, VoucherStatus.PARTIALLY_REDEEMED);

    private final VoucherRepository voucherRepository;
    private final MerchantRepository merchantRepository;
    private final VoucherRedemptionRepository redemptionRepository;
    private final VoucherRefundRepository refundRepository;
    private final VoucherSyncIssueRepository issueRepository;
    private final VoucherDeliveryRepository deliveryRepository;
    private final VoucherCodeVault vault;
    private final VoucherCodeGenerator generator;
    private final VoucherDeliveryDispatcher dispatcher;
    private final VoucherProperties properties;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;
    private final GetMoreAmountBinding getMoreBinding;

    /**
     * Issues the voucher a disbursement pays out as (FR-SGL-033), and sends it to the customer once this transaction
     * commits (FR-SGL-034). Once per disbursement: asked again for the same disbursement, it returns the voucher
     * already issued, and sends nothing.
     *
     * @throws VouchersUnavailableException the voucher code keys are not configured
     * @throws ValidationException          the command is incomplete, or names no merchant there is
     * @throws ConflictException            the disbursement already has a voucher, for a different loan, merchant or
     *                                      amount
     */
    @Transactional
    public VoucherResponse issue(IssueVoucherCommand command) {
        vault.requireConfigured();
        check(command);
        Merchant merchant = merchantRepository.findById(command.merchantId()).orElseThrow(() ->
                new ValidationException("merchantId " + command.merchantId() + " is not a merchant"));
        LocalDateTime now = marketTimeZone.nowUtc();
        String disbursement = command.disbursementReference().strip();
        Voucher earlier = voucherRepository.findByDisbursementReference(disbursement).orElse(null);
        if (earlier != null) {
            if (!earlier.getLoanAccount().equals(command.loanAccount().strip())
                    || !earlier.getMerchant().getId().equals(merchant.getId())
                    || earlier.getFaceValue().compareTo(command.faceValue()) != 0) {
                throw new ConflictException("Disbursement " + disbursement + " already has voucher " + earlier.getId()
                        + ", for a different loan, merchant or amount");
            }
            log.info("Voucher {} already issued for disbursement {}; not issued again", earlier.getId(), disbursement);
            return VoucherResponse.of(earlier, now);
        }
        String code = newCode();
        Voucher voucher;
        try {
            voucher = voucherRepository.saveAndFlush(Voucher.builder()
                    .product(command.product())
                    .disbursementReference(disbursement)
                    .loanAccount(command.loanAccount().strip())
                    .merchant(merchant)
                    .staffMemberId(command.staffMemberId())
                    .customerReference(command.customerReference().strip())
                    .customerName(command.customerName().strip())
                    .customerMsisdn(MsisdnUtils.toE164(command.customerMsisdn()))
                    .codeHmac(vault.fingerprint(code))
                    .codeCiphertext(vault.encrypt(code))
                    .codeLast4(VoucherCodes.lastFour(code))
                    .codeLength(code.length())
                    .faceValue(command.faceValue().setScale(2, RoundingMode.UNNECESSARY))
                    .redeemedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY))
                    .currency(command.currency())
                    .issuedAt(now)
                    .expiresAt(expiryFor(now))
                    .status(VoucherStatus.ISSUED)
                    .deliveryStatus(VoucherDeliveryStatus.PENDING)
                    .deliveryUpdatedAt(now)
                    .build());
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException("A voucher for disbursement " + disbursement + " was issued at the same moment;"
                    + " read it again");
        }
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_ISSUED")
                .entityType(ENTITY).entityId(String.valueOf(voucher.getId()))
                .actorId(authService.getLoggedInUsername()).channelUsed("system")
                .detail("disbursement:" + disbursement + ";loanAccount:" + voucher.getLoanAccount() + ";merchant:"
                        + merchant.getMerchantCode() + ";faceValue:" + voucher.getFaceValue() + " "
                        + voucher.getCurrency() + ";code:" + voucher.maskedCode()
                        + ";expiresAt:" + voucher.getExpiresAt()));
        log.info("Voucher {} ({}) issued for disbursement {} on loan {}: {} {}, until {}", voucher.getId(),
                voucher.maskedCode(), disbursement, voucher.getLoanAccount(), voucher.getFaceValue(),
                voucher.getCurrency(), voucher.getExpiresAt());
        dispatcher.afterCommit(voucher.getId(), VoucherDeliveryDispatcher.SYSTEM);
        return VoucherResponse.of(voucher, now);
    }

    /**
     * Vouchers, newest first. {@code status} is read as of now, so EXPIRED includes open vouchers past their expiry
     * that the job has not marked yet, and ISSUED and PARTIALLY_REDEEMED leave them out. {@code deliveryStatus=FAILED}
     * is the list of vouchers no channel could deliver (FR-SGL-037).
     */
    @Transactional(readOnly = true)
    public Page<VoucherResponse> vouchers(VoucherStatus status, VoucherDeliveryStatus deliveryStatus,
                                          String customerReference, String loanAccount, LocalDate issuedFrom,
                                          LocalDate issuedTo, Pageable pageable) {
        if (issuedFrom != null && issuedTo != null && issuedFrom.isAfter(issuedTo)) {
            throw new ValidationException("issuedFrom (" + issuedFrom + ") must not be after issuedTo (" + issuedTo
                    + ")");
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        Specification<Voucher> filter = (root, query, cb) -> null;
        if (status != null) {
            filter = filter.and(switch (status) {
                case ISSUED, PARTIALLY_REDEEMED -> (root, query, cb) -> cb.and(cb.equal(root.get("status"), status),
                        cb.greaterThan(root.get("expiresAt"), now));
                case EXPIRED -> (root, query, cb) -> cb.or(cb.equal(root.get("status"), status),
                        cb.and(root.get("status").in(OPEN), cb.lessThanOrEqualTo(root.get("expiresAt"), now)));
                default -> (root, query, cb) -> cb.equal(root.get("status"), status);
            });
        }
        if (deliveryStatus != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("deliveryStatus"), deliveryStatus));
        }
        if (StringUtils.isNotBlank(customerReference)) {
            String reference = customerReference.strip().toUpperCase(Locale.ROOT);
            filter = filter.and((root, query, cb) -> cb.equal(cb.upper(root.get("customerReference")), reference));
        }
        if (StringUtils.isNotBlank(loanAccount)) {
            String account = loanAccount.strip();
            filter = filter.and((root, query, cb) -> cb.equal(root.get("loanAccount"), account));
        }
        if (issuedFrom != null) {
            LocalDateTime start = marketTimeZone.startOfDayUtc(issuedFrom);
            filter = filter.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("issuedAt"), start));
        }
        if (issuedTo != null) {
            LocalDateTime end = marketTimeZone.endOfDayUtc(issuedTo);
            filter = filter.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("issuedAt"), end));
        }
        return voucherRepository.findAll(filter, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id"))).map(voucher -> VoucherResponse.of(voucher, now));
    }

    /**
     * A voucher, with every purchase made with it and every attempt to send it; a GetMore voucher also with its refunds
     * and its open sync issues.
     */
    @Transactional(readOnly = true)
    public VoucherDetailResponse voucher(Long id) {
        Voucher voucher = voucherRepository.findById(id).orElseThrow(() -> notFound(id));
        boolean getMore = voucher.isGetMore();
        return new VoucherDetailResponse(VoucherResponse.of(voucher, marketTimeZone.nowUtc()),
                redemptionRepository.findByVoucherIdOrderByIdAsc(id).stream().map(VoucherRedemptionResponse::of)
                        .toList(),
                deliveryRepository.findByVoucherIdOrderByIdAsc(id).stream().map(VoucherDeliveryResponse::of).toList(),
                getMore ? refundRepository.findByVoucherIdOrderByIdAsc(id).stream().map(VoucherRefundResponse::of)
                        .toList() : null,
                getMore ? issueRepository.findByVoucherIdAndClearedAtIsNullOrderByIdAsc(id).stream()
                        .map(issue -> VoucherSyncIssueResponse.of(issue, voucher.getLoanAccount())).toList() : null);
    }

    /**
     * The code in full, for a VOUCHER_SUPPORT user helping the customer (FR-SGL-040). Every reveal is audited with who,
     * when and why; the code itself is never logged.
     */
    @Transactional(readOnly = true)
    public VoucherCodeResponse reveal(Long id, String reason) {
        vault.requireConfigured();
        Voucher voucher = voucherRepository.findById(id).orElseThrow(() -> notFound(id));
        String username = authService.getLoggedInUsername();
        String code = vault.decrypt(voucher.getCodeCiphertext());
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_CODE_REVEALED")
                .entityType(ENTITY).entityId(String.valueOf(id))
                .actorId(username).channelUsed("admin-portal")
                .detail("reason:" + reason.strip() + ";status:" + voucher.statusAt(marketTimeZone.nowUtc())));
        log.info("Voucher {} ({}) shown in full to {}", id, voucher.maskedCode(), username);
        return VoucherCodeResponse.of(id, code, voucher.getCodeSource());
    }

    /**
     * The voucher {@code loanAccount} was paid as, for the borrower it was issued to (FR-SGL-030, FR-SGL-037): its
     * status and balance, and the code itself while it can still be spent, so the SuperApp can show it and its QR
     * code whether or not the SMS arrived. Not audited like a {@link #reveal}: the holder reading their own voucher.
     * Empty when no voucher was issued to them for that loan.
     *
     * <p>A GetMore coupon's code is given exactly as GetMore made it, and its balance is what can still be spent at
     * GetMore as the last sync read it ({@code balanceAsOf}): less what is ordered there and not yet invoiced.</p>
     */
    @Transactional(readOnly = true)
    public Optional<BorrowerVoucherResponse> forBorrower(Long staffMemberId, String loanAccount) {
        LocalDateTime now = marketTimeZone.nowUtc();
        return voucherRepository.findFirstByStaffMemberIdAndLoanAccountOrderByIdDesc(staffMemberId, loanAccount)
                .map(voucher -> {
                    VoucherStatus status = voucher.statusAt(now);
                    boolean spendable = (status == VoucherStatus.ISSUED || status == VoucherStatus.PARTIALLY_REDEEMED)
                            && vault.isConfigured();
                    String code = spendable ? vault.decrypt(voucher.getCodeCiphertext()) : null;
                    if (voucher.isGetMore()) {
                        return new BorrowerVoucherResponse(status, voucher.getFaceValue(), voucher.spendableAtStore(),
                                voucher.getCurrency(), voucher.getExpiresAt(), voucher.maskedCode(), code, code,
                                VoucherCodeSource.GETMORE, getMoreBinding.partialSpending(),
                                voucher.getRedeemedAmount(), simulated(voucher), balanceAsOf(voucher));
                    }
                    return new BorrowerVoucherResponse(status, voucher.getFaceValue(), voucher.balance(),
                            voucher.getCurrency(), voucher.getExpiresAt(), voucher.maskedCode(),
                            code == null ? null : VoucherCodes.display(code),
                            code == null ? null : VoucherCodes.scanValue(code), VoucherCodeSource.LOANS,
                            properties.isPartialRedemptionAllowed(), voucher.getRedeemedAmount(), null, null);
                });
    }

    /**
     * Every spend and refund of the voucher {@code loanReference} was paid as, for its borrower, oldest first, with
     * what was left after each. No code.
     *
     * @throws NotFoundException the loan is not theirs, or has no voucher yet
     */
    @Transactional(readOnly = true)
    public BorrowerVoucherSpendsResponse spendsFor(Long staffMemberId, String loanReference) {
        Voucher voucher = voucherRepository.findFirstByStaffMemberIdAndLoanAccountOrderByIdDesc(staffMemberId,
                loanReference).orElseThrow(() -> new NotFoundException("Loan " + loanReference
                + " has no voucher yet"));
        List<VoucherRedemption> spends = redemptionRepository.findByVoucherIdOrderByIdAsc(voucher.getId());
        List<VoucherRefund> refunds = voucher.isGetMore()
                ? refundRepository.findByVoucherIdOrderByIdAsc(voucher.getId()) : List.of();
        Map<Long, String> outletByOrder = new HashMap<>();
        List<BorrowerVoucherSpendsResponse.Movement> movements = new ArrayList<>();
        for (VoucherRedemption spend : spends) {
            String outlet = StringUtils.defaultIfBlank(spend.getOutletName(), spend.getOutletId());
            if (spend.getExternalOrderId() != null) {
                outletByOrder.putIfAbsent(spend.getExternalOrderId(), outlet);
            }
            movements.add(new BorrowerVoucherSpendsResponse.Movement(BorrowerVoucherSpendsResponse.Kind.SPEND,
                    spend.getAmount(), spend.getBalanceAfter(), spend.getRedeemedAt(), outlet,
                    spend.getExternalOrderNumber()));
        }
        Map<Long, String> orderNumbers = new HashMap<>();
        spends.stream().filter(spend -> spend.getExternalOrderId() != null && spend.getExternalOrderNumber() != null)
                .forEach(spend -> orderNumbers.putIfAbsent(spend.getExternalOrderId(), spend.getExternalOrderNumber()));
        for (VoucherRefund refund : refunds) {
            movements.add(new BorrowerVoucherSpendsResponse.Movement(BorrowerVoucherSpendsResponse.Kind.REFUND,
                    refund.getAmount(), refund.getBalanceAfter(), refund.getRefundedAt(),
                    outletByOrder.get(refund.getExternalOrderId()), orderNumbers.get(refund.getExternalOrderId())));
        }
        movements.sort(Comparator.comparing(BorrowerVoucherSpendsResponse.Movement::at)
                .thenComparing(movement -> movement.kind().ordinal()));
        boolean getMore = voucher.isGetMore();
        return new BorrowerVoucherSpendsResponse(voucher.getLoanAccount(), voucher.getFaceValue(),
                voucher.getRedeemedAmount(), getMore ? voucher.getHeldAmount() : zero(),
                getMore ? voucher.spendableAtStore() : voucher.balance(), voucher.getCurrency(),
                getMore ? balanceAsOf(voucher) : null,
                getMore ? getMoreBinding.partialSpending() : properties.isPartialRedemptionAllowed(),
                getMore ? simulated(voucher) : null, movements);
    }

    /**
     * Stops a voucher before anything is spent with it (FR-SGL-035): a lost phone, a mistaken payout. What becomes of
     * the loan and the money paid to the merchant is a separate decision (OQ-09, and BRD 3.8's reversal under
     * maker-checker).
     *
     * @throws ConflictException it has been spent from, has lapsed, or is already closed; or it is a GetMore coupon,
     *                           which is cancelled only with its loan
     */
    @Transactional
    public VoucherResponse cancel(Long id, String reason) {
        Voucher voucher = voucherRepository.lockById(id).orElseThrow(() -> notFound(id));
        if (voucher.isGetMore()) {
            throw new ConflictException("Voucher " + id + " is the GetMore coupon of loan " + voucher.getLoanAccount()
                    + ": cancel the loan instead (POST /lending/v1/staff-loans/" + voucher.getStaffLoanId()
                    + "/cancel)");
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        VoucherStatus status = voucher.statusAt(now);
        if (status != VoucherStatus.ISSUED) {
            throw new ConflictException(switch (status) {
                case PARTIALLY_REDEEMED -> "Voucher " + id + " has been partly spent and cannot be cancelled";
                case EXPIRED -> "Voucher " + id + " has expired and cannot be cancelled";
                default -> "Voucher " + id + " is already " + status;
            });
        }
        String username = authService.getLoggedInUsername();
        voucher.cancel(username, reason.strip(), now);
        voucherRepository.save(voucher);
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_CANCELLED")
                .entityType(ENTITY).entityId(String.valueOf(id))
                .actorId(username).channelUsed("admin-portal")
                .stateTransitionDelta("{\"from\":\"ISSUED\",\"to\":\"CANCELLED\"}")
                .detail("reason:" + reason.strip() + ";faceValue:" + voucher.getFaceValue() + " "
                        + voucher.getCurrency()));
        log.info("Voucher {} ({}) cancelled by {}", id, voucher.maskedCode(), username);
        return VoucherResponse.of(voucher, now);
    }

    /**
     * Sends the voucher again, to the number it was issued to and no other, with what is left on it: the follow-up to a
     * failed delivery (FR-SGL-037), or a customer who lost the message.
     *
     * @throws ConflictException nothing is left to spend, or it is being sent right now
     */
    @Transactional
    public VoucherResponse resend(Long id) {
        vault.requireConfigured();
        Voucher voucher = voucherRepository.lockById(id).orElseThrow(() -> notFound(id));
        LocalDateTime now = marketTimeZone.nowUtc();
        VoucherStatus status = voucher.statusAt(now);
        if (!status.isOpen()) {
            throw new ConflictException("Voucher " + id + " is " + status + ": there is nothing left to spend, so it is"
                    + " not sent");
        }
        boolean stale = voucher.getDeliveryStatus() == VoucherDeliveryStatus.SENDING
                && voucher.getDeliveryUpdatedAt().isBefore(now.minus(STALE_SENDING));
        if (voucher.getDeliveryStatus() == VoucherDeliveryStatus.PENDING
                || (voucher.getDeliveryStatus() == VoucherDeliveryStatus.SENDING && !stale)) {
            throw new ConflictException("Voucher " + id + " is being sent already");
        }
        String username = authService.getLoggedInUsername();
        VoucherDeliveryStatus before = voucher.getDeliveryStatus();
        voucher.queueDelivery(now);
        voucherRepository.save(voucher);
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_RESENT")
                .entityType(ENTITY).entityId(String.valueOf(id))
                .actorId(username).channelUsed("admin-portal")
                .detail("deliveryWas:" + before + ";recipient:" + MsisdnUtils.mask(voucher.getCustomerMsisdn())));
        log.info("Voucher {} ({}) queued to be sent again by {}; delivery was {}", id, voucher.maskedCode(), username,
                before);
        dispatcher.afterCommit(id, username);
        return VoucherResponse.of(voucher, now);
    }

    // ---- GetMore coupons --------------------------------------------------------------------------------------

    /**
     * Keeps a GetMore coupon as a Staff Grocery Loan's voucher, in the issuance's own transaction, and sends it to the
     * borrower once that commits, exactly as {@link #issue} does. The code is fingerprinted in its normalised form and
     * sealed exactly as GetMore returned it. The disbursement reference names the coupon ({@code GETMORE-<couponId>},
     * {@code GETMORE-SIM-} when simulated), and asked again for the same coupon it returns the voucher already kept.
     *
     * @throws VouchersUnavailableException the voucher code keys are not configured
     * @throws ValidationException          the command is incomplete, or names no merchant there is
     * @throws ConflictException            the loan already has a voucher of another coupon, or the coupon's
     *                                      disbursement reference or code is another voucher's
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherResponse issueFromGetMore(GetMoreVoucherCommand command) {
        vault.requireConfigured();
        String normalised = check(command);
        Merchant merchant = merchantRepository.findById(command.merchantId()).orElseThrow(() ->
                new ValidationException("merchantId " + command.merchantId() + " is not a merchant"));
        LocalDateTime now = marketTimeZone.nowUtc();
        String disbursement = getMoreDisbursementReference(command.storeMode(), command.couponId());
        Voucher earlier = voucherRepository.findByDisbursementReference(disbursement)
                .or(() -> voucherRepository.findByStaffLoanId(command.staffLoanId())).orElse(null);
        if (earlier != null) {
            if (!disbursement.equals(earlier.getDisbursementReference())
                    || !command.staffLoanId().equals(earlier.getStaffLoanId())
                    || !earlier.getMerchant().getId().equals(merchant.getId())
                    || earlier.getFaceValue().compareTo(command.faceValue()) != 0) {
                throw new ConflictException("Loan " + command.loanAccount() + " already has voucher " + earlier.getId()
                        + " (" + earlier.getDisbursementReference() + "), not of GetMore coupon "
                        + command.couponId());
            }
            log.info("Voucher {} already kept for GetMore coupon {} of loan {}; not issued again", earlier.getId(),
                    command.couponId(), command.loanAccount());
            return VoucherResponse.of(earlier, now);
        }
        String fingerprint = vault.fingerprint(normalised);
        if (voucherRepository.existsByCodeHmac(fingerprint)) {
            throw new ConflictException("GetMore coupon " + command.couponId() + " ("
                    + GetMoreCodes.maskedCode(command.codeAsStored()) + ") has the code of another voucher");
        }
        String code = command.codeAsStored();
        Voucher voucher;
        try {
            voucher = voucherRepository.saveAndFlush(Voucher.builder()
                    .product(VoucherProduct.STAFF_GROCERY_LOAN)
                    .disbursementReference(disbursement)
                    .loanAccount(command.loanAccount().strip())
                    .merchant(merchant)
                    .staffMemberId(command.staffMemberId())
                    .customerReference(command.customerReference().strip())
                    .customerName(command.customerName().strip())
                    .customerMsisdn(MsisdnUtils.toE164(command.customerMsisdn()))
                    .codeHmac(fingerprint)
                    .codeCiphertext(vault.encrypt(code))
                    .codeLast4(GetMoreCodes.lastFour(code))
                    .codeLength(code.length())
                    .faceValue(command.faceValue().setScale(2, RoundingMode.UNNECESSARY))
                    .redeemedAmount(zero())
                    .currency(command.currency())
                    .issuedAt(now)
                    .expiresAt(expiryFor(now))
                    .status(VoucherStatus.ISSUED)
                    .deliveryStatus(VoucherDeliveryStatus.PENDING)
                    .deliveryUpdatedAt(now)
                    .codeSource(VoucherCodeSource.GETMORE)
                    .storeMode(command.storeMode())
                    .staffLoanId(command.staffLoanId())
                    .externalCouponId(command.couponId())
                    .externalRuleId(command.ruleId())
                    .codePrefix(command.codePrefix())
                    .build());
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException("A voucher for loan " + command.loanAccount() + " was kept at the same moment;"
                    + " read it again");
        }
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_ISSUED")
                .entityType(ENTITY).entityId(String.valueOf(voucher.getId()))
                .actorId(authService.getLoggedInUsername()).channelUsed("system")
                .detail("disbursement:" + disbursement + ";loanAccount:" + voucher.getLoanAccount() + ";merchant:"
                        + merchant.getMerchantCode() + ";faceValue:" + voucher.getFaceValue() + " "
                        + voucher.getCurrency() + ";code:" + voucher.maskedCode() + ";expiresAt:"
                        + voucher.getExpiresAt() + ";source:getmore;mode:" + command.storeMode() + ";coupon:"
                        + command.couponId() + ";rule:" + command.ruleId()));
        log.info("Voucher {} ({}) kept for GetMore coupon {} ({}) on loan {}: {} {}, until {}", voucher.getId(),
                voucher.maskedCode(), command.couponId(), command.storeMode(), voucher.getLoanAccount(),
                voucher.getFaceValue(), voucher.getCurrency(), voucher.getExpiresAt());
        dispatcher.afterCommit(voucher.getId(), VoucherDeliveryDispatcher.SYSTEM);
        return VoucherResponse.of(voucher, now);
    }

    /** The disbursement a GetMore coupon is: {@code GETMORE-<couponId>}, or {@code GETMORE-SIM-<couponId>}. */
    public static String getMoreDisbursementReference(GetMoreMode storeMode, Long couponId) {
        return (storeMode == GetMoreMode.SIMULATED ? "GETMORE-SIM-" : "GETMORE-") + couponId;
    }

    /**
     * Cancels a GetMore voucher with its loan, in the cancellation's transaction, once its coupon is withdrawn at
     * GetMore. Only a voucher nothing was ever spent from: nothing spent, refunded or held, and ISSUED, or stopped on a
     * cancellation's way before anything was spent ({@link Voucher#cancellableWithLoan}).
     *
     * @throws ConflictException it is not a GetMore voucher, or it was used
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherResponse cancelGetMoreForLoan(Long voucherId, String by, String reason, LocalDateTime now) {
        Voucher voucher = lockGetMore(voucherId);
        VoucherStatus status = voucher.statusAt(now);
        boolean untouched = voucher.getRedeemedAmount().signum() == 0 && voucher.getRefundedAmount().signum() == 0
                && voucher.getHeldAmount().signum() == 0;
        if (!untouched || !voucher.cancellableWithLoan(now)) {
            throw new ConflictException("Voucher " + voucherId + " is " + status + " with " + voucher.getCurrency()
                    + " " + voucher.getRedeemedAmount() + " spent, " + voucher.getRefundedAmount() + " refunded and "
                    + voucher.getHeldAmount() + " held at the till: a voucher that was used cannot be cancelled");
        }
        VoucherStatus before = voucher.getStatus();
        voucher.cancel(by, reason.strip(), now);
        voucherRepository.save(voucher);
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_CANCELLED")
                .entityType(ENTITY).entityId(String.valueOf(voucherId))
                .actorId(by).channelUsed("admin-portal")
                .stateTransitionDelta("{\"from\":\"" + before + "\",\"to\":\"CANCELLED\"}")
                .detail("reason:" + reason.strip() + ";faceValue:" + voucher.getFaceValue() + " "
                        + voucher.getCurrency() + ";loanAccount:" + voucher.getLoanAccount() + ";coupon:"
                        + voucher.getExternalCouponId()));
        log.info("Voucher {} ({}) cancelled with loan {} by {}", voucherId, voucher.maskedCode(),
                voucher.getLoanAccount(), by);
        return VoucherResponse.of(voucher, now);
    }

    /**
     * Stops an open GetMore voucher now ({@code reason}), in the caller's transaction: it lapses at this moment and
     * nothing more can be spent. A voucher already closed is left as it is.
     *
     * @return whether it was stopped
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean stopGetMoreNow(Long voucherId, VoucherStopReason reason, LocalDateTime now) {
        Voucher voucher = lockGetMore(voucherId);
        if (!voucher.stopNow(reason, now)) {
            return false;
        }
        voucherRepository.save(voucher);
        auditStopped(voucher, reason, "admin-portal");
        return true;
    }

    /**
     * Records that the GetMore voucher's code is gone at GetMore, in the caller's transaction. The first confirmation
     * is kept, and audited.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCouponWithdrawn(Long voucherId, LocalDateTime at) {
        Voucher voucher = lockGetMore(voucherId);
        if (voucher.getCouponWithdrawnAt() != null) {
            return;
        }
        voucher.couponWithdrawn(at);
        voucherRepository.save(voucher);
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_COUPON_WITHDRAWN")
                .entityType(ENTITY).entityId(String.valueOf(voucherId))
                .actorId(authService.getLoggedInUsername()).channelUsed("admin-portal")
                .detail("loanAccount:" + voucher.getLoanAccount() + ";coupon:" + voucher.getExternalCouponId()
                        + ";code:" + voucher.maskedCode() + ";at:" + at));
    }

    /** Records that GetMore confirmed the coupon's expiry date. */
    @Transactional
    public void markCouponExpirySet(Long voucherId, LocalDateTime at) {
        Voucher voucher = lockGetMore(voucherId);
        voucher.couponExpirySet(at);
        voucherRepository.save(voucher);
    }

    /**
     * STAGING ONLY: stops a simulated voucher now, as GetMore's simulated till already expired its coupon. A live
     * voucher is refused.
     *
     * @throws ConflictException it is not a simulated GetMore voucher, or it is already closed
     */
    @Transactional
    public VoucherResponse expireNowForSimulation(Long voucherId) {
        Voucher voucher = lockGetMore(voucherId);
        if (voucher.getStoreMode() != GetMoreMode.SIMULATED) {
            throw new ConflictException("Voucher " + voucherId + " is not a simulated GetMore voucher");
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        if (!voucher.stopNow(VoucherStopReason.SIMULATOR_EXPIRED, now)) {
            throw new ConflictException("Voucher " + voucherId + " is " + voucher.statusAt(now) + " already");
        }
        voucherRepository.save(voucher);
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_EXPIRED_BY_SIMULATOR")
                .entityType(ENTITY).entityId(String.valueOf(voucherId))
                .actorId(authService.getLoggedInUsername()).channelUsed("getmore-simulator")
                .detail("loanAccount:" + voucher.getLoanAccount() + ";coupon:" + voucher.getExternalCouponId()
                        + ";expiresAt:" + voucher.getExpiresAt()));
        log.info("Simulated voucher {} ({}) expired now on request", voucherId, voucher.maskedCode());
        return VoucherResponse.of(voucher, now);
    }

    private Voucher lockGetMore(Long voucherId) {
        Voucher voucher = voucherRepository.lockById(voucherId).orElseThrow(() -> notFound(voucherId));
        if (!voucher.isGetMore()) {
            throw new ConflictException("Voucher " + voucherId + " is not a GetMore voucher");
        }
        return voucher;
    }

    private void auditStopped(Voucher voucher, VoucherStopReason reason, String channel) {
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_STOPPED")
                .entityType(ENTITY).entityId(String.valueOf(voucher.getId()))
                .actorId(authService.getLoggedInUsername()).channelUsed(channel)
                .detail("reason:" + reason + ";loanAccount:" + voucher.getLoanAccount() + ";spent:"
                        + voucher.getRedeemedAmount() + " " + voucher.getCurrency() + ";expiresAt:"
                        + voucher.getExpiresAt()));
        log.info("Voucher {} ({}) stopped: {}", voucher.getId(), voucher.maskedCode(), reason);
    }

    private Boolean simulated(Voucher voucher) {
        return voucher.getStoreMode() == GetMoreMode.SIMULATED ? Boolean.TRUE : null;
    }

    private static LocalDateTime balanceAsOf(Voucher voucher) {
        return voucher.getSyncedAt() == null ? voucher.getIssuedAt() : voucher.getSyncedAt();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * When a voucher issued at {@code issuedAt} lapses: the end of its last market day, to the microsecond the column
     * keeps. A GetMore issuance reads it before the coupon is made, so the store's rule can be checked against it.
     */
    public LocalDateTime expiryFor(LocalDateTime issuedAt) {
        LocalDate lastDay = marketTimeZone.localDay(issuedAt).plusDays(properties.getValidityDays());
        return marketTimeZone.endOfDayUtc(lastDay).truncatedTo(ChronoUnit.MICROS);
    }

    /** A code no voucher has; a clash is next to impossible (10^15 codes), and is simply drawn again. */
    private String newCode() {
        for (int attempt = 1; attempt <= CODE_ATTEMPTS; attempt++) {
            String code = generator.next(properties.getCodeLength());
            if (!voucherRepository.existsByCodeHmac(vault.fingerprint(code))) {
                return code;
            }
            log.warn("New voucher code clashed with an existing one (attempt {}); drawing again", attempt);
        }
        throw new IllegalStateException("Could not draw an unused voucher code in " + CODE_ATTEMPTS + " attempts");
    }

    private static void check(IssueVoucherCommand command) {
        if (command.product() == null) {
            throw new ValidationException("product is required");
        }
        if (command.merchantId() == null) {
            throw new ValidationException("merchantId is required");
        }
        for (String[] field : new String[][]{{"disbursementReference", command.disbursementReference()},
                {"loanAccount", command.loanAccount()}, {"customerReference", command.customerReference()},
                {"customerName", command.customerName()}}) {
            if (StringUtils.isBlank(field[1])) {
                throw new ValidationException(field[0] + " is required");
            }
        }
        if (command.customerMsisdn() == null || !command.customerMsisdn().strip().matches(
                MsisdnUtils.ZIMBABWE_MOBILE_REGEX)) {
            throw new ValidationException("customerMsisdn " + MsisdnUtils.ZIMBABWE_MOBILE_MESSAGE);
        }
        if (command.faceValue() == null || command.faceValue().signum() <= 0
                || command.faceValue().stripTrailingZeros().scale() > 2) {
            throw new ValidationException("faceValue must be above 0, in at most 2 decimal places");
        }
        if (command.currency() == null || !command.currency().matches("[A-Z]{3}")) {
            throw new ValidationException("currency must be a three-letter ISO code, e.g. USD");
        }
    }

    /** Checks a GetMore command; the code in its normalised form, for the fingerprint. */
    private static String check(GetMoreVoucherCommand command) {
        for (Object[] field : new Object[][]{{"staffLoanId", command.staffLoanId()},
                {"merchantId", command.merchantId()}, {"storeMode", command.storeMode()},
                {"couponId", command.couponId()}, {"ruleId", command.ruleId()}}) {
            if (field[1] == null) {
                throw new ValidationException(field[0] + " is required");
            }
        }
        if (command.storeMode() == GetMoreMode.OFF) {
            throw new ValidationException("storeMode must be LIVE or SIMULATED");
        }
        for (String[] field : new String[][]{{"loanAccount", command.loanAccount()},
                {"customerReference", command.customerReference()}, {"customerName", command.customerName()},
                {"codePrefix", command.codePrefix()}}) {
            if (StringUtils.isBlank(field[1])) {
                throw new ValidationException(field[0] + " is required");
            }
        }
        if (command.customerMsisdn() == null || !command.customerMsisdn().strip().matches(
                MsisdnUtils.ZIMBABWE_MOBILE_REGEX)) {
            throw new ValidationException("customerMsisdn " + MsisdnUtils.ZIMBABWE_MOBILE_MESSAGE);
        }
        if (command.faceValue() == null || command.faceValue().signum() <= 0
                || command.faceValue().stripTrailingZeros().scale() > 2) {
            throw new ValidationException("faceValue must be above 0, in at most 2 decimal places");
        }
        if (command.currency() == null || !command.currency().matches("[A-Z]{3}")) {
            throw new ValidationException("currency must be a three-letter ISO code, e.g. USD");
        }
        String code = command.codeAsStored();
        String normalised = GetMoreCodes.normalize(code).orElseThrow(() -> new ValidationException(
                "the GetMore code (" + GetMoreCodes.maskedCode(code) + ") is not a GetMore code"));
        if (!GetMoreCodes.hasPrefix(code, command.codePrefix())) {
            throw new ValidationException("the GetMore code (" + GetMoreCodes.maskedCode(code) + ") is not on the"
                    + " issuance's prefix");
        }
        return normalised;
    }

    static NotFoundException notFound(Long id) {
        return new NotFoundException("Voucher " + id + " not found");
    }

    /** Vouchers to mark EXPIRED; read by the expiry job. */
    static Set<VoucherStatus> open() {
        return OPEN;
    }
}

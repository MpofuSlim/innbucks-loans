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
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Grocery vouchers (FR-SGL-033 to FR-SGL-040): issuing one for a disbursement, and what staff can see and do with them.
 * The merchant's side is {@link VoucherRedemptionService}; the daily report {@link VoucherSettlementService}.
 *
 * <p>Staff see a code masked. Only a VOUCHER_SUPPORT user sees it in full, one voucher at a time, with a reason that
 * goes on the audit record with their name (FR-SGL-040). A voucher is only ever sent to the number it was issued to:
 * nobody can redirect one to another phone.
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
    private final VoucherDeliveryRepository deliveryRepository;
    private final VoucherCodeVault vault;
    private final VoucherCodeGenerator generator;
    private final VoucherDeliveryDispatcher dispatcher;
    private final VoucherProperties properties;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * Issues the voucher a disbursement pays out as (FR-SGL-033), and sends it to the customer once this transaction
     * commits (FR-SGL-034). Once per disbursement: asked again for the same disbursement, it returns the voucher already
     * issued, and sends nothing.
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

    /** A voucher, with every purchase made with it and every attempt to send it. */
    @Transactional(readOnly = true)
    public VoucherDetailResponse voucher(Long id) {
        Voucher voucher = voucherRepository.findById(id).orElseThrow(() -> notFound(id));
        return new VoucherDetailResponse(VoucherResponse.of(voucher, marketTimeZone.nowUtc()),
                redemptionRepository.findByVoucherIdOrderByIdAsc(id).stream().map(VoucherRedemptionResponse::of)
                        .toList(),
                deliveryRepository.findByVoucherIdOrderByIdAsc(id).stream().map(VoucherDeliveryResponse::of).toList());
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
        return VoucherCodeResponse.of(id, code);
    }

    /**
     * The voucher {@code loanAccount} was paid as, for the borrower it was issued to (FR-SGL-030, FR-SGL-037): its
     * status and balance, and the code itself while it can still be spent, so the SuperApp can show it and its QR
     * code whether or not the SMS arrived. Not audited like a {@link #reveal}: the holder reading their own voucher.
     * Empty when no voucher was issued to them for that loan.
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
                    return new BorrowerVoucherResponse(status, voucher.getFaceValue(), voucher.balance(),
                            voucher.getCurrency(), voucher.getExpiresAt(), voucher.maskedCode(),
                            code == null ? null : VoucherCodes.display(code),
                            code == null ? null : VoucherCodes.scanValue(code));
                });
    }

    /**
     * Stops a voucher before anything is spent with it (FR-SGL-035): a lost phone, a mistaken payout. What becomes of
     * the loan and the money paid to the merchant is a separate decision (OQ-09, and BRD 3.8's reversal under
     * maker-checker).
     *
     * @throws ConflictException it has been spent from, has lapsed, or is already closed
     */
    @Transactional
    public VoucherResponse cancel(Long id, String reason) {
        Voucher voucher = voucherRepository.lockById(id).orElseThrow(() -> notFound(id));
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

    /** The end of the last market day the voucher is good for, to the microsecond the column keeps. */
    private LocalDateTime expiryFor(LocalDateTime issuedAt) {
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

    static NotFoundException notFound(Long id) {
        return new NotFoundException("Voucher " + id + " not found");
    }

    /** Vouchers to mark EXPIRED; read by the expiry job. */
    static Set<VoucherStatus> open() {
        return OPEN;
    }
}

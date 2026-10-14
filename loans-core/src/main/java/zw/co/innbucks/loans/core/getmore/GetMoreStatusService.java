package zw.co.innbucks.loans.core.getmore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.getmore.live.LiveAmountModel;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceRepository;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceStatus;
import zw.co.innbucks.loans.core.voucher.GetMoreDataGuard;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssue;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueRepository;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueResponse;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What {@code GET /lending/v1/getmore} and {@code GET /lending/v1/voucher-sync-issues} show: GetMore's state on this
 * host, read from loans' own records (and, only when asked, the sales rule read from GetMore itself, with no
 * transaction open), and the problems syncs found.
 */
@Slf4j
@Service
public class GetMoreStatusService {

    /** The issuance states an operator watches: every one that is not yet final. */
    static final List<StaffLoanVoucherIssuanceStatus> OPEN_ISSUANCES = List.of(
            StaffLoanVoucherIssuanceStatus.PENDING, StaffLoanVoucherIssuanceStatus.CALLING,
            StaffLoanVoucherIssuanceStatus.PARKED, StaffLoanVoucherIssuanceStatus.CANCELLING);

    private final GetMoreProperties properties;
    private final GetMoreAmountBinding binding;
    private final GetMoreCoupons coupons;
    private final MerchantRepository merchantRepository;
    private final StaffLoanVoucherIssuanceRepository issuanceRepository;
    private final VoucherSyncIssueRepository issueRepository;
    private final VoucherRepository voucherRepository;
    private final TransactionTemplate readTransaction;

    public GetMoreStatusService(GetMoreProperties properties, GetMoreAmountBinding binding, GetMoreCoupons coupons,
                                MerchantRepository merchantRepository,
                                StaffLoanVoucherIssuanceRepository issuanceRepository,
                                VoucherSyncIssueRepository issueRepository, VoucherRepository voucherRepository,
                                PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.binding = binding;
        this.coupons = coupons;
        this.merchantRepository = merchantRepository;
        this.issuanceRepository = issuanceRepository;
        this.issueRepository = issueRepository;
        this.voucherRepository = voucherRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * GetMore's state on this host. Every count is of the store this host is in (LIVE when GetMore is OFF): a host
     * moved from SIMULATED to LIVE keeps its simulated rows, never synced again, and they are not reported as live work
     * (§6.3). With {@code checkRule}, the sales rule is read from GetMore as well; a failure to read it is part of the
     * answer ({@code ruleError}), never an error of its own.
     */
    public GetMoreStatusResponse status(boolean checkRule) {
        GetMoreCalls.requireNoTransaction();
        GetMoreMode mode = properties.getMode();
        GetMoreMode store = mode == GetMoreMode.OFF ? GetMoreMode.LIVE : mode;
        GetMoreStatusResponse counted = readTransaction.execute(status -> {
            Merchant merchant = merchantRepository.findByStaffLoanMerchantTrue().orElse(null);
            List<String> warnings = new ArrayList<>();
            switch (mode) {
                case OFF -> warnings.add("GetMore is OFF: an accepted Staff Grocery Loan waits AWAITING_DISBURSEMENT"
                        + " with no voucher. Set GETMORE_MODE.");
                case LIVE -> {
                    if (properties.getLive().getAmountModel() == LiveAmountModel.NONE) {
                        warnings.add("LIVE reads GetMore but issues no voucher until GETMORE_LIVE_AMOUNT_MODEL is"
                                + " set");
                    }
                }
                case SIMULATED -> {
                }
            }
            GetMoreDataGuard.merchantWarning(mode, merchant).ifPresent(warnings::add);
            Map<String, Long> issuances = new LinkedHashMap<>();
            for (StaffLoanVoucherIssuanceStatus open : OPEN_ISSUANCES) {
                issuances.put(open.name(), issuanceRepository.countByStatusAndStoreMode(open, store));
            }
            return new GetMoreStatusResponse(mode, properties.getRuleId(), properties.couponPrefixFor(mode),
                    mode == GetMoreMode.LIVE ? host(properties.getLive().getBaseUrl()) : null, binding.name(),
                    binding.partialSpending(), merchant == null ? null
                    : new GetMoreStatusResponse.StaffLoanMerchant(merchant.getMerchantCode(),
                    merchant.getVoucherStore()), List.copyOf(warnings), issuances,
                    issueRepository.countOpenForStoreMode(store),
                    voucherRepository.countByStoreModeAndSyncBlockedByIsNotNull(store),
                    voucherRepository.countByStoreModeAndHeldAmountGreaterThan(store, BigDecimal.ZERO), null, null);
        });
        if (!checkRule) {
            return counted;
        }
        GetMoreSalesRule rule = null;
        String ruleError = null;
        try {
            rule = coupons.rule(properties.getRuleId());
        } catch (GetMoreException failed) {
            ruleError = errorCode(failed);
            log.warn("GetMore: sales rule {} could not be read: {}", properties.getRuleId(), failed.getMessage());
        }
        return new GetMoreStatusResponse(counted.mode(), counted.ruleId(), counted.couponPrefix(), counted.liveHost(),
                counted.amountModel(), counted.partialSpending(), counted.staffLoanMerchant(), counted.warnings(),
                counted.issuances(), counted.openSyncIssues(), counted.blockedVouchers(), counted.heldVouchers(),
                rule, ruleError);
    }

    /** Sync issues, oldest first: only the open ones with {@code open}, every one ever seen without. */
    public Page<VoucherSyncIssueResponse> syncIssues(boolean open, Pageable pageable) {
        return readTransaction.execute(status -> {
            Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
            Page<VoucherSyncIssue> issues = open ? issueRepository.findByClearedAtIsNullOrderByFirstSeenAtAscIdAsc(page)
                    : issueRepository.findAllByOrderByFirstSeenAtAscIdAsc(page);
            List<Long> voucherIds = issues.getContent().stream().map(VoucherSyncIssue::getVoucherId).distinct()
                    .toList();
            Map<Long, String> loans = voucherIds.isEmpty() ? Map.of() : voucherRepository.findAllById(voucherIds)
                    .stream().collect(Collectors.toMap(Voucher::getId, Voucher::getLoanAccount));
            return issues.map(issue -> VoucherSyncIssueResponse.of(issue, loans.get(issue.getVoucherId())));
        });
    }

    /** {@code GETMORE_UNAVAILABLE: AUTH}, {@code GETMORE_REJECTED: NOT_FOUND}: the API's code and the reason. */
    static String errorCode(GetMoreException failed) {
        return switch (failed) {
            case GetMoreUnavailableException unavailable -> "GETMORE_UNAVAILABLE: " + unavailable.reason();
            case GetMoreRejectedException rejected -> "GETMORE_REJECTED: " + rejected.reason();
            case GetMoreOutcomeUnknownException unknown -> "GETMORE_OUTCOME_UNKNOWN";
        };
    }

    private static String host(String url) {
        try {
            return url == null ? null : URI.create(url.strip()).getHost();
        } catch (IllegalArgumentException unparsable) {
            return null;
        }
    }
}

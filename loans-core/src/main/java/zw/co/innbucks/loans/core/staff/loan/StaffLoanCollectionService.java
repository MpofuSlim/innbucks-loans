package zw.co.innbucks.loans.core.staff.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.merchant.VoucherStore;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.Excluded;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.ExclusionReason;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.LateAdjustment;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.Line;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.MerchantSettlement;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.PreviousCycle;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.SettlementLine;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.SettlementTotals;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.Totals;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncTrigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherSync;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherRedemption;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionRepository;
import zw.co.innbucks.loans.core.voucher.VoucherRefund;
import zw.co.innbucks.loans.core.voucher.VoucherRefundRepository;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import static zw.co.innbucks.loans.core.voucher.VoucherSettlementService.cell;

/**
 * What is owed on a Staff Grocery Loan payday, the GetMore sync before it, and finalising it: the collection
 * statement (D24). For each loan due that payday, what to collect from salary, as its GetMore voucher stood at the
 * cut-off, 00:00 market time on the payday ({@link StaffLoanOwed}); and what to pay GetMore for the cycle: every
 * sale less every refund of the store's vouchers made before the cut-off and not paid for by an earlier payday,
 * whichever loan it belongs to.
 *
 * <p>Finalising a payday keeps its lines as they were sent to payroll and stamps the GetMore rows it paid for
 * ({@code settlement_cycle}), in one transaction. A sale or refund made before the cut-off but recorded after that is
 * paid in the next payday, where it shows {@code late}, and listed against its loan in {@code lateAdjustments}. Nothing
 * here moves money, and {@link StaffLoan#outstanding()} is unchanged: the arrears report and a write-off still read the
 * loan's whole repayment (Q2).</p>
 *
 * <p>On staging's SIMULATED host it describes the simulated store, says so, and is not for payroll; on every other host
 * (OFF included) it describes the LIVE store. A loan whose voucher is (or is being issued) in the other store is
 * excluded either way. The payday sync reads GetMore page by page on request; no job runs any of this.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffLoanCollectionService {

    static final String FINALISED_EVENT = "STAFF_LOAN_COLLECTION_FINALISED";
    static final String ENTITY = "STAFF_LOAN_COLLECTION";
    /** What a simulated store's statement says: a staging test, never a payroll instruction. */
    public static final String NOT_FOR_PAYROLL = "SIMULATED: not for payroll";
    static final int DEFAULT_SYNC_PAGE = 50;
    static final int MAX_SYNC_PAGE = 200;
    /** A payday sync page stops after this many vouchers in a row GetMore could not be read for. */
    static final int SYNC_STOP_AFTER_UNAVAILABLE = 3;
    /** A payday sync page starts no voucher after this long: a slow GetMore never pins a request for minutes. */
    static final Duration SYNC_PAGE_BUDGET = Duration.ofSeconds(60);
    /** The advisory lock a finalisation takes, per store. */
    static final String FINALISATION_LOCK = "staff-loan-collections:";
    /** Rows stamped per statement: an IN list well inside what a driver binds. */
    static final int STAMP_CHUNK = 500;
    static final int MAX_REASON = 255;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final Set<StaffLoanStatus> DUE = EnumSet.of(StaffLoanStatus.AWAITING_DISBURSEMENT,
            StaffLoanStatus.DISBURSED, StaffLoanStatus.WRITTEN_OFF);
    private static final String[] CSV_HEADER = {"loanReference", "employeeNumber", "fullName", "status",
            "treatment", "voucherId", "voucherStateAtCutoff", "stoppedReason", "currency", "faceValue", "spentNet",
            "refunded", "held", "openBalance", "amountToCollect", "final", "flags", "lastSyncedAt", "simulated"};

    private final StaffLoanRepository loanRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository redemptionRepository;
    private final VoucherRefundRepository refundRepository;
    private final VoucherSyncIssueRepository issueRepository;
    private final StaffLoanCollectionCycleRepository cycleRepository;
    private final StaffLoanCollectionLineRepository lineRepository;
    private final StaffLoanVoucherIssuanceRepository issuanceRepository;
    private final MerchantRepository merchantRepository;
    private final GetMoreVoucherSync sync;
    private final StaffLoanVoucherIssuer issuer;
    private final StaffLoanProperties properties;
    private final GetMoreProperties getMore;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * A payday and what bounds it.
     *
     * @param cutoff         00:00 market time on the payday, UTC
     * @param previousCutoff the same on the previous payday
     * @param storeMode      the store it describes
     * @param simulated      whether that is staging's simulated store
     * @param expiresFrom    the earliest expiry of a voucher whose sales or refunds can fall into the cycle
     */
    record Period(LocalDate payday, LocalDateTime cutoff, LocalDate previousPayday, LocalDateTime previousCutoff,
                  GetMoreMode storeMode, boolean simulated, LocalDateTime expiresFrom) {
    }

    /** The report, and the GetMore rows its settlement takes (what a finalisation stamps). */
    private record Built(StaffLoanCollectionReport report, List<Long> spendIds, List<Long> refundIds) {
    }

    /**
     * The payday, refused when it is not a repayment day (day {@code loans.staff-loans.repayment-day} of its month).
     *
     * @throws ValidationException not a repayment day
     */
    Period period(LocalDate payday) {
        int repaymentDay = properties.getRepaymentDay();
        if (payday == null || payday.getDayOfMonth() != repaymentDay) {
            throw new ValidationException(payday + " is not a payday: Staff Grocery Loans are collected on day "
                    + repaymentDay + " of each month");
        }
        boolean simulated = getMore.getMode() == GetMoreMode.SIMULATED;
        LocalDate previousPayday = payday.minusMonths(1);
        LocalDateTime previousCutoff = marketTimeZone.startOfDayUtc(previousPayday);
        return new Period(payday, marketTimeZone.startOfDayUtc(payday), previousPayday, previousCutoff,
                simulated ? GetMoreMode.SIMULATED : GetMoreMode.LIVE, simulated,
                previousCutoff.minus(getMore.getSync().getAfterExpiry()));
    }

    /**
     * The payday's statement: a preview until it is finalised, the finalised lines after.
     *
     * @throws ValidationException not a repayment day
     */
    @Transactional(readOnly = true)
    public StaffLoanCollectionReport report(LocalDate payday) {
        Period period = period(payday);
        return build(period, cycleRepository.find(payday, period.storeMode()).orElse(null)).report();
    }

    /** The statement's lines as a spreadsheet, one row each, for payroll. */
    @Transactional(readOnly = true)
    public StaffLoanCollectionReport.Csv csv(LocalDate payday) {
        StaffLoanCollectionReport report = report(payday);
        StringBuilder out = new StringBuilder(String.join(",", CSV_HEADER)).append("\r\n");
        for (Line line : report.lines()) {
            out.append(String.join(",", cell(line.loanReference()), cell(line.employeeNumber()),
                            cell(line.fullName()), line.status().name(), line.treatment().name(),
                            line.voucherId() == null ? "" : String.valueOf(line.voucherId()),
                            line.voucherStateAtCutoff() == null ? "" : line.voucherStateAtCutoff().name(),
                            line.stoppedReason() == null ? "" : line.stoppedReason().name(), cell(line.currency()),
                            line.faceValue().toPlainString(), line.spentNet().toPlainString(),
                            line.refunded().toPlainString(), line.held().toPlainString(),
                            line.openBalance().toPlainString(), line.amountToCollect().toPlainString(),
                            String.valueOf(line.finalAmount()),
                            cell(line.flags().stream().map(Enum::name).collect(Collectors.joining(","))),
                            line.lastSyncedAt() == null ? "" : marketTimeZone.render(line.lastSyncedAt()),
                            String.valueOf(line.simulated())))
                    .append("\r\n");
        }
        return new StaffLoanCollectionReport.Csv(report.payday(), report.simulated(), out.toString());
    }

    /**
     * Finalises the payday in this host's store, in one transaction: keeps its lines as they are sent to payroll and
     * stamps the GetMore sales and refunds its settlement takes, so one recorded later is paid in the next payday.
     * Refused before the cut-off, a second time, after a later payday was finalised, and, unless
     * {@code acceptNonFinal} with a reason, while a line is not final or a voucher was not read from GetMore since the
     * cut-off. The store's finalisations run one at a time (a transaction-scoped advisory lock taken before anything
     * is read), so each sees what the previous one committed. Audited.
     *
     * @return the finalised statement
     * @throws ValidationException not a repayment day; {@code acceptNonFinal} with no reason
     * @throws ConflictException   refused, as above
     */
    @Transactional
    public StaffLoanCollectionReport finalise(LocalDate payday, FinaliseCollectionRequest request) {
        Period period = period(payday);
        boolean acceptNonFinal = request != null && request.acceptNonFinalAsked();
        String reason = request == null ? null : StringUtils.trimToNull(request.reason());
        if (acceptNonFinal && reason == null) {
            throw new ValidationException("reason is required to finalise with acceptNonFinal");
        }
        if (reason != null && reason.length() > MAX_REASON) {
            throw new ValidationException("reason must be at most " + MAX_REASON + " characters");
        }
        GetMoreMode mode = period.storeMode();
        // First, before anything is read: two finalisations of one store, of one payday or two, could otherwise both
        // pass the checks below against what neither has committed yet.
        cycleRepository.lockStore(FINALISATION_LOCK + mode);
        if (cycleRepository.find(payday, mode).isPresent()) {
            throw new ConflictException(alreadyFinalised(period));
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        if (now.isBefore(period.cutoff())) {
            throw new ConflictException("The cut-off has not passed yet: payday " + DAY.format(payday)
                    + " can be finalised from 00:00 on that day");
        }
        cycleRepository.findFirstByStoreModeAndPaydayAfterOrderByPaydayAsc(mode, payday).ifPresent(later -> {
            throw new ConflictException("A later payday, " + DAY.format(later.getPayday()) + ", is already finalised"
                    + (period.simulated() ? " in the SIMULATED store" : "") + ": " + DAY.format(payday)
                    + " can no longer be");
        });
        Built preview = build(period, null);
        StaffLoanCollectionReport report = preview.report();
        int nonFinal = (int) report.lines().stream().filter(line -> !line.finalAmount()).count();
        int stale = report.merchantSettlement().staleVouchers();
        if ((nonFinal > 0 || stale > 0) && !acceptNonFinal) {
            throw new ConflictException(notFinal(nonFinal, stale, payday));
        }

        String by = authService.getLoggedInUsername();
        StaffLoanCollectionCycle cycle = StaffLoanCollectionCycle.builder().payday(payday).storeMode(mode)
                .cutoff(period.cutoff()).finalisedAt(now).finalisedBy(by).nonFinalLines(nonFinal)
                .staleVouchers(stale).reason(reason).fresh(true).build();
        try {
            cycleRepository.saveAndFlush(cycle);
        } catch (DataIntegrityViolationException twice) {
            throw new ConflictException(alreadyFinalised(period));
        }
        lineRepository.saveAll(report.lines().stream().map(line -> StaffLoanCollectionLine.builder()
                .payday(payday).storeMode(mode).staffLoanId(line.staffLoanId()).treatment(line.treatment())
                .currency(line.currency()).amountToCollect(line.amountToCollect()).finalAmount(line.finalAmount())
                .flags(StaffLoanCollectionLine.join(line.flags())).build()).toList());
        lineRepository.flush();
        if (stamp(preview.spendIds(), ids -> redemptionRepository.stampSettlementCycle(ids, payday))
                != preview.spendIds().size()
                || stamp(preview.refundIds(), ids -> refundRepository.stampSettlementCycle(ids, payday))
                != preview.refundIds().size()) {
            throw new ConflictException(settledConcurrently(payday));
        }
        StaffLoanCollectionReport finalised = build(period, cycle).report();
        auditService.record(AuditLog.builder()
                .eventType(FINALISED_EVENT)
                .entityType(ENTITY).entityId(payday + ":" + mode)
                .actorId(by).channelUsed("web")
                .detail("mode:" + mode + ";cutoff:" + period.cutoff() + ";lines:" + finalised.lines().size()
                        + ";nonFinal:" + nonFinal + ";stale:" + stale + ";collect:"
                        + money(finalised.totals().stream().collect(Collectors.toMap(Totals::currency,
                        Totals::amountToCollect, BigDecimal::add, TreeMap::new)))
                        + ";settle:" + money(settled(finalised)) + ";spends:" + preview.spendIds().size()
                        + ";refunds:" + preview.refundIds().size() + (reason == null ? "" : ";reason:" + reason)));
        log.info("Staff Grocery Loan collections for {} finalised in the {} store by {}: {} line(s), {} not final,"
                        + " {} stale voucher(s), {} sale(s) and {} refund(s) settled", payday, mode, by,
                finalised.lines().size(), nonFinal, stale, preview.spendIds().size(), preview.refundIds().size());
        return finalised;
    }

    /**
     * Reads GetMore for one page of the vouchers whose sales or refunds can fall into the payday's cycle (keyset on
     * voucher id: issued before the cut-off, expiring no earlier than the previous payday's cut-off less the sync's
     * after-expiry window), whatever loan they belong to; the first page also cleans up the cancelled loans' voucher
     * issuances in this store first. Called with no transaction open; a voucher that cannot be read is counted and the
     * rest go on, but the page stops early once GetMore could not be read for {@value #SYNC_STOP_AFTER_UNAVAILABLE}
     * vouchers in a row, or when it has run {@link #SYNC_PAGE_BUDGET}: each voucher's calls (and each cleanup's) are
     * bounded only by their timeouts, and a slow GetMore must not hold a request for many minutes. The cleanups share
     * that time: none is started once it has run out, and those not reached wait for the next first page (or a
     * borrower's read, Credit's retry). Whatever the cleanups took, the page syncs at least one voucher, so the walk
     * always moves on. A page that stopped early says so and gives the voucher it got to as {@code nextAfter}.
     *
     * @param after the last voucher id of the previous page; null or 0 for the first
     * @param limit 1 to 200; null for 50
     * @throws ValidationException         not a repayment day, or a bad page
     * @throws GetMoreUnavailableException {@code OFF}: GetMore is OFF on this host
     */
    public StaffLoanCollectionReport.Sync syncForPayday(LocalDate payday, Long after, Integer limit) {
        GetMoreCalls.requireNoTransaction();
        Period period = period(payday);
        long from = after == null ? 0 : after;
        int size = limit == null ? DEFAULT_SYNC_PAGE : limit;
        if (from < 0) {
            throw new ValidationException("after must be 0 or more");
        }
        if (size < 1 || size > MAX_SYNC_PAGE) {
            throw new ValidationException("limit must be between 1 and " + MAX_SYNC_PAGE);
        }
        if (getMore.getMode() == GetMoreMode.OFF) {
            throw new GetMoreUnavailableException(null, GetMoreUnavailableException.Reason.OFF, "GetMore is OFF on"
                    + " this host (GETMORE_MODE): nothing can be read for payday " + DAY.format(payday));
        }
        GetMoreMode mode = period.storeMode();
        Instant deadline = marketTimeZone.clock().instant().plus(SYNC_PAGE_BUDGET);
        int cleanedUp = 0;
        if (from == 0) {
            List<StaffLoanVoucherIssuance> cancelling = issuanceRepository.findByStatusAndStoreModeOrderByIdAsc(
                    StaffLoanVoucherIssuanceStatus.CANCELLING, mode);
            for (int tried = 0; tried < cancelling.size(); tried++) {
                // Not stopped after failures in a row: a cleanup can fail for good on one issuance (codes GetMore
                // will not withdraw), and stopping there would leave every later one to its other triggers.
                if (!marketTimeZone.clock().instant().isBefore(deadline)) {
                    log.warn("Payday {} sync of the {} store: out of time after {} of {} cancelled voucher"
                                    + " issuance cleanup(s); the rest wait for the next first page", payday, mode,
                            tried, cancelling.size());
                    break;
                }
                if (issuer.cleanup(cancelling.get(tried).getId()).withdrawn()) {
                    cleanedUp++;
                }
            }
        }
        List<Long> ids = voucherRepository.findGetMoreIdsForCycle(mode, period.cutoff(), period.expiresFrom(), from,
                size);
        int synced = 0;
        int unchanged = 0;
        int blocked = 0;
        int unavailable = 0;
        int unavailableInARow = 0;
        int handled = 0;
        Long last = null;
        boolean stoppedEarly = false;
        for (Long voucherId : ids) {
            if (handled > 0 && !marketTimeZone.clock().instant().isBefore(deadline)) {
                stoppedEarly = true;
                break;
            }
            GetMoreSyncResult.Outcome outcome;
            try {
                outcome = sync.sync(voucherId, GetMoreSyncTrigger.PAYDAY).outcome();
            } catch (RuntimeException failed) {
                log.warn("Payday {} sync: voucher {} could not be synced ({})", payday, voucherId,
                        failed.getClass().getSimpleName());
                outcome = GetMoreSyncResult.Outcome.UNAVAILABLE;
            }
            switch (outcome) {
                case SYNCED -> synced++;
                case UNCHANGED, STALE_SNAPSHOT -> unchanged++;
                case BLOCKED -> blocked++;
                default -> unavailable++;
            }
            handled++;
            last = voucherId;
            unavailableInARow = outcome == GetMoreSyncResult.Outcome.UNAVAILABLE ? unavailableInARow + 1 : 0;
            if (unavailableInARow >= SYNC_STOP_AFTER_UNAVAILABLE && handled < ids.size()) {
                stoppedEarly = true;
                break;
            }
        }
        Long nextAfter = stoppedEarly || ids.size() == size ? last : null;
        if (stoppedEarly) {
            log.warn("Payday {} sync of the {} store after voucher {} stopped early at voucher {} ({} of {} on the"
                            + " page, {} in a row GetMore could not be read for)", payday, mode, from, last, handled,
                    ids.size(), unavailableInARow);
        }
        log.info("Payday {} sync of the {} store after voucher {}: {} synced, {} unchanged, {} blocked, {}"
                        + " unavailable, {} cancelled issuance(s) cleaned up; next after {}", payday, mode, from, synced,
                unchanged, blocked, unavailable, cleanedUp, nextAfter);
        return new StaffLoanCollectionReport.Sync(synced, unchanged, blocked, unavailable, cleanedUp, nextAfter,
                stoppedEarly, period.simulated());
    }

    // ---- the statement ---------------------------------------------------------------------------------------------

    private Built build(Period period, StaffLoanCollectionCycle cycle) {
        LocalDate payday = period.payday();
        GetMoreMode mode = period.storeMode();
        boolean finalised = cycle != null;
        StaffLoanCollectionCycle previous = cycleRepository.find(period.previousPayday(), mode).orElse(null);

        List<StaffLoan> due = loanRepository.findDueOn(payday, DUE);
        Map<Long, StaffLoan> loans = new LinkedHashMap<>();
        due.forEach(loan -> loans.put(loan.getId(), loan));
        Map<Long, StaffLoanCollectionLine> kept = new LinkedHashMap<>();
        if (finalised) {
            lineRepository.findByPaydayAndStoreModeOrderByIdAsc(payday, mode)
                    .forEach(line -> kept.put(line.getStaffLoanId(), line));
            List<Long> missing = kept.keySet().stream().filter(id -> !loans.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                loanRepository.findAllById(missing).forEach(loan -> loans.put(loan.getId(), loan));
            }
        }
        Map<Long, Voucher> vouchers = loans.isEmpty() ? Map.of() : voucherRepository.findByStaffLoanIdIn(
                loans.keySet()).stream().collect(Collectors.toMap(Voucher::getStaffLoanId, Function.identity()));
        Map<Long, StaffLoanVoucherIssuance> issuances = loans.isEmpty() ? Map.of()
                : issuanceRepository.findByStaffLoanIdIn(loans.keySet()).stream().collect(Collectors.toMap(
                StaffLoanVoucherIssuance::getStaffLoanId, Function.identity()));

        List<StaffLoan> lineLoans = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        if (finalised) {
            kept.keySet().stream().map(loans::get).filter(Objects::nonNull).forEach(lineLoans::add);
        }
        for (StaffLoan loan : due) {
            if (finalised && kept.containsKey(loan.getId())) {
                continue;
            }
            ExclusionReason reason = exclusion(loan, vouchers.get(loan.getId()), issuances.get(loan.getId()),
                    period.storeMode());
            if (reason == null && finalised) {
                reason = ExclusionReason.NOT_IN_FINALISED_CYCLE;
            }
            if (reason == null) {
                lineLoans.add(loan);
            } else {
                excluded.add(new Excluded(loan.getId(), loan.getReference(), loan.getEmployeeNumber(),
                        loan.getStatus(), reason, loan.getCurrency(), loan.outstanding()));
            }
        }
        long otherStore = excluded.stream().filter(exclusion -> exclusion.reason() == ExclusionReason.SIMULATED
                || exclusion.reason() == ExclusionReason.LIVE).count();
        if (otherStore > 0) {
            log.info("Staff Grocery Loan collections for {}: {} GetMore loan(s) of another store left out (this"
                    + " statement is the {} store's; GetMore is {} here)", payday, otherStore, mode, getMore.getMode());
        }

        // The settlement's rows are read BEFORE the lines' rows, and until finalised a line counts only rows that read
        // saw, or that an earlier payday paid for. A sale or refund a sync records in between is then in neither, and
        // is late next payday: what a finalisation keeps for a loan and the rows it stamps can never disagree.
        List<VoucherRedemption> spends = finalised
                ? redemptionRepository.findBySettlementCycleAndStoreModeOrderByIdAsc(payday, mode)
                : redemptionRepository.findUnsettledBefore(mode, period.cutoff());
        List<VoucherRefund> refunds = finalised
                ? refundRepository.findBySettlementCycleAndStoreModeOrderByIdAsc(payday, mode)
                : refundRepository.findUnsettledBefore(mode, period.cutoff());
        Set<Long> settlingSpends = spends.stream().map(VoucherRedemption::getId).collect(Collectors.toSet());
        Set<Long> settlingRefunds = refunds.stream().map(VoucherRefund::getId).collect(Collectors.toSet());

        List<Long> lineVoucherIds = lineLoans.stream().map(loan -> vouchers.get(loan.getId()))
                .filter(Objects::nonNull).map(Voucher::getId).toList();
        Map<Long, List<VoucherRedemption>> lineSpends = lineVoucherIds.isEmpty() ? Map.of()
                : redemptionRepository.findByVoucherIdInOrderByIdAsc(lineVoucherIds).stream()
                .filter(spend -> finalised || spend.getSettlementCycle() != null
                        || settlingSpends.contains(spend.getId()))
                .collect(Collectors.groupingBy(VoucherRedemption::getVoucherId));
        Map<Long, List<VoucherRefund>> lineRefunds = lineVoucherIds.isEmpty() ? Map.of()
                : refundRepository.findByVoucherIdInOrderByIdAsc(lineVoucherIds).stream()
                .filter(refund -> finalised || refund.getSettlementCycle() != null
                        || settlingRefunds.contains(refund.getId()))
                .collect(Collectors.groupingBy(VoucherRefund::getVoucherId));

        List<Line> lines = lineLoans.stream().map(loan -> line(loan, vouchers.get(loan.getId()),
                issuances.get(loan.getId()), kept.get(loan.getId()), lineSpends, lineRefunds, period, finalised))
                .toList();
        List<LateAdjustment> late = finalised ? lateAdjustments(lineLoans, vouchers, lineSpends, lineRefunds,
                period) : List.of();

        int staleVouchers = finalised ? cycle.getStaleVouchers()
                : (int) voucherRepository.countGetMoreStaleForCycle(mode, period.cutoff(), period.expiresFrom(),
                period.cutoff());
        MerchantSettlement settlement = settlement(period, previous, spends, refunds, vouchers, staleVouchers);

        long openIssues = lineVoucherIds.isEmpty() ? 0
                : issueRepository.countByVoucherIdInAndClearedAtIsNull(lineVoucherIds);
        int staleLines = (int) lines.stream().filter(line -> line.flags().contains(StaffLoanOwed.Flag.STALE)).count();
        StaffLoanCollectionReport report = new StaffLoanCollectionReport(payday, period.cutoff(), mode,
                period.simulated(), finalised, finalised ? cycle.getFinalisedAt() : null,
                finalised ? cycle.getFinalisedBy() : null, finalised ? cycle.getReason() : null, lines,
                totals(lines), settlement, excluded, late, staleLines, openIssues);
        return new Built(report, spends.stream().map(VoucherRedemption::getId).toList(),
                refunds.stream().map(VoucherRefund::getId).toList());
    }

    /**
     * Why a loan due on the payday has no line, or null when it has one: its voucher is (or is being issued) in the
     * other GetMore store than the statement's, whatever the loan's status, so a simulated loan is never presented as
     * real debt and a real one never lands on a simulated statement; or it was written off (recovered outside
     * payroll).
     */
    private static ExclusionReason exclusion(StaffLoan loan, Voucher voucher, StaffLoanVoucherIssuance issuance,
                                             GetMoreMode storeMode) {
        GetMoreMode store = store(loan, voucher, issuance);
        if (store != null && store != storeMode) {
            return store == GetMoreMode.SIMULATED ? ExclusionReason.SIMULATED : ExclusionReason.LIVE;
        }
        if (loan.getStatus() == StaffLoanStatus.WRITTEN_OFF) {
            return ExclusionReason.WRITTEN_OFF;
        }
        return null;
    }

    /**
     * The GetMore store a loan was paid out in, or is being issued its voucher in: SIMULATED when paid out as a
     * simulated coupon, else its voucher's store, else its issuance's; null when it has neither.
     */
    private static GetMoreMode store(StaffLoan loan, Voucher voucher, StaffLoanVoucherIssuance issuance) {
        if (loan.isSimulatedGetMore()) {
            return GetMoreMode.SIMULATED;
        }
        if (voucher != null && voucher.getStoreMode() != null) {
            return voucher.getStoreMode();
        }
        return issuance == null ? null : issuance.getStoreMode();
    }

    /** Paid out as (or being issued) a coupon of staging's simulated store. */
    private static boolean simulated(StaffLoan loan, Voucher voucher, StaffLoanVoucherIssuance issuance) {
        return store(loan, voucher, issuance) == GetMoreMode.SIMULATED;
    }

    /**
     * One loan's line. Before finalisation, what it owes now ({@link StaffLoanOwed#at}); after, what was sent to
     * payroll, beside its voucher as it was known then (the rows a payday up to this one had paid for).
     */
    private Line line(StaffLoan loan, Voucher voucher, StaffLoanVoucherIssuance issuance,
                      StaffLoanCollectionLine kept, Map<Long, List<VoucherRedemption>> spends,
                      Map<Long, List<VoucherRefund>> refunds, Period period, boolean finalised) {
        StaffLoanOwed.VoucherAtCutoff atCutoff = voucher == null ? null : StaffLoanOwed.VoucherAtCutoff.of(voucher,
                spends.getOrDefault(voucher.getId(), List.of()), refunds.getOrDefault(voucher.getId(), List.of()),
                period.cutoff(), finalised ? period.payday() : null);
        StaffLoanOwed owed;
        UnredeemedVoucherTreatment treatment;
        String currency;
        if (kept == null) {
            owed = owed(loan, atCutoff, () -> StaffLoanOwed.at(loan, atCutoff));
            treatment = loan.getUnredeemedVoucherTreatment();
            currency = loan.getCurrency();
        } else {
            StaffLoanOwed known = owed(loan, atCutoff, () -> StaffLoanOwed.of(loan.getDisbursedAt() != null,
                    kept.getTreatment(), loan.getTotalRepayable(), loan.getAmount(), atCutoff));
            owed = new StaffLoanOwed(kept.getAmountToCollect(), kept.isFinalAmount(), known.openBalance(),
                    kept.isFinalAmount() ? null : known.couldRiseTo(), kept.flagSet());
            treatment = kept.getTreatment();
            currency = kept.getCurrency();
        }
        return new Line(loan.getId(), loan.getReference(), loan.getEmployeeNumber(), loan.getFullName(),
                loan.getStatus(), treatment, voucher == null ? null : voucher.getId(),
                atCutoff == null ? null : atCutoff.state(), atCutoff == null ? null : atCutoff.stoppedReason(),
                currency, atCutoff == null ? loan.getAmount() : atCutoff.faceValue(),
                atCutoff == null ? ZERO : atCutoff.spentNet(), atCutoff == null ? ZERO : atCutoff.refunded(),
                atCutoff == null ? ZERO : atCutoff.held(), owed.openBalance(), owed.amountToCollect(),
                owed.couldRiseTo(), owed.finalAmount(), List.copyOf(owed.flags()),
                voucher == null ? null : voucher.getSyncedAt(), simulated(loan, voucher, issuance));
    }

    /**
     * What a loan owes; or, when its voucher's rows at the cut-off come to an amount outside 0 to its whole repayment,
     * a provisional line flagged ROWS_INCONSISTENT ({@link StaffLoanOwed#inconsistent}): one voucher never takes the
     * whole statement down, and the line cannot be finalised without {@code acceptNonFinal} and a reason.
     */
    private static StaffLoanOwed owed(StaffLoan loan, StaffLoanOwed.VoucherAtCutoff atCutoff,
                                      Supplier<StaffLoanOwed> owed) {
        try {
            return owed.get();
        } catch (StaffLoanOwed.OutOfRange outOfRange) {
            if (atCutoff == null) {
                throw outOfRange;
            }
            log.warn("Staff Grocery Loan {}: voucher {} comes to {} spent less refunded at the cut-off ({}); its payday"
                            + " line is provisional, {}", loan.getReference(), atCutoff.voucherId(),
                    atCutoff.spentNet().toPlainString(), outOfRange.getMessage(), StaffLoanOwed.Flag.ROWS_INCONSISTENT);
            return StaffLoanOwed.inconsistent(loan.getTotalRepayable(), atCutoff);
        }
    }

    private static List<Totals> totals(List<Line> lines) {
        Map<String, List<Line>> byCurrency = lines.stream()
                .collect(Collectors.groupingBy(Line::currency, TreeMap::new, Collectors.toList()));
        List<Totals> totals = new ArrayList<>();
        byCurrency.forEach((currency, group) -> {
            List<Line> provisional = group.stream().filter(line -> !line.finalAmount()).toList();
            totals.add(new Totals(currency, group.size(), sum(group), provisional.size(), sum(provisional)));
        });
        return totals;
    }

    private static BigDecimal sum(List<Line> lines) {
        return lines.stream().map(Line::amountToCollect).reduce(ZERO, BigDecimal::add);
    }

    /**
     * What the cycle pays GetMore, per voucher and per currency. A row made before the previous payday's cut-off,
     * when that payday was finalised, was recorded after it: {@code late}.
     */
    private MerchantSettlement settlement(Period period, StaffLoanCollectionCycle previous,
                                          List<VoucherRedemption> spends, List<VoucherRefund> refunds,
                                          Map<Long, Voucher> lineVouchers, int staleVouchers) {
        Set<Long> ids = new TreeSet<>();
        spends.forEach(spend -> ids.add(spend.getVoucherId()));
        refunds.forEach(refund -> ids.add(refund.getVoucherId()));
        Map<Long, Voucher> vouchers = new HashMap<>();
        lineVouchers.values().stream().filter(voucher -> ids.contains(voucher.getId()))
                .forEach(voucher -> vouchers.put(voucher.getId(), voucher));
        List<Long> missing = ids.stream().filter(id -> !vouchers.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            voucherRepository.findAllById(missing).forEach(voucher -> vouchers.put(voucher.getId(), voucher));
        }
        LocalDateTime previousCutoff = previous == null ? null : period.previousCutoff();
        Map<Long, Map<Boolean, BigDecimal[]>> byVoucher = new TreeMap<>();
        spends.forEach(spend -> add(byVoucher, spend.getVoucherId(), late(spend.getRedeemedAt(), previousCutoff), 0,
                spend.getAmount()));
        refunds.forEach(refund -> add(byVoucher, refund.getVoucherId(), late(refund.getRefundedAt(),
                previousCutoff), 1, refund.getAmount()));
        List<SettlementLine> lines = new ArrayList<>();
        byVoucher.forEach((voucherId, kinds) -> kinds.forEach((isLate, amounts) -> {
            Voucher voucher = vouchers.get(voucherId);
            lines.add(new SettlementLine(voucherId, voucher == null ? null : voucher.getLoanAccount(),
                    voucher == null ? null : voucher.getCurrency(), amounts[0], amounts[1],
                    amounts[0].subtract(amounts[1]), isLate));
        }));
        Map<String, List<SettlementLine>> byCurrency = lines.stream().collect(Collectors.groupingBy(
                line -> Objects.toString(line.currency(), ""), TreeMap::new, Collectors.toList()));
        List<SettlementTotals> totals = new ArrayList<>();
        byCurrency.forEach((currency, group) -> {
            BigDecimal sold = group.stream().map(SettlementLine::spends).reduce(ZERO, BigDecimal::add);
            BigDecimal given = group.stream().map(SettlementLine::refunds).reduce(ZERO, BigDecimal::add);
            totals.add(new SettlementTotals(currency, sold, given, sold.subtract(given)));
        });
        Merchant merchant = merchant(vouchers, lineVouchers.values());
        return new MerchantSettlement(merchant == null ? null : merchant.getMerchantCode(),
                merchant == null ? null : merchant.getCompanyName(), period.cutoff(),
                previous == null ? null : new PreviousCycle(previous.getPayday(), previous.getFinalisedAt()),
                staleVouchers, totals, lines);
    }

    private static boolean late(LocalDateTime occurredAt, LocalDateTime previousCutoff) {
        return previousCutoff != null && occurredAt.isBefore(previousCutoff);
    }

    private static void add(Map<Long, Map<Boolean, BigDecimal[]>> byVoucher, Long voucherId, boolean late, int slot,
                            BigDecimal amount) {
        BigDecimal[] amounts = byVoucher.computeIfAbsent(voucherId, id -> new TreeMap<>())
                .computeIfAbsent(late, kind -> new BigDecimal[]{ZERO, ZERO});
        amounts[slot] = amounts[slot].add(amount);
    }

    /**
     * Whom the cycle pays: the merchant of its vouchers (the lowest id first), else of the payday's vouchers, else the
     * Staff Grocery Loan merchant when it is a GetMore store.
     */
    private Merchant merchant(Map<Long, Voucher> settled, Collection<Voucher> lineVouchers) {
        return settled.values().stream().min(Comparator.comparing(Voucher::getId))
                .or(() -> lineVouchers.stream().min(Comparator.comparing(Voucher::getId)))
                .map(Voucher::getMerchant)
                .or(() -> merchantRepository.findByStaffLoanMerchantTrue()
                        .filter(merchant -> merchant.getVoucherStore() == VoucherStore.GETMORE))
                .orElse(null);
    }

    /**
     * Once finalised: per loan, its voucher's sales and refunds made before the cut-off that no payday up to this one
     * had paid for when it was finalised, i.e. recorded after. They are paid to GetMore in the next payday; what they
     * mean for the borrower under the reduced treatment is the owner's decision (Q3).
     */
    private static List<LateAdjustment> lateAdjustments(List<StaffLoan> lineLoans, Map<Long, Voucher> vouchers,
                                                        Map<Long, List<VoucherRedemption>> spends,
                                                        Map<Long, List<VoucherRefund>> refunds, Period period) {
        List<LateAdjustment> late = new ArrayList<>();
        for (StaffLoan loan : lineLoans) {
            Voucher voucher = vouchers.get(loan.getId());
            if (voucher == null) {
                continue;
            }
            BigDecimal sold = spends.getOrDefault(voucher.getId(), List.of()).stream()
                    .filter(spend -> spend.getRedeemedAt().isBefore(period.cutoff())
                            && notSettledBy(spend.getSettlementCycle(), period.payday()))
                    .map(VoucherRedemption::getAmount).reduce(ZERO, BigDecimal::add);
            BigDecimal given = refunds.getOrDefault(voucher.getId(), List.of()).stream()
                    .filter(refund -> refund.getRefundedAt().isBefore(period.cutoff())
                            && notSettledBy(refund.getSettlementCycle(), period.payday()))
                    .map(VoucherRefund::getAmount).reduce(ZERO, BigDecimal::add);
            if (sold.signum() != 0 || given.signum() != 0) {
                late.add(new LateAdjustment(loan.getId(), loan.getReference(), voucher.getId(), voucher.getCurrency(),
                        sold, given, sold.subtract(given)));
            }
        }
        return late;
    }

    private static boolean notSettledBy(LocalDate settlementCycle, LocalDate payday) {
        return settlementCycle == null || settlementCycle.isAfter(payday);
    }

    // ---- finalising ------------------------------------------------------------------------------------------------

    /** Stamps rows in chunks; how many were stamped. */
    private static int stamp(List<Long> ids, ToIntFunction<List<Long>> update) {
        int stamped = 0;
        for (int from = 0; from < ids.size(); from += STAMP_CHUNK) {
            stamped += update.applyAsInt(ids.subList(from, Math.min(ids.size(), from + STAMP_CHUNK)));
        }
        return stamped;
    }

    /** What a finalisation that found rows it was settling already settled answers (409): it keeps nothing. */
    public static String settledConcurrently(LocalDate payday) {
        return "GetMore sales or refunds of payday " + DAY.format(payday) + " were settled by another finalisation"
                + " while this one ran; nothing was finalised, read it again";
    }

    private static String alreadyFinalised(Period period) {
        return "Payday " + DAY.format(period.payday()) + " is already finalised"
                + (period.simulated() ? " in the SIMULATED store" : "");
    }

    static String notFinal(int nonFinal, int stale, LocalDate payday) {
        return nonFinal + (nonFinal == 1 ? " line is" : " lines are") + " not final and " + stale
                + (stale == 1 ? " voucher is" : " vouchers are") + " stale: sync first (POST /lending/v1/"
                + "staff-loan-collections/" + payday + "/getmore-syncs) or finalise with acceptNonFinal and a reason";
    }

    /** What the payday pays GetMore, per currency. */
    static Map<String, BigDecimal> settled(StaffLoanCollectionReport report) {
        return report.merchantSettlement().totals().stream().collect(Collectors.toMap(SettlementTotals::currency,
                SettlementTotals::net, BigDecimal::add, TreeMap::new));
    }

    /** {@code USD 150.00 and ZWG 10.00}; {@code nothing} when there is none. */
    static String money(Map<String, BigDecimal> byCurrency) {
        return byCurrency.isEmpty() ? "nothing" : byCurrency.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue().toPlainString())
                .collect(Collectors.joining(" and "));
    }

    /**
     * What a finalisation answers: "Collections for 20 Nov 2026 finalised: 1 loan, USD 150.00 to collect; USD 90.00
     * to pay GetMore Groceries", and on the simulated store that it is not for payroll.
     */
    public static String finalisedMessage(StaffLoanCollectionReport report) {
        int loans = report.lines().size();
        String merchant = report.merchantSettlement().merchantName() == null ? "GetMore"
                : report.merchantSettlement().merchantName();
        return "Collections for " + DAY.format(report.payday()) + " finalised: " + loans
                + (loans == 1 ? " loan, " : " loans, ") + money(report.totals().stream().collect(Collectors.toMap(
                Totals::currency, Totals::amountToCollect, BigDecimal::add, TreeMap::new))) + " to collect; "
                + money(settled(report)) + " to pay " + merchant
                + (report.simulated() ? " (" + NOT_FOR_PAYROLL + ")" : "");
    }
}

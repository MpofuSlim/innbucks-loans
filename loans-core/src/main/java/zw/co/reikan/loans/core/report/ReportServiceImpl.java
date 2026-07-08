package zw.co.reikan.loans.core.report;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.reikan.loans.core.api.AgentPerformanceReportResponse;
import zw.co.reikan.loans.core.api.AgentPerformanceReportResponse.AgentPerformance;
import zw.co.reikan.loans.core.api.CommissionsReportResponse;
import zw.co.reikan.loans.core.api.CommissionsReportResponse.MerchantCommission;
import zw.co.reikan.loans.core.api.DisbursementsReportResponse;
import zw.co.reikan.loans.core.api.DisbursementsReportResponse.DailyDisbursement;
import zw.co.reikan.loans.core.api.LoanPortfolioReportResponse;
import zw.co.reikan.loans.core.api.LoanPortfolioReportResponse.StatusBreakdown;
import zw.co.reikan.loans.core.api.MerchantPerformanceReportResponse;
import zw.co.reikan.loans.core.api.MerchantPerformanceReportResponse.MerchantPerformance;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanRepository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final LoanRepository loanRepository;

    @Override
    @Transactional(readOnly = true)
    public DisbursementsReportResponse disbursementsReport(LocalDate fromDate, LocalDate toDate) {
        Range range = resolveRange(fromDate, toDate);
        List<DailyDisbursement> days = new ArrayList<>();
        long totalCount = 0;
        BigDecimal totalDisbursed = BigDecimal.ZERO;
        for (Object[] row : loanRepository.disbursementsByDay(range.start(), range.end())) {
            long count = asLong(row[1]);
            BigDecimal amount = asBigDecimal(row[2]);
            days.add(new DailyDisbursement(asLocalDate(row[0]), count, amount));
            totalCount += count;
            totalDisbursed = totalDisbursed.add(amount);
        }
        return new DisbursementsReportResponse(range.from(), range.to(), totalCount, totalDisbursed, days);
    }

    @Override
    @Transactional(readOnly = true)
    public LoanPortfolioReportResponse loanPortfolioReport(LocalDate fromDate, LocalDate toDate) {
        Range range = resolveRange(fromDate, toDate);
        List<StatusBreakdown> breakdown = new ArrayList<>();
        long totalLoans = 0;
        BigDecimal totalPrincipal = BigDecimal.ZERO;
        for (Object[] row : loanRepository.portfolioByApprovalStatus(range.start(), range.end())) {
            String status = row[0] == null ? "UNKNOWN" : ((LoanApprovalStatus) row[0]).name();
            long count = asLong(row[1]);
            BigDecimal principal = asBigDecimal(row[2]);
            breakdown.add(new StatusBreakdown(status, count, principal));
            totalLoans += count;
            totalPrincipal = totalPrincipal.add(principal);
        }
        return new LoanPortfolioReportResponse(range.from(), range.to(), totalLoans, totalPrincipal, breakdown);
    }

    @Override
    @Transactional(readOnly = true)
    public CommissionsReportResponse commissionsReport(LocalDate fromDate, LocalDate toDate) {
        Range range = resolveRange(fromDate, toDate);
        List<MerchantCommission> merchants = new ArrayList<>();
        BigDecimal totalAgent = BigDecimal.ZERO;
        BigDecimal totalProvider = BigDecimal.ZERO;
        for (Object[] row : loanRepository.commissionsByMerchant(range.start(), range.end())) {
            BigDecimal agent = asBigDecimal(row[3]);
            BigDecimal provider = asBigDecimal(row[4]);
            merchants.add(new MerchantCommission((String) row[0], (String) row[1], asLong(row[2]), agent, provider));
            totalAgent = totalAgent.add(agent);
            totalProvider = totalProvider.add(provider);
        }
        return new CommissionsReportResponse(range.from(), range.to(), totalAgent, totalProvider, merchants);
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantPerformanceReportResponse merchantPerformanceReport(LocalDate fromDate, LocalDate toDate) {
        Range range = resolveRange(fromDate, toDate);
        List<MerchantPerformance> merchants = new ArrayList<>();
        for (Object[] row : loanRepository.merchantPerformance(range.start(), range.end())) {
            merchants.add(new MerchantPerformance((String) row[0], (String) row[1], asLong(row[2]),
                    asBigDecimal(row[3]), asLong(row[4]), asBigDecimal(row[5])));
        }
        return new MerchantPerformanceReportResponse(range.from(), range.to(), merchants);
    }

    @Override
    @Transactional(readOnly = true)
    public AgentPerformanceReportResponse agentPerformanceReport(LocalDate fromDate, LocalDate toDate) {
        Range range = resolveRange(fromDate, toDate);
        List<AgentPerformance> agents = new ArrayList<>();
        for (Object[] row : loanRepository.agentPerformance(range.start(), range.end())) {
            agents.add(new AgentPerformance(asLong(row[0]), (String) row[1], asLong(row[2]),
                    asBigDecimal(row[3]), asBigDecimal(row[4])));
        }
        return new AgentPerformanceReportResponse(range.from(), range.to(), agents);
    }

    /** Defaults: month-to-date. Bounds are inclusive whole days. */
    private Range resolveRange(LocalDate fromDate, LocalDate toDate) {
        LocalDate from = fromDate == null ? LocalDate.now().withDayOfMonth(1) : fromDate;
        LocalDate to = toDate == null ? LocalDate.now() : toDate;
        if (from.isAfter(to)) {
            throw new ValidationException("fromDate must not be after toDate");
        }
        return new Range(from, to);
    }

    private record Range(LocalDate from, LocalDate to) {
        LocalDateTime start() {
            return from.atStartOfDay();
        }

        LocalDateTime end() {
            return to.atTime(LocalTime.MAX);
        }
    }

    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }

    private static LocalDate asLocalDate(Object value) {
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return (LocalDate) value;
    }
}

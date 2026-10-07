package zw.co.innbucks.loans.core.dashboard;

/** The dashboard's three entity counts, read in one statement ({@code LoanBatchRepository.dashboardEntityCounts}). */
public record DashboardEntityCounts(long merchants, long users, long batches) {
}

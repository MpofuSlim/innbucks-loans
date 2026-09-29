package zw.co.innbucks.loans.core.ndasenda;

import java.time.LocalDate;
import java.util.List;

/**
 * What one run of the deduction-response job did, for its summary line. A fetch that failed is listed
 * here instead of passing for "no responses": the loans cannot tell the two apart, so the run must.
 *
 * @param from             first day of responses read (inclusive)
 * @param to               last day of responses read (inclusive), today
 * @param awaitingLoans    loans waiting on Ndasenda when the run started
 * @param batchesRead      response batches whose records were read
 * @param recordsProcessed deduction records handed to processing
 * @param fetchFailures    one entry per listing or batch that could not be read, with its cause
 * @param newlyOverdue     loans reported overdue for the first time by this run
 */
public record ResponseSweepResult(LocalDate from,
                                  LocalDate to,
                                  int awaitingLoans,
                                  int batchesRead,
                                  int recordsProcessed,
                                  List<String> fetchFailures,
                                  int newlyOverdue) {

    public boolean incomplete() {
        return !fetchFailures.isEmpty();
    }
}

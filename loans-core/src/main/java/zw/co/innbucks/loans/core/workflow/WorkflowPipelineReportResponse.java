package zw.co.innbucks.loans.core.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The workflow pipeline and service-level adherence by stage, channel and originating agent (FR-SSB-014, BRD 7.4):
 * what waits at each stage now, and how the waits that ended in the period measured against the stage's target.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowPipelineReportResponse(LocalDate fromDate, LocalDate toDate, List<StagePipeline> stages) {

    /**
     * One stage.
     *
     * @param unassigned waiting items nobody has; absent where items are not assigned
     * @param completed  waits that ended in the period and could be timed
     * @param unmeasured waits that ended in the period with no record of when they began, not timed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StagePipeline(
            String stage,
            String name,
            int targetHours,
            Integer escalationHours,
            long waiting,
            long overdue,
            long escalated,
            Long unassigned,
            long completed,
            long withinTarget,
            BigDecimal adherencePercent,
            BigDecimal averageHours,
            BigDecimal medianHours,
            BigDecimal longestHours,
            long unmeasured,
            List<Breakdown> byChannel,
            List<Breakdown> byOriginator) {
    }

    /**
     * One channel's or originator's share of a stage.
     *
     * @param key the channel id (PORTAL for the portal) or the originator's username
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Breakdown(
            String key,
            String name,
            long waiting,
            long overdue,
            long completed,
            long withinTarget,
            BigDecimal adherencePercent,
            BigDecimal averageHours) {
    }
}

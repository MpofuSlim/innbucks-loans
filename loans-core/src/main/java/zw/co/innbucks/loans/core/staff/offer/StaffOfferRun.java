package zw.co.innbucks.loans.core.staff.offer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One attempt at the weekly offer run (FR-SGL-015), kept whatever its outcome. A completed run counts every member of
 * the register once: ineligible, excluded for one reason, or eligible; and every eligible member as offered (new),
 * refreshed (their open offer replaced) or already offered this cycle.
 */
@Entity
@Table(name = "staff_offer_runs")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "cycleStart", "trigger", "status"})
public class StaffOfferRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The Monday of the market week it belongs to. */
    @Column(name = "cycle_start", nullable = false)
    private LocalDate cycleStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_trigger", length = 16, nullable = false)
    private StaffOfferRunTrigger trigger;

    @Column(name = "started_by", nullable = false)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at", nullable = false)
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffOfferRunStatus status;

    /** Why it was REFUSED or FAILED. */
    @Column(name = "reason", length = 500)
    private String reason;

    /** The payroll reconciliation it relied on (or, when REFUSED, found too old). */
    @Column(name = "reconciliation_id")
    private Long reconciliationId;

    @Column(name = "register_members")
    private Integer registerMembers;

    @Column(name = "ineligible")
    private Integer ineligible;

    @Column(name = "excluded_active_loan")
    private Integer excludedActiveLoan;

    @Column(name = "excluded_arrears")
    private Integer excludedArrears;

    @Column(name = "excluded_by_reconciliation")
    private Integer excludedByReconciliation;

    @Column(name = "eligible")
    private Integer eligible;

    @Column(name = "offered")
    private Integer offered;

    @Column(name = "refreshed")
    private Integer refreshed;

    @Column(name = "already_offered")
    private Integer alreadyOffered;

    @Column(name = "withdrawn")
    private Integer withdrawn;

    @Column(name = "expired")
    private Integer expired;
}

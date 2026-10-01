package zw.co.innbucks.loans.core.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One field of one staff record changing (FR-SGL-006): its previous value (none when the record was created), its new
 * value, who submitted the change, who approved it, and when. Written once and never changed.
 */
@Entity
@Table(name = "staff_member_changes")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffMemberChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_member_id", nullable = false)
    private Long staffMemberId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** The field's API name, e.g. grade or employmentStatus. */
    @Column(name = "field", length = 40, nullable = false)
    private String field;

    @Column(name = "previous_value")
    private String previousValue;

    @Column(name = "new_value", nullable = false)
    private String newValue;

    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    @Column(name = "approved_by", nullable = false)
    private String approvedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}

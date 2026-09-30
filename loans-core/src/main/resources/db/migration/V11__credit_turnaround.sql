-- Credit turnaround (FR-SSB-015 / FR-PBL-030): how long an application waits for a credit decision, against a
-- service level an administrator sets without a release. Past the target it is overdue; past the escalation point
-- it is escalated, once per wait.

-- The service level of each stage the lender measures; only the credit decision, for now. Hours are wall-clock
-- hours from when the application reached Credit.
CREATE TABLE service_levels (
    stage            VARCHAR(32)  NOT NULL,
    target_hours     INTEGER      NOT NULL,
    escalation_hours INTEGER      NOT NULL,
    updated_by       VARCHAR(255) NOT NULL,
    updated_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT service_levels_pkey PRIMARY KEY (stage),
    CONSTRAINT ck_service_levels_stage CHECK (stage IN ('CREDIT_DECISION')),
    CONSTRAINT ck_service_levels_target CHECK (target_hours > 0),
    CONSTRAINT ck_service_levels_escalation CHECK (escalation_hours >= target_hours)
);

-- A default for the bank to change: decided within a day, escalated after two.
INSERT INTO service_levels (stage, target_hours, escalation_hours, updated_by, updated_at)
VALUES ('CREDIT_DECISION', 24, 48, 'system', now() AT TIME ZONE 'UTC');

-- An application reaches Credit when SSB accepts its deduction (date_approved), and again each time its originator
-- answers a return: credit_resubmitted_at is the last of those answers. credit_escalated_at is when the current
-- wait was escalated; a resubmission starts a new wait and clears it.
ALTER TABLE loans
    ADD COLUMN credit_resubmitted_at TIMESTAMP(6),
    ADD COLUMN credit_escalated_at   TIMESTAMP(6);

-- Loans resubmitted before this change: their last answer, from the decision log.
UPDATE loans l
SET credit_resubmitted_at = d.last_resubmitted_at
FROM (SELECT loan_id, max(performed_at) AS last_resubmitted_at
      FROM credit_decisions
      WHERE action = 'RESUBMITTED'
      GROUP BY loan_id) d
WHERE d.loan_id = l.id;

-- The credit queue, and the escalation sweep over it.
CREATE INDEX idx_loans_awaiting_credit ON loans (id)
    WHERE loan_status = 'APPROVED' AND internal_approval_status = 'PENDING';

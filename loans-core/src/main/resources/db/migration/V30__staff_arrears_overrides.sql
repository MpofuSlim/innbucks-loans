-- Credit's override of the arrears rule for one staff member (FR-SGL-014): no Staff Grocery Loan where the employee
-- owes a written-off or delinquent balance on any InnBucks facility, "unless overridden by Credit with reason
-- recorded". It lifts the written-off part only. An overdue loan is still an open one, and FR-SGL-013 allows no loan
-- beside it, override or not.
--
-- A CREDIT_MANAGER or SUPER_ADMIN proposes one with a reason and a last day; another approves or rejects it, never the
-- proposer. It is good for one loan: the loan accepted under it names it, and it is USED from then on.
CREATE TABLE staff_arrears_overrides (
    id                BIGSERIAL    NOT NULL,
    staff_member_id   BIGINT       NOT NULL,
    reason            VARCHAR(255) NOT NULL,
    -- The last market day it may be used on.
    valid_until       DATE         NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    proposed_by       VARCHAR(255) NOT NULL,
    proposed_at       TIMESTAMP(6) NOT NULL,
    decided_by        VARCHAR(255),
    decided_at        TIMESTAMP(6),
    decision_comment  VARCHAR(255),
    -- An approved override replaced by a later approval for the same member.
    superseded_by     BIGINT,
    superseded_at     TIMESTAMP(6),
    revoked_by        VARCHAR(255),
    revoked_at        TIMESTAMP(6),
    revocation_reason VARCHAR(255),
    -- The loan it let the member take.
    staff_loan_id     BIGINT,
    used_at           TIMESTAMP(6),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT staff_arrears_overrides_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_arrears_overrides_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT fk_staff_arrears_overrides_superseded_by
        FOREIGN KEY (superseded_by) REFERENCES staff_arrears_overrides (id),
    CONSTRAINT fk_staff_arrears_overrides_loan FOREIGN KEY (staff_loan_id) REFERENCES staff_loans (id),
    CONSTRAINT uq_staff_arrears_overrides_loan UNIQUE (staff_loan_id),
    CONSTRAINT ck_staff_arrears_overrides_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'SUPERSEDED', 'REVOKED', 'USED')),
    CONSTRAINT ck_staff_arrears_overrides_decided
        CHECK ((status = 'PENDING') = (decided_by IS NULL) AND (decided_by IS NULL) = (decided_at IS NULL)),
    -- Maker-checker: whoever proposed an override never approves or rejects it. Only they may withdraw it.
    CONSTRAINT ck_staff_arrears_overrides_checker
        CHECK (status NOT IN ('APPROVED', 'REJECTED', 'SUPERSEDED', 'REVOKED', 'USED')
            OR lower(decided_by) <> lower(proposed_by)),
    CONSTRAINT ck_staff_arrears_overrides_withdrawn CHECK (status <> 'WITHDRAWN' OR decided_by = proposed_by),
    CONSTRAINT ck_staff_arrears_overrides_superseded
        CHECK ((status = 'SUPERSEDED') = (superseded_by IS NOT NULL)
            AND (superseded_by IS NULL) = (superseded_at IS NULL)),
    CONSTRAINT ck_staff_arrears_overrides_revoked
        CHECK ((status = 'REVOKED') = (revoked_by IS NOT NULL)
            AND (revoked_by IS NULL) = (revoked_at IS NULL)
            AND (revoked_by IS NULL) = (revocation_reason IS NULL)),
    CONSTRAINT ck_staff_arrears_overrides_used
        CHECK ((status = 'USED') = (staff_loan_id IS NOT NULL)
            AND (staff_loan_id IS NULL) = (used_at IS NULL))
);
-- One override in force per member, and one proposal waiting for them.
CREATE UNIQUE INDEX uq_staff_arrears_overrides_approved ON staff_arrears_overrides (staff_member_id)
    WHERE status = 'APPROVED';
CREATE UNIQUE INDEX uq_staff_arrears_overrides_pending ON staff_arrears_overrides (staff_member_id)
    WHERE status = 'PENDING';

-- The override a loan was accepted under, when the member owed a written-off balance.
ALTER TABLE staff_loans ADD COLUMN arrears_override_id BIGINT;
ALTER TABLE staff_loans ADD CONSTRAINT fk_staff_loans_arrears_override
    FOREIGN KEY (arrears_override_id) REFERENCES staff_arrears_overrides (id);
ALTER TABLE staff_loans ADD CONSTRAINT uq_staff_loans_arrears_override UNIQUE (arrears_override_id);

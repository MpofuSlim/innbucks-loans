-- Credit's limit overrides for one staff member (FR-SGL-011). An offer is made at the member's grade limit unless an
-- override authorised by Credit says otherwise: proposed by one CREDIT_MANAGER or SUPER_ADMIN and approved or rejected
-- by another (maker-checker), every step audited. An override is set for the grade the member held when it was
-- proposed and applies only while they still hold it, so a regrading brings back the matrix limit. An amount of 0
-- stops offers to the member.

CREATE TABLE staff_limit_overrides (
    id                BIGSERIAL      NOT NULL,
    staff_member_id   BIGINT         NOT NULL,
    grade             VARCHAR(16)    NOT NULL,
    amount            NUMERIC(19, 2) NOT NULL,
    reason            VARCHAR(255)   NOT NULL,
    status            VARCHAR(16)    NOT NULL,
    proposed_by       VARCHAR(255)   NOT NULL,
    proposed_at       TIMESTAMP(6)   NOT NULL,
    decided_by        VARCHAR(255),
    decided_at        TIMESTAMP(6),
    decision_comment  VARCHAR(255),
    -- An approved override replaced by a later approval for the same member.
    superseded_by     BIGINT,
    superseded_at     TIMESTAMP(6),
    revoked_by        VARCHAR(255),
    revoked_at        TIMESTAMP(6),
    revocation_reason VARCHAR(255),
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT staff_limit_overrides_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_limit_overrides_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT fk_staff_limit_overrides_superseded_by
        FOREIGN KEY (superseded_by) REFERENCES staff_limit_overrides (id),
    CONSTRAINT ck_staff_limit_overrides_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT ck_staff_limit_overrides_amount CHECK (amount >= 0),
    CONSTRAINT ck_staff_limit_overrides_decided
        CHECK ((status = 'PENDING') = (decided_by IS NULL) AND (decided_by IS NULL) = (decided_at IS NULL)),
    -- Maker-checker: whoever proposed an override never approves or rejects it. Only they may withdraw it.
    CONSTRAINT ck_staff_limit_overrides_checker
        CHECK (status NOT IN ('APPROVED', 'REJECTED', 'SUPERSEDED', 'REVOKED')
            OR lower(decided_by) <> lower(proposed_by)),
    CONSTRAINT ck_staff_limit_overrides_withdrawn CHECK (status <> 'WITHDRAWN' OR decided_by = proposed_by),
    CONSTRAINT ck_staff_limit_overrides_superseded
        CHECK ((status = 'SUPERSEDED') = (superseded_by IS NOT NULL)
            AND (superseded_by IS NULL) = (superseded_at IS NULL)),
    CONSTRAINT ck_staff_limit_overrides_revoked
        CHECK ((status = 'REVOKED') = (revoked_by IS NOT NULL)
            AND (revoked_by IS NULL) = (revoked_at IS NULL)
            AND (revoked_by IS NULL) = (revocation_reason IS NULL))
);
-- One override in force per member, and one proposal waiting for them.
CREATE UNIQUE INDEX uq_staff_limit_overrides_approved ON staff_limit_overrides (staff_member_id)
    WHERE status = 'APPROVED';
CREATE UNIQUE INDEX uq_staff_limit_overrides_pending ON staff_limit_overrides (staff_member_id)
    WHERE status = 'PENDING';

-- The override an offer's amount came from, when it did not come from the grade limit.
ALTER TABLE staff_offers ADD COLUMN limit_override_id BIGINT;
ALTER TABLE staff_offers ADD CONSTRAINT fk_staff_offers_limit_override
    FOREIGN KEY (limit_override_id) REFERENCES staff_limit_overrides (id);

-- Members a run left out because Credit set their limit to 0. Every run completed so far ran before overrides
-- existed, so excluded none.
ALTER TABLE staff_offer_runs ADD COLUMN excluded_by_override INTEGER;
UPDATE staff_offer_runs SET excluded_by_override = 0 WHERE status = 'COMPLETED';
ALTER TABLE staff_offer_runs DROP CONSTRAINT ck_staff_offer_runs_completed;
ALTER TABLE staff_offer_runs ADD CONSTRAINT ck_staff_offer_runs_completed
    CHECK (CASE WHEN status = 'COMPLETED'
                THEN reconciliation_id IS NOT NULL
                    AND num_nulls(register_members, ineligible, excluded_active_loan, excluded_arrears,
                                  excluded_by_reconciliation, excluded_by_override, eligible, offered, refreshed,
                                  already_offered, withdrawn, expired) = 0
                ELSE num_nonnulls(register_members, ineligible, excluded_active_loan, excluded_arrears,
                                  excluded_by_reconciliation, excluded_by_override, eligible, offered, refreshed,
                                  already_offered, withdrawn, expired) = 0 END);
ALTER TABLE staff_offer_runs DROP CONSTRAINT ck_staff_offer_runs_counts;
ALTER TABLE staff_offer_runs ADD CONSTRAINT ck_staff_offer_runs_counts
    CHECK (status <> 'COMPLETED' OR (
        register_members = ineligible + excluded_active_loan + excluded_arrears + excluded_by_reconciliation
            + excluded_by_override + eligible
        AND eligible = offered + refreshed + already_offered
        AND LEAST(ineligible, excluded_active_loan, excluded_arrears, excluded_by_reconciliation, excluded_by_override,
                  offered, refreshed, already_offered, withdrawn, expired) >= 0));

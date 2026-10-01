-- Staff Grocery Loan grade-to-limit matrix (FR-SGL-009, FR-SGL-010). Each Paterson grade maps to one maximum loan
-- amount and a score band label, effective from a market date. A row is a CHANGE to the matrix: proposed by one user
-- and approved or rejected by another (maker-checker). The limit in force for a grade on a day is its APPROVED change
-- with the latest effective date on or before that day, so a later change never rewrites what applied before it.
CREATE TABLE staff_grade_limit_changes (
    id               BIGSERIAL      NOT NULL,
    grade            VARCHAR(16)    NOT NULL,
    score_band       VARCHAR(40)    NOT NULL,
    maximum_limit    NUMERIC(19, 2) NOT NULL,
    effective_from   DATE           NOT NULL,
    status           VARCHAR(16)    NOT NULL,
    proposed_by      VARCHAR(255)   NOT NULL,
    proposed_at      TIMESTAMP(6)   NOT NULL,
    proposal_comment VARCHAR(255),
    decided_by       VARCHAR(255),
    decided_at       TIMESTAMP(6),
    decision_comment VARCHAR(255),
    -- An approved change replaced by a later approval for the same grade and date, before that date arrived.
    superseded_by    BIGINT,
    superseded_at    TIMESTAMP(6),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT staff_grade_limit_changes_pkey PRIMARY KEY (id),
    CONSTRAINT ck_staff_grade_limit_changes_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'SUPERSEDED')),
    CONSTRAINT ck_staff_grade_limit_changes_maximum_limit CHECK (maximum_limit >= 0),
    CONSTRAINT ck_staff_grade_limit_changes_decided
        CHECK ((status = 'PENDING') = (decided_by IS NULL) AND (decided_by IS NULL) = (decided_at IS NULL)),
    -- Maker-checker: whoever proposed a change never approves or rejects it. Only they may withdraw it.
    CONSTRAINT ck_staff_grade_limit_changes_checker
        CHECK (status NOT IN ('APPROVED', 'REJECTED', 'SUPERSEDED') OR lower(decided_by) <> lower(proposed_by)),
    CONSTRAINT ck_staff_grade_limit_changes_withdrawn
        CHECK (status <> 'WITHDRAWN' OR decided_by = proposed_by),
    CONSTRAINT ck_staff_grade_limit_changes_superseded
        CHECK ((status = 'SUPERSEDED') = (superseded_by IS NOT NULL)
            AND (superseded_by IS NULL) = (superseded_at IS NULL)),
    CONSTRAINT fk_staff_grade_limit_changes_superseded_by
        FOREIGN KEY (superseded_by) REFERENCES staff_grade_limit_changes (id)
);

-- One approved limit per grade per effective date, and one proposal waiting for it.
CREATE UNIQUE INDEX uq_staff_grade_limit_changes_approved
    ON staff_grade_limit_changes (grade, effective_from) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX uq_staff_grade_limit_changes_pending
    ON staff_grade_limit_changes (grade, effective_from) WHERE status = 'PENDING';
CREATE INDEX idx_staff_grade_limit_changes_grade ON staff_grade_limit_changes (grade);

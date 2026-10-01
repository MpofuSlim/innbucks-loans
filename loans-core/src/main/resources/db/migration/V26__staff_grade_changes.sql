-- Retiring and renaming a grade (FR-SGL-010): the bank renames, merges and drops grades, so the matrix's grades are
-- managed from the portal like its limits, under maker-checker. A row is a proposal to retire a grade, or to rename it:
-- proposed by one user and approved or rejected by another, and withdrawn only by its proposer.
CREATE TABLE staff_grade_changes (
    id                     BIGSERIAL    NOT NULL,
    action                 VARCHAR(16)  NOT NULL,
    grade                  VARCHAR(32)  NOT NULL,
    -- The new name, for a RENAME.
    new_grade              VARCHAR(32),
    status                 VARCHAR(16)  NOT NULL,
    proposed_by            VARCHAR(255) NOT NULL,
    proposed_at            TIMESTAMP(6) NOT NULL,
    proposal_comment       VARCHAR(255),
    decided_by             VARCHAR(255),
    decided_at             TIMESTAMP(6),
    decision_comment       VARCHAR(255),
    -- What an approved RENAME moved to the new name.
    staff_members_moved    INTEGER,
    limit_overrides_moved  INTEGER,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT staff_grade_changes_pkey PRIMARY KEY (id),
    CONSTRAINT ck_staff_grade_changes_action CHECK (action IN ('RETIRE', 'RENAME')),
    CONSTRAINT ck_staff_grade_changes_new_grade
        CHECK ((action = 'RENAME') = (new_grade IS NOT NULL) AND new_grade IS DISTINCT FROM grade),
    CONSTRAINT ck_staff_grade_changes_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_staff_grade_changes_decided
        CHECK ((status = 'PENDING') = (decided_by IS NULL) AND (decided_by IS NULL) = (decided_at IS NULL)),
    -- Maker-checker: whoever proposed a change never approves or rejects it. Only they may withdraw it.
    CONSTRAINT ck_staff_grade_changes_checker
        CHECK (status NOT IN ('APPROVED', 'REJECTED') OR lower(decided_by) <> lower(proposed_by)),
    CONSTRAINT ck_staff_grade_changes_withdrawn CHECK (status <> 'WITHDRAWN' OR decided_by = proposed_by),
    CONSTRAINT ck_staff_grade_changes_moved
        CHECK ((action = 'RENAME' AND status = 'APPROVED') = (staff_members_moved IS NOT NULL)
            AND (staff_members_moved IS NULL) = (limit_overrides_moved IS NULL))
);

-- One proposal at a time for a grade, and for a new name.
CREATE UNIQUE INDEX uq_staff_grade_changes_pending ON staff_grade_changes (grade) WHERE status = 'PENDING';
CREATE UNIQUE INDEX uq_staff_grade_changes_pending_new_grade
    ON staff_grade_changes (new_grade) WHERE status = 'PENDING';
CREATE INDEX idx_staff_grade_changes_grade ON staff_grade_changes (grade);

-- A retired or renamed grade's approved limits leave the matrix: RETIRED, naming the change that retired them. They
-- stay as the history of what the grade could borrow, and offers keep pointing at the limit they were made at. A grade
-- brought back later starts from limits approved afresh, so none of its old ones applies again.
ALTER TABLE staff_grade_limit_changes DROP CONSTRAINT ck_staff_grade_limit_changes_status;
ALTER TABLE staff_grade_limit_changes ADD CONSTRAINT ck_staff_grade_limit_changes_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'SUPERSEDED', 'RETIRED'));
ALTER TABLE staff_grade_limit_changes DROP CONSTRAINT ck_staff_grade_limit_changes_checker;
ALTER TABLE staff_grade_limit_changes ADD CONSTRAINT ck_staff_grade_limit_changes_checker
    CHECK (status NOT IN ('APPROVED', 'REJECTED', 'SUPERSEDED', 'RETIRED') OR lower(decided_by) <> lower(proposed_by));
ALTER TABLE staff_grade_limit_changes ADD COLUMN retired_by BIGINT;
ALTER TABLE staff_grade_limit_changes ADD COLUMN retired_at TIMESTAMP(6);
ALTER TABLE staff_grade_limit_changes ADD CONSTRAINT ck_staff_grade_limit_changes_retired
    CHECK ((status = 'RETIRED') = (retired_by IS NOT NULL) AND (retired_by IS NULL) = (retired_at IS NULL));
ALTER TABLE staff_grade_limit_changes ADD CONSTRAINT fk_staff_grade_limit_changes_retired_by
    FOREIGN KEY (retired_by) REFERENCES staff_grade_changes (id);

-- A rename moves every staff member at the grade to the new name, and their record's history says so: a change comes
-- either from a register batch or from a grade change, never both.
ALTER TABLE staff_member_changes ALTER COLUMN batch_id DROP NOT NULL;
ALTER TABLE staff_member_changes ADD COLUMN grade_change_id BIGINT;
ALTER TABLE staff_member_changes ADD CONSTRAINT fk_staff_member_changes_grade_change
    FOREIGN KEY (grade_change_id) REFERENCES staff_grade_changes (id);
ALTER TABLE staff_member_changes ADD CONSTRAINT ck_staff_member_changes_source
    CHECK ((batch_id IS NULL) <> (grade_change_id IS NULL));

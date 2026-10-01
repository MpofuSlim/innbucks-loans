-- The Staff Register (FR-SGL-001 to FR-SGL-007): the master control for the Staff Grocery Loan. Who may borrow, and
-- how much, is decided by this register and the grade-to-limit matrix (V16), so it changes only under maker-checker:
-- Human Capital submits a batch (a file upload or a single change), and a different person approves or rejects it.
-- Nothing reaches staff_members until a batch is approved, and every field it changes is recorded.

-- Human Capital owns the register.
ALTER TABLE user_groups DROP CONSTRAINT ck_user_groups_user_group;
ALTER TABLE user_groups ADD CONSTRAINT ck_user_groups_user_group
    CHECK (user_group IN ('AGENTS', 'SUPER_ADMIN', 'CREDIT_MANAGER', 'FINANCE', 'HUMAN_CAPITAL'));

CREATE TABLE staff_members (
    id                    BIGSERIAL    NOT NULL,
    employee_number       VARCHAR(32)  NOT NULL,
    full_name             VARCHAR(160) NOT NULL,
    national_id           VARCHAR(20)  NOT NULL,
    msisdn                VARCHAR(12)  NOT NULL,
    grade                 VARCHAR(16)  NOT NULL,
    department            VARCHAR(120) NOT NULL,
    employment_status     VARCHAR(16)  NOT NULL,
    engagement_date       DATE         NOT NULL,
    wallet_account_number VARCHAR(20)  NOT NULL,
    status_changed_at     TIMESTAMP(6) NOT NULL,
    created_batch_id      BIGINT       NOT NULL,
    created_at            TIMESTAMP(6) NOT NULL,
    updated_batch_id      BIGINT       NOT NULL,
    updated_at            TIMESTAMP(6) NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT staff_members_pkey PRIMARY KEY (id),
    CONSTRAINT uq_staff_members_employee_number UNIQUE (employee_number),
    -- One register entry per mobile number: offers and vouchers are sent to it.
    CONSTRAINT uq_staff_members_msisdn UNIQUE (msisdn),
    CONSTRAINT ck_staff_members_employment_status
        CHECK (employment_status IN ('ACTIVE', 'RESIGNED', 'TERMINATED', 'SUSPENDED', 'UNPAID_LEAVE')),
    CONSTRAINT ck_staff_members_msisdn CHECK (msisdn ~ '^2637[13789][0-9]{7}$')
);
CREATE INDEX idx_staff_members_national_id ON staff_members (national_id);
CREATE INDEX idx_staff_members_grade ON staff_members (grade);

-- A maker's submission: an uploaded file, or one record changed on the admin screen.
CREATE TABLE staff_register_batches (
    id               BIGSERIAL    NOT NULL,
    source           VARCHAR(16)  NOT NULL,
    file_name        VARCHAR(255),
    file_sha256      VARCHAR(64),
    status           VARCHAR(16)  NOT NULL,
    submitted_by     VARCHAR(255) NOT NULL,
    submitted_at     TIMESTAMP(6) NOT NULL,
    submission_comment VARCHAR(255),
    total_rows       INTEGER      NOT NULL,
    staged_rows      INTEGER      NOT NULL,
    rejected_rows    INTEGER      NOT NULL,
    decided_by       VARCHAR(255),
    decided_at       TIMESTAMP(6),
    decision_comment VARCHAR(255),
    created_rows     INTEGER,
    amended_rows     INTEGER,
    unchanged_rows   INTEGER,
    skipped_rows     INTEGER,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT staff_register_batches_pkey PRIMARY KEY (id),
    CONSTRAINT ck_staff_register_batches_source CHECK (source IN ('UPLOAD', 'MANUAL')),
    CONSTRAINT ck_staff_register_batches_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_staff_register_batches_file CHECK ((source = 'UPLOAD') = (file_sha256 IS NOT NULL)),
    CONSTRAINT ck_staff_register_batches_counts
        CHECK (staged_rows > 0 AND rejected_rows >= 0 AND total_rows = staged_rows + rejected_rows),
    CONSTRAINT ck_staff_register_batches_decided
        CHECK ((status = 'PENDING') = (decided_by IS NULL) AND (decided_by IS NULL) = (decided_at IS NULL)),
    -- Maker-checker (FR-SGL-004): whoever submitted a batch never approves or rejects it. Only they may withdraw it.
    CONSTRAINT ck_staff_register_batches_checker
        CHECK (status NOT IN ('APPROVED', 'REJECTED') OR lower(decided_by) <> lower(submitted_by)),
    CONSTRAINT ck_staff_register_batches_withdrawn CHECK (status <> 'WITHDRAWN' OR decided_by = submitted_by),
    CONSTRAINT ck_staff_register_batches_applied
        CHECK ((status = 'APPROVED') = (created_rows IS NOT NULL)
            AND (created_rows IS NULL) = (amended_rows IS NULL)
            AND (created_rows IS NULL) = (unchanged_rows IS NULL)
            AND (created_rows IS NULL) = (skipped_rows IS NULL))
);
CREATE INDEX idx_staff_register_batches_status ON staff_register_batches (status);

-- Each row of a batch as submitted: refused at upload with its reasons, or staged until the batch is decided and then
-- applied, found unchanged, or skipped because the register changed in between. Values are kept as text: a refused
-- row may not parse.
CREATE TABLE staff_register_rows (
    id                    BIGSERIAL    NOT NULL,
    batch_id              BIGINT       NOT NULL,
    row_number            INTEGER      NOT NULL,
    outcome               VARCHAR(16)  NOT NULL,
    action                VARCHAR(16),
    employee_number       VARCHAR(255),
    full_name             VARCHAR(255),
    national_id           VARCHAR(255),
    msisdn                VARCHAR(255),
    grade                 VARCHAR(255),
    department            VARCHAR(255),
    employment_status     VARCHAR(255),
    engagement_date       VARCHAR(255),
    wallet_account_number VARCHAR(255),
    errors                TEXT,
    staff_member_id       BIGINT,
    CONSTRAINT staff_register_rows_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_register_rows_batch FOREIGN KEY (batch_id) REFERENCES staff_register_batches (id),
    CONSTRAINT fk_staff_register_rows_staff_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT uq_staff_register_rows_row UNIQUE (batch_id, row_number),
    CONSTRAINT ck_staff_register_rows_outcome
        CHECK (outcome IN ('REJECTED', 'STAGED', 'CREATED', 'AMENDED', 'UNCHANGED', 'SKIPPED')),
    CONSTRAINT ck_staff_register_rows_action CHECK (action IN ('CREATE', 'AMEND')),
    CONSTRAINT ck_staff_register_rows_errors CHECK ((outcome IN ('REJECTED', 'SKIPPED')) = (errors IS NOT NULL)),
    CONSTRAINT ck_staff_register_rows_applied
        CHECK ((outcome IN ('CREATED', 'AMENDED', 'UNCHANGED')) = (staff_member_id IS NOT NULL))
);

-- Every change to a staff record, field by field (FR-SGL-006): what it was, what it became, who submitted it, who
-- approved it, and when. A record's creation is recorded the same way, from nothing.
CREATE TABLE staff_member_changes (
    id              BIGSERIAL    NOT NULL,
    staff_member_id BIGINT       NOT NULL,
    batch_id        BIGINT       NOT NULL,
    field           VARCHAR(40)  NOT NULL,
    previous_value  VARCHAR(255),
    new_value       VARCHAR(255) NOT NULL,
    submitted_by    VARCHAR(255) NOT NULL,
    approved_by     VARCHAR(255) NOT NULL,
    changed_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT staff_member_changes_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_member_changes_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT fk_staff_member_changes_batch FOREIGN KEY (batch_id) REFERENCES staff_register_batches (id)
);
CREATE INDEX idx_staff_member_changes_member ON staff_member_changes (staff_member_id);

ALTER TABLE staff_members ADD CONSTRAINT fk_staff_members_created_batch
    FOREIGN KEY (created_batch_id) REFERENCES staff_register_batches (id);
ALTER TABLE staff_members ADD CONSTRAINT fk_staff_members_updated_batch
    FOREIGN KEY (updated_batch_id) REFERENCES staff_register_batches (id);

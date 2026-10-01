-- Reconciling the Staff Register against the HR payroll master (FR-SGL-008). Human Capital uploads the payroll master
-- each month; it is compared with the register, employee by employee, and the variances are kept as a report. A
-- reconciliation changes nothing on the register: correcting it is an ordinary batch under maker-checker (V17).

CREATE TABLE staff_register_reconciliations (
    id                       BIGSERIAL    NOT NULL,
    file_name                VARCHAR(255) NOT NULL,
    file_sha256              VARCHAR(64)  NOT NULL,
    run_by                   VARCHAR(255) NOT NULL,
    run_at                   TIMESTAMP(6) NOT NULL,
    comment                  VARCHAR(255),
    -- The register fields the file had a column for, comma-separated; empty when it had only employee numbers.
    compared_fields          VARCHAR(255) NOT NULL,
    payroll_rows             INTEGER      NOT NULL,
    register_members         INTEGER      NOT NULL,
    matched                  INTEGER      NOT NULL,
    different                INTEGER      NOT NULL,
    left_on_payroll          INTEGER      NOT NULL,
    left_on_payroll_eligible INTEGER      NOT NULL,
    not_on_register          INTEGER      NOT NULL,
    not_on_payroll           INTEGER      NOT NULL,
    not_on_payroll_eligible  INTEGER      NOT NULL,
    duplicates_on_payroll    INTEGER      NOT NULL,
    unreadable_rows          INTEGER      NOT NULL,
    CONSTRAINT staff_register_reconciliations_pkey PRIMARY KEY (id),
    CONSTRAINT ck_staff_register_reconciliations_counts
        CHECK (payroll_rows > 0 AND register_members >= 0 AND matched >= 0 AND different >= 0
            AND left_on_payroll >= 0 AND not_on_register >= 0 AND not_on_payroll >= 0 AND duplicates_on_payroll >= 0
            AND unreadable_rows >= 0
            AND left_on_payroll_eligible BETWEEN 0 AND left_on_payroll
            AND not_on_payroll_eligible BETWEEN 0 AND not_on_payroll)
);

-- One line of the report. What it carries beyond its kind and the employee depends on the kind, so it is kept as
-- JSON: the register's record, the payroll row as sent, the fields that differ, or why a row could not be read.
CREATE TABLE staff_register_variances (
    id                BIGSERIAL    NOT NULL,
    reconciliation_id BIGINT       NOT NULL,
    kind              VARCHAR(24)  NOT NULL,
    employee_number   VARCHAR(255),
    full_name         VARCHAR(255),
    -- Only for someone the register has as employed whom the payroll says has left, or does not list: whether they
    -- could borrow when it ran.
    eligible          BOOLEAN,
    details           TEXT         NOT NULL,
    CONSTRAINT staff_register_variances_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_register_variances_reconciliation
        FOREIGN KEY (reconciliation_id) REFERENCES staff_register_reconciliations (id),
    CONSTRAINT ck_staff_register_variances_kind
        CHECK (kind IN ('LEFT_ON_PAYROLL', 'NOT_ON_PAYROLL', 'DIFFERENT', 'NOT_ON_REGISTER', 'DUPLICATE_ON_PAYROLL',
            'UNREADABLE')),
    CONSTRAINT ck_staff_register_variances_employee CHECK (kind = 'UNREADABLE' OR employee_number IS NOT NULL),
    CONSTRAINT ck_staff_register_variances_eligible
        CHECK ((kind IN ('LEFT_ON_PAYROLL', 'NOT_ON_PAYROLL')) = (eligible IS NOT NULL))
);
CREATE INDEX idx_staff_register_variances_reconciliation ON staff_register_variances (reconciliation_id, kind);

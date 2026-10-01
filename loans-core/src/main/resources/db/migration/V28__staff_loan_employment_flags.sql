-- A paid-out Staff Grocery Loan whose borrower stops being ACTIVE on the register (FR-SGL-007, BRD 3.8): the status
-- they moved to, when, and the register batch that moved them. Null while they are ACTIVE. Someone who left (RESIGNED,
-- TERMINATED) is recovered from their terminal benefits; for one suspended or on unpaid leave, Credit decides.
ALTER TABLE staff_loans ADD COLUMN employment_flag VARCHAR(16);
ALTER TABLE staff_loans ADD COLUMN employment_flagged_at TIMESTAMP(6);
ALTER TABLE staff_loans ADD COLUMN employment_flag_batch_id BIGINT;

ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_employment_flag
    CHECK (employment_flag IN ('RESIGNED', 'TERMINATED', 'SUSPENDED', 'UNPAID_LEAVE'));
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_employment_flag_complete
    CHECK ((employment_flag IS NULL AND employment_flagged_at IS NULL AND employment_flag_batch_id IS NULL)
        OR (employment_flag IS NOT NULL AND employment_flagged_at IS NOT NULL AND employment_flag_batch_id IS NOT NULL));

-- The flagged loans, for Human Capital, Payroll and Credit.
CREATE INDEX ix_staff_loans_employment_flag ON staff_loans (employment_flagged_at) WHERE employment_flag IS NOT NULL;

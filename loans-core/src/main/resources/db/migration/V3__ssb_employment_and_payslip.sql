-- SSB application capture (FR-SSB-003, FR-SSB-006): where the applicant works, the payslip's
-- existing deductions, and the InnBucks wallet the loan pays.

ALTER TABLE loans
    ADD COLUMN ministry      VARCHAR(255),
    ADD COLUMN station       VARCHAR(255),
    ADD COLUMN grade         VARCHAR(64),
    ADD COLUMN contract_type VARCHAR(32),
    ADD COLUMN wallet_number VARCHAR(255),
    ADD CONSTRAINT ck_loans_contract_type CHECK (contract_type IN ('PERMANENT','CONTRACT','TEMPORARY'));

-- Every loan so far has paid its mobile number; the wallet number says so explicitly from now on.
UPDATE loans SET wallet_number = mobile_number WHERE wallet_number IS NULL;

CREATE TABLE loan_payslip_deductions (
    loan_id     BIGINT NOT NULL,
    line_number INTEGER NOT NULL,
    beneficiary VARCHAR(255) NOT NULL,
    amount      NUMERIC(38,2) NOT NULL,
    CONSTRAINT loan_payslip_deductions_pkey PRIMARY KEY (loan_id, line_number),
    CONSTRAINT fk_loan_payslip_deductions_loan_id FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT ck_loan_payslip_deductions_amount CHECK (amount > 0)
);

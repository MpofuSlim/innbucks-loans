-- ============================================================================
-- InnBucks Loans — loan pricing parameters (PostgreSQL)
-- ============================================================================
-- The loan calculator (LoanServiceImpl.calculate) cannot quote without every
-- one of these rows in parameters, and the application never seeds them.
-- Apply with psql on a fresh database, then maintain the values there.
--
-- The NAMES are what the code requires. The VALUES are the ones the previous
-- product ran on (rates in percent, amounts in USD, tenors in months). They are
-- NOT approved SSB pricing: the BRD leaves amount, pricing and tenure open
-- (OQ-25), so replace them with the approved terms before launch.
--
-- The previous product's list also carried default_loan_amount (100) and
-- default_loan_tenor (3); nothing reads them, so they are left out.
--
-- Idempotent: an existing row is left as it is.
-- ============================================================================

INSERT INTO parameters (created_date, last_modified_date, version, user_can_edit, name, val)
SELECT now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC', 0, true, p.name, p.val
FROM (VALUES
    ('admin_fee_rate',        '6'),
    ('commission_rate',       '3'),
    ('monthly_interest_rate', '7'),
    ('minimum_loan_tenor',    '1'),
    ('maximum_loan_tenor',    '24'),
    ('minimum_loan_amount',   '20'),
    ('maximum_loan_amount',   '2000')
) AS p(name, val)
ON CONFLICT (name) DO NOTHING;

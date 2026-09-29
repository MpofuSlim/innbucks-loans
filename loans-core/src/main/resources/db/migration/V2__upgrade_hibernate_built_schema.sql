-- ============================================================================
-- Upgrades a database Hibernate built before Flyway (ddl-auto: update) to the
-- standard schema V1 creates, in place and without losing a row.
--
-- Flyway baselines such a database (tables but no history) at V1 and runs only
-- this. On a database V1 created, every step finds nothing to change: renames
-- check that the old name exists, and what is created here is checked for first
-- or recreated identically.
--
-- It also carries what LegacyDataMigration did at startup: the groups retired
-- with the merchant sales network, and the commission groups' old names.
--
-- Tables and columns of removed features stay, unread: bulk_ingestion_runs,
-- idempotency_records, and users.agent_id / loans.agent_id. Drop them once their
-- rows are not wanted; the statements are in docs/db/retired_objects.sql.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Tables: plural snake_case. "User" was quoted because user is reserved.
-- ----------------------------------------------------------------------------
ALTER TABLE IF EXISTS "User" RENAME TO users;
ALTER TABLE IF EXISTS loan_request RENAME TO loans;
ALTER TABLE IF EXISTS loan_batch RENAME TO loan_batches;
ALTER TABLE IF EXISTS loan_disbursement RENAME TO loan_disbursements;
ALTER TABLE IF EXISTS loan_saga RENAME TO loan_sagas;
ALTER TABLE IF EXISTS merchant RENAME TO merchants;
ALTER TABLE IF EXISTS commission_group RENAME TO commission_groups;
ALTER TABLE IF EXISTS channel RENAME TO channels;
ALTER TABLE IF EXISTS parameter RENAME TO parameters;

-- ----------------------------------------------------------------------------
-- 2. Columns: snake_case, and the misnamed ones. employee_name,
--    employee_contact_number and the employee_* address held the EMPLOYER's.
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT * FROM (VALUES
            ('users', 'externalSystemId', 'external_system_id'),
            ('users', 'firstName', 'first_name'),
            ('users', 'idNumber', 'id_number'),
            ('users', 'lastName', 'last_name'),
            ('users', 'mobileNumber', 'mobile_number'),
            ('users', 'physical_addess', 'physical_address'),
            ('users', 'temporaryPassword', 'temporary_password'),
            ('users', 'commissionGroup_id', 'commission_group_id'),
            ('channels', 'systemUser_id', 'system_user_id'),
            ('commission_groups', 'agentCommission', 'agent_commission'),
            ('commission_groups', 'providerCommission', 'provider_commission'),
            ('loans', 'disburse_amount', 'disbursed_amount'),
            ('loans', 'employee_city', 'employer_city'),
            ('loans', 'employee_country', 'employer_country'),
            ('loans', 'employee_street', 'employer_street'),
            ('loans', 'employee_suburb', 'employer_suburb'),
            ('loans', 'employee_contact_number', 'employer_contact_number'),
            ('loans', 'employee_name', 'employer_name'),
            ('loans', 'lineOfBusiness', 'line_of_business'),
            ('loans', 'loanPurpose', 'loan_purpose'),
            ('loans', 'number_of_dependencies', 'number_of_dependants'),
            ('loans', 'witness_date_singed', 'witness_date_signed'),
            ('merchants', 'accountNumber', 'account_number'),
            ('merchants', 'commissionStructure', 'commission_structure'),
            ('merchants', 'disbursementType', 'disbursement_type'),
            ('merchants', 'merchantCode', 'merchant_code'),
            ('merchants', 'physical_addess', 'physical_address'),
            ('merchants', 'commissionGroup_id', 'commission_group_id')
        ) AS v(table_name, old_name, new_name)
    LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns c
                   WHERE c.table_schema = current_schema() AND c.table_name = r.table_name
                     AND c.column_name = r.old_name) THEN
            EXECUTE format('ALTER TABLE %I RENAME COLUMN %I TO %I', r.table_name, r.old_name, r.new_name);
        END IF;
    END LOOP;
END $$;

-- ----------------------------------------------------------------------------
-- 3. Identity sequences are named after their table, which a table rename
--    does not carry over.
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    t text;
    seq text;
BEGIN
    FOREACH t IN ARRAY ARRAY['users', 'channels', 'commission_groups', 'merchants', 'loans', 'loan_batches',
                             'loan_disbursements', 'loan_sagas', 'ledger_entries', 'audit_logs', 'parameters']
    LOOP
        seq := pg_get_serial_sequence(t, 'id');
        IF seq IS NOT NULL AND seq <> format('%I.%I', current_schema(), t || '_id_seq') THEN
            EXECUTE format('ALTER SEQUENCE %s RENAME TO %I', seq, t || '_id_seq');
        END IF;
    END LOOP;
END $$;

-- ----------------------------------------------------------------------------
-- 4. Constraints: Hibernate named them at random (FK6mip3gy30..., UKny4wfq...)
--    or after the old tables. Each is renamed from its own definition, so the
--    names match V1's whatever Hibernate called them.
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    r record;
    target text;
BEGIN
    FOR r IN
        SELECT con.conname, con.contype, con.conrelid, rel.relname AS table_name,
               (SELECT string_agg(a.attname, '_' ORDER BY k.ord)
                FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum) AS columns
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relnamespace = current_schema()::regnamespace
          AND rel.relname IN ('users', 'user_groups', 'channels', 'commission_groups', 'merchants', 'loans',
                              'loan_batches', 'loan_disbursements', 'loan_sagas', 'ledger_entries', 'audit_logs',
                              'parameters')
          AND con.contype IN ('p', 'f', 'u', 'c')
    LOOP
        target := CASE r.contype
            WHEN 'p' THEN r.table_name || '_pkey'
            WHEN 'f' THEN 'fk_' || r.table_name || '_' || r.columns
            WHEN 'u' THEN 'uq_' || r.table_name || '_' || r.columns
            WHEN 'c' THEN 'ck_' || r.table_name || '_' || r.columns
        END;
        IF r.conname <> target
           AND NOT EXISTS (SELECT 1 FROM pg_constraint x WHERE x.conrelid = r.conrelid AND x.conname = target) THEN
            EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', r.table_name, r.conname, target);
        END IF;
    END LOOP;
END $$;

-- user_groups had only a unique (user_id, user_group); its natural key is the primary key.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'user_groups'::regclass AND contype = 'p') THEN
        DELETE FROM user_groups WHERE user_group IS NULL;
        ALTER TABLE user_groups ALTER COLUMN user_group SET NOT NULL;
        ALTER TABLE user_groups DROP CONSTRAINT IF EXISTS uq_user_groups_user_id_user_group;
        ALTER TABLE user_groups ADD CONSTRAINT user_groups_pkey PRIMARY KEY (user_id, user_group);
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 5. Indexes. The unique constraint on external_system_id already indexes it.
-- ----------------------------------------------------------------------------
ALTER INDEX IF EXISTS idx_loan_request_batch_number RENAME TO idx_loans_batch_number;
ALTER INDEX IF EXISTS idx_ec_number RENAME TO idx_loans_ec_number;
ALTER INDEX IF EXISTS idx_loan_request_upper_ec_number RENAME TO idx_loans_upper_ec_number;
ALTER INDEX IF EXISTS idx_loan_request_upper_national_id RENAME TO idx_loans_upper_national_id_number;
ALTER INDEX IF EXISTS uq_loan_public_reference RENAME TO uq_loans_public_reference;
ALTER INDEX IF EXISTS idx_merchant_code RENAME TO idx_merchants_merchant_code;
ALTER INDEX IF EXISTS idx_channel_id RENAME TO idx_channels_channel_id;
ALTER INDEX IF EXISTS idx_loan_saga_state RENAME TO idx_loan_sagas_current_state;
ALTER INDEX IF EXISTS idx_audit_entity RENAME TO idx_audit_logs_entity_type_entity_id;
ALTER INDEX IF EXISTS idx_audit_correlation RENAME TO idx_audit_logs_correlation_id;
ALTER INDEX IF EXISTS idx_audit_created_at RENAME TO idx_audit_logs_created_at;
ALTER INDEX IF EXISTS idx_ledger_loan_id RENAME TO idx_ledger_entries_loan_id;
ALTER INDEX IF EXISTS idx_ledger_account RENAME TO idx_ledger_entries_account;
ALTER INDEX IF EXISTS idx_ledger_tx_ref RENAME TO idx_ledger_entries_transaction_ref;
DROP INDEX IF EXISTS idx_external_system_id;

-- ----------------------------------------------------------------------------
-- 6. Groups retired with the merchant sales network. A user holding one cannot
--    be loaded at all, so they are rewritten, and each changed account's
--    sessions end so its next sign-in carries the new group.
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    groupless text;
BEGIN
    UPDATE users SET token_version = COALESCE(token_version, 0) + 1
    WHERE id IN (SELECT user_id FROM user_groups
                 WHERE user_group IN ('BULKIT_ADMIN', 'SUB_AGENTS', 'ORGANISATION_SUPER_USER', 'RETAIL_SALES'));

    ALTER TABLE user_groups DROP CONSTRAINT IF EXISTS ck_user_groups_user_group;

    DELETE FROM user_groups g WHERE g.user_group = 'BULKIT_ADMIN'
        AND EXISTS (SELECT 1 FROM user_groups h WHERE h.user_id = g.user_id AND h.user_group = 'SUPER_ADMIN');
    UPDATE user_groups SET user_group = 'SUPER_ADMIN' WHERE user_group = 'BULKIT_ADMIN';

    DELETE FROM user_groups g WHERE g.user_group = 'SUB_AGENTS'
        AND EXISTS (SELECT 1 FROM user_groups h WHERE h.user_id = g.user_id AND h.user_group = 'AGENTS');
    UPDATE user_groups SET user_group = 'AGENTS' WHERE user_group = 'SUB_AGENTS';

    SELECT string_agg(u.username, ', ' ORDER BY u.username) INTO groupless
    FROM users u
    WHERE EXISTS (SELECT 1 FROM user_groups g
                  WHERE g.user_id = u.id AND g.user_group IN ('ORGANISATION_SUPER_USER', 'RETAIL_SALES'))
      AND NOT EXISTS (SELECT 1 FROM user_groups g
                      WHERE g.user_id = u.id AND g.user_group NOT IN ('ORGANISATION_SUPER_USER', 'RETAIL_SALES'));
    DELETE FROM user_groups WHERE user_group IN ('ORGANISATION_SUPER_USER', 'RETAIL_SALES');
    IF groupless IS NOT NULL THEN
        RAISE WARNING 'These accounts now hold no group, because theirs was retired: %. Assign a group or retire them.',
            groupless;
    END IF;

    ALTER TABLE user_groups ADD CONSTRAINT ck_user_groups_user_group
        CHECK (user_group IN ('AGENTS', 'SUPER_ADMIN', 'CREDIT_MANAGER', 'FINANCE'));
END $$;

-- The seeded commission groups' old names. Renamed in place, so the merchants
-- and users pointing at them keep the same rows.
UPDATE commission_groups c SET name = v.new_name
FROM (VALUES ('80-20-Favouring-BulkIT', '80-20-Favouring-InnBucks'),
             ('100-Favouring-BulkIT', '100-Favouring-InnBucks'),
             ('Default-BulkIT', 'Default-InnBucks')) AS v(old_name, new_name)
WHERE lower(c.name) = lower(v.old_name)
  AND NOT EXISTS (SELECT 1 FROM commission_groups x WHERE lower(x.name) = lower(v.new_name));

-- ----------------------------------------------------------------------------
-- 7. What Hibernate could not create: the application used to make these at
--    startup, or left them to a script run by hand.
-- ----------------------------------------------------------------------------
-- Checked before creating, rather than IF NOT EXISTS, which Flyway logs as a WARN.
DO $$
BEGIN
    IF to_regclass('loan_public_ref_seq') IS NULL THEN
        CREATE SEQUENCE loan_public_ref_seq START WITH 1;
    END IF;
    IF to_regclass('uq_loans_public_reference') IS NULL THEN
        CREATE UNIQUE INDEX uq_loans_public_reference ON loans (public_reference) WHERE public_reference IS NOT NULL;
    END IF;
    IF to_regclass('idx_loans_upper_ec_number') IS NULL THEN
        CREATE INDEX idx_loans_upper_ec_number ON loans (upper(ec_number));
    END IF;
    IF to_regclass('idx_loans_upper_national_id_number') IS NULL THEN
        CREATE INDEX idx_loans_upper_national_id_number ON loans (upper(national_id_number));
    END IF;
END $$;

CREATE OR REPLACE FUNCTION ledger_entries_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is forbidden. Post a reversing entry instead.', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ledger_entries_immutable ON ledger_entries;
CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_immutable();

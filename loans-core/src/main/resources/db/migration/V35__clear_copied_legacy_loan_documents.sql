-- Clears the legacy document columns on loans (payslip_picture, national_id_picture, signature,
-- witness_signature) where V6's copy in loan_documents provably holds the same bytes. Nothing has read or
-- written those columns since V6 (the same change removed their mappings from Loan and Witness), but V6 left
-- them as they were, so every pre-V6 loan still carries its documents twice.
--
-- What a legacy value is, exactly as V6 read it: either the base64 itself (optionally a data: URL), or, as
-- Hibernate stored a @Lob String, the OID of a large object holding that base64 (the column then holds only
-- digits and the bytes live in pg_largeobject, not in loans' TOAST). V6 decoded each to bytes and inserted
-- version 1 of the matching document type (PAYSLIP, NATIONAL_ID, SIGNATURE, WITNESS_SIGNATURE). It skipped a
-- blank value or one that decoded to nothing, and a value it could not read or decode stayed on the loan with
-- a NOTICE: those have no copy, and stay.
--
-- A value is cleared only when, read and decoded the same way, its bytes equal the content of a loan_documents
-- row of the SAME loan and document type (found by the indexed sha256, proven by comparing the bytes
-- themselves). An OID value is cleared only when no other legacy value names the same large object, and its
-- large object is unlinked in the same step: setting the column to NULL alone would orphan it, and nothing would
-- then say which loan it belonged to. Everything else is left untouched (no copy, a copy with different bytes,
-- a shared or unreadable large object, a value that does not decode, a blank) and counted in the closing NOTICE.
-- The data: URL's declared type is the only thing V6 did not carry over (loan_documents sniffs the type from
-- the bytes); nothing reads it.
--
-- Columns are NOT dropped: a DROP cannot be undone and is the owner's decision. loans.version and
-- last_modified_date are not touched either: the columns are not mapped, so no entity state changes, and a
-- version bump would only fail an in-flight optimistic lock for nothing.
--
-- Locks: one UPDATE per affected loan, in Flyway's single transaction. That takes ROW EXCLUSIVE on loans (reads
-- and other rows' writes go on; only DDL, VACUUM FULL and explicit table locks wait) and row locks on the
-- pre-V6 loans that still carry a document, held until commit. Batching would not shorten that inside one
-- transaction, and a non-transactional migration would trade it for a half-applied state Flyway marks failed.
-- The set is small and closed (nothing writes these columns, so no loan created since V6 is in it), and
-- loans-service deploys with strategy Recreate, so no other loans process runs while this does.
--
-- Space: this frees nothing by itself. VACUUM makes the dead row versions, their TOAST chunks and the unlinked
-- large objects' pages reusable; VACUUM FULL (or pg_repack) returns them to the OS but locks the table
-- exclusively while it runs. Both are an operator step after the deploy, not part of this migration:
--   VACUUM (VERBOSE, ANALYZE) loans; VACUUM (VERBOSE) pg_largeobject; VACUUM (VERBOSE) pg_largeobject_metadata;
-- (the two catalogs need the database superuser). Never run vacuumlo on this database while a legacy value
-- still holds an OID: vacuumlo only sees oid/lo-typed columns, and these are TEXT, so it would delete those
-- large objects, including the ones this migration left because they have no copy.
--
-- Rollback: every build since V6 ignores these columns, so a rollback reads nothing it expects. Flyway ignores
-- this applied migration under the older build.

DO
$$
    DECLARE
        loan          RECORD;
        cell          RECORD;
        payload       TEXT;
        bytes         BYTEA;
        large_object  OID;
        to_clear      TEXT[];
        cleared       INTEGER := 0;
        no_copy       INTEGER := 0;
        different     INTEGER := 0;
        shared        INTEGER := 0;
        unreadable    INTEGER := 0;
        loans_cleared INTEGER := 0;
        loans_left    INTEGER;
    BEGIN
        -- How many legacy values name each large object, over all four columns.
        CREATE TEMPORARY TABLE v35_large_object_uses ON COMMIT DROP AS
        SELECT stored, count(*) AS uses
        FROM (SELECT payslip_picture AS stored FROM loans
              UNION ALL SELECT national_id_picture FROM loans
              UNION ALL SELECT signature FROM loans
              UNION ALL SELECT witness_signature FROM loans) legacy
        WHERE stored ~ '^[0-9]+$'
        GROUP BY stored;

        FOR loan IN SELECT id, payslip_picture, national_id_picture, signature, witness_signature
                    FROM loans
                    WHERE payslip_picture IS NOT NULL
                       OR national_id_picture IS NOT NULL
                       OR signature IS NOT NULL
                       OR witness_signature IS NOT NULL
                    ORDER BY id
            LOOP
                to_clear := ARRAY []::TEXT[];
                FOR cell IN SELECT *
                            FROM (VALUES ('PAYSLIP', loan.payslip_picture),
                                         ('NATIONAL_ID', loan.national_id_picture),
                                         ('SIGNATURE', loan.signature),
                                         ('WITNESS_SIGNATURE', loan.witness_signature)) AS c(document_type, stored)
                            WHERE c.stored IS NOT NULL
                    LOOP
                        BEGIN
                            -- V6's migrate_loan_document_bytes, step for step.
                            large_object := NULL;
                            bytes := NULL;
                            IF BTRIM(cell.stored) <> '' THEN
                                IF cell.stored ~ '^[0-9]+$' THEN
                                    large_object := cell.stored::OID;
                                    payload := CONVERT_FROM(LO_GET(large_object), 'UTF8');
                                ELSE
                                    payload := cell.stored;
                                END IF;
                                IF payload LIKE 'data:%' AND POSITION(',' IN payload) > 0 THEN
                                    payload := SUBSTRING(payload FROM POSITION(',' IN payload) + 1);
                                END IF;
                                bytes := DECODE(REGEXP_REPLACE(payload, '\s', '', 'g'), 'base64');
                            END IF;

                            IF bytes IS NULL OR LENGTH(bytes) = 0 THEN
                                unreadable := unreadable + 1;
                            ELSIF NOT EXISTS (SELECT 1
                                              FROM loan_documents d
                                              WHERE d.loan_id = loan.id
                                                AND d.document_type = cell.document_type) THEN
                                no_copy := no_copy + 1;
                            ELSIF NOT EXISTS (SELECT 1
                                              FROM loan_documents d
                                              WHERE d.loan_id = loan.id
                                                AND d.document_type = cell.document_type
                                                AND d.sha256 = ENCODE(SHA256(bytes), 'hex')
                                                AND d.content = bytes) THEN
                                different := different + 1;
                            ELSIF large_object IS NOT NULL
                                AND (SELECT uses FROM v35_large_object_uses WHERE stored = cell.stored) > 1 THEN
                                shared := shared + 1;
                            ELSE
                                IF large_object IS NOT NULL THEN
                                    PERFORM LO_UNLINK(large_object);
                                END IF;
                                to_clear := to_clear || cell.document_type;
                            END IF;
                        EXCEPTION
                            WHEN OTHERS THEN
                                unreadable := unreadable + 1;
                                RAISE NOTICE 'Loan % % stays on the loan row: %', loan.id, cell.document_type, SQLERRM;
                        END;
                    END LOOP;

                IF CARDINALITY(to_clear) > 0 THEN
                    UPDATE loans
                    SET payslip_picture     = CASE WHEN 'PAYSLIP' = ANY (to_clear) THEN NULL ELSE payslip_picture END,
                        national_id_picture = CASE WHEN 'NATIONAL_ID' = ANY (to_clear) THEN NULL ELSE national_id_picture END,
                        signature           = CASE WHEN 'SIGNATURE' = ANY (to_clear) THEN NULL ELSE signature END,
                        witness_signature   = CASE WHEN 'WITNESS_SIGNATURE' = ANY (to_clear) THEN NULL ELSE witness_signature END
                    WHERE id = loan.id;
                    cleared := cleared + CARDINALITY(to_clear);
                    loans_cleared := loans_cleared + 1;
                END IF;
            END LOOP;

        SELECT count(*)
        INTO loans_left
        FROM loans
        WHERE payslip_picture IS NOT NULL
           OR national_id_picture IS NOT NULL
           OR signature IS NOT NULL
           OR witness_signature IS NOT NULL;

        RAISE NOTICE 'V35: cleared % legacy document value(s) on % loan(s); % loan row(s) left untouched with a legacy'
                         ' value (no copy: %, a different copy: %, a shared large object: %, unreadable or blank: %)',
            cleared, loans_cleared, loans_left, no_copy, different, shared, unreadable;
    END
$$;

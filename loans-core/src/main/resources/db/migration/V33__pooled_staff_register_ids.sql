-- Staff register rows and reconciliation variances take their ids from their
-- own BIGSERIAL sequences 50 at a time (Hibernate SEQUENCE + pooled-lo, see
-- StaffRegisterRow / StaffRegisterVariance and JpaSchemaConfig). Under IDENTITY
-- Hibernate cannot batch an INSERT, since it must read each generated id back
-- one row at a time; a register upload writes one row per line of its file and
-- a reconciliation one variance per finding, so those were thousands of
-- single-row round trips inside one transaction. With ids drawn in blocks they
-- go out as JDBC batches of hibernate.jdbc.batch_size.
--
-- No new sequence: each column's own serial sequence becomes the pooled one.
--   * INCREMENT BY 50 is the entities' allocationSize, which Hibernate's schema
--     validation checks.
--   * setval moves it to at least the highest id in the table (and never
--     backwards), so the next nextval, and every id of the block it opens
--     (pooled-lo: nextval v gives v .. v+49), is above every existing row.
--
-- Rollback: the columns KEEP their DEFAULT nextval(...) on these same
-- sequences. A build from before this (IDENTITY) inserts through that default,
-- so each of its rows takes one nextval for itself: a value no Hibernate block
-- was opened at, so it never lands inside a block the new build is using. The
-- gaps (50 per old-build row) are harmless. Flyway ignores this applied
-- migration under the older build (future migrations are ignored by default).
-- Nothing has to be undone to roll back or to roll forward again.
--
-- The tables are locked against writes for the moment it takes, so a row
-- inserted by a build still running during the deploy cannot slip between the
-- read of max(id) and the setval.

LOCK TABLE staff_register_rows IN EXCLUSIVE MODE;
ALTER SEQUENCE staff_register_rows_id_seq INCREMENT BY 50;
SELECT setval('staff_register_rows_id_seq',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM staff_register_rows),
                       (SELECT last_value FROM staff_register_rows_id_seq),
                       1),
              true);

LOCK TABLE staff_register_variances IN EXCLUSIVE MODE;
ALTER SEQUENCE staff_register_variances_id_seq INCREMENT BY 50;
SELECT setval('staff_register_variances_id_seq',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM staff_register_variances),
                       (SELECT last_value FROM staff_register_variances_id_seq),
                       1),
              true);

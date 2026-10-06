-- Trigram indexes for the substring searches, which are LIKE '%text%' and so
-- could never use a b-tree: every search read the whole table.
--
--   * the staff register (GET /lending/v1/staff-members?q=&department=,
--     StaffRegisterService.members): lower(employee_number), lower(full_name)
--     and lower(department) LIKE '%text%', and msisdn LIKE '%digits%' (not
--     lowered: it is digits only);
--   * the user search (AuthServiceImpl.search): Spring Data's
--     ContainingIgnoreCase, rendered upper(username) LIKE upper(?).
--
-- Each index is on EXACTLY the expression the query compares, because the
-- planner uses an expression index only for that expression: lower(full_name)
-- does not serve upper(full_name), nor full_name. Change a query's expression
-- and its index in the same change, in a new migration.
-- DashboardAndSearchPostgresIT captures the SQL Hibernate really sends and
-- fails if a LIKE operand is not one of these expressions, or an index stops
-- serving its expression. A search text shorter than three characters has no
-- trigram to look up, and the planner reads the table instead, as before.
--
-- pg_trgm is a trusted extension since Postgres 13: CREATE privilege on the
-- database is enough, no superuser. Plain CREATE INDEX (not CONCURRENTLY):
-- Flyway runs this in a transaction. Each build holds a SHARE lock on its
-- table (reads go on, writes wait) for as long as it takes to build, which at
-- the register's and the users table's size (thousands of rows) is well under
-- a second.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_staff_members_employee_number_trgm
    ON staff_members USING gin (lower(employee_number) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_staff_members_full_name_trgm
    ON staff_members USING gin (lower(full_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_staff_members_department_trgm
    ON staff_members USING gin (lower(department) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_staff_members_msisdn_trgm
    ON staff_members USING gin (msisdn gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_users_username_trgm
    ON users USING gin (upper(username) gin_trgm_ops);

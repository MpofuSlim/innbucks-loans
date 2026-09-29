-- ============================================================================
-- InnBucks Loans — objects of retired features (PostgreSQL)
-- ============================================================================
-- Only on a database that Hibernate built before the schema moved to Flyway
-- (V2 upgraded it). A database created by V1 has none of these.
--
-- They belong to features removed because the BRD does not ask for them: bulk
-- loan upload, an idempotency engine nothing called, and the sales-consultant
-- link between an agent and the users and loans under them. Nothing reads them.
-- Keep them while their rows are worth looking at; then run this by hand. It is
-- not a migration, deliberately: dropping data is the operator's decision.
-- ============================================================================

DROP TABLE IF EXISTS bulk_ingestion_runs;
DROP TABLE IF EXISTS idempotency_records;
ALTER TABLE loans DROP COLUMN IF EXISTS agent_id;
ALTER TABLE users DROP COLUMN IF EXISTS agent_id;

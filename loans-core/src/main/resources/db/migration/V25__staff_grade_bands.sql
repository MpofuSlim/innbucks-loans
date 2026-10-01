-- A grade is whatever the bank grades its staff by. The register and the grade-to-limit matrix were built for a
-- Paterson grade such as C4, at most 16 characters, but the bank's staff list and limit matrix grade by band: EXCO,
-- HOD, MANAGER, SUPERVISOR, ANALYST, OFFICER, CLERK/ASSISTANT/AGENT and DRIVER/OFFICE ORDERLY. The last two are 21
-- characters, so every column that holds a grade now takes 32 (StaffGrades.MAX_LENGTH). Widening a VARCHAR rewrites
-- nothing, and the values already stored are unchanged: a Paterson grade is still one.
ALTER TABLE staff_grade_limit_changes ALTER COLUMN grade TYPE VARCHAR(32);
ALTER TABLE staff_members ALTER COLUMN grade TYPE VARCHAR(32);
ALTER TABLE staff_offers ALTER COLUMN grade TYPE VARCHAR(32);
ALTER TABLE staff_limit_overrides ALTER COLUMN grade TYPE VARCHAR(32);
ALTER TABLE staff_loans ALTER COLUMN grade TYPE VARCHAR(32);

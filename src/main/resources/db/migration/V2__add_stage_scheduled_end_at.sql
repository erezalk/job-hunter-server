-- Optional end of an interview/application stage, same type and timezone
-- convention as scheduled_at (TIMESTAMPTZ, stored as an instant).
--
-- Additive and backward compatible: a nullable column with no default, so every
-- existing stage keeps all of its values and simply has NULL here. No existing
-- row is rewritten or updated.
ALTER TABLE application_stages ADD COLUMN scheduled_end_at TIMESTAMPTZ;

-- Safety net behind the API validation: an end needs a start and must be
-- strictly after it. Trivially satisfied by existing rows (all NULL), so adding
-- it cannot fail on existing data.
ALTER TABLE application_stages ADD CONSTRAINT ck_application_stages_end_after_start
    CHECK (scheduled_end_at IS NULL OR (scheduled_at IS NOT NULL AND scheduled_end_at > scheduled_at));

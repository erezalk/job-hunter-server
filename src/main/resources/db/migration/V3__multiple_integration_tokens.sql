-- Several independent integration tokens per user (e.g. one for Claude Desktop,
-- one for Codex), each with an optional name, revocable and regenerable on its
-- own.
--
-- Data preserving: no row is rewritten. Every existing token keeps its id,
-- owner, SHA-256 digest, suffix and created_at, so a client configured with it
-- keeps working after the upgrade. It simply has no name (NULL = "unnamed").
-- Users without a token are unaffected.

-- Was at most one token per user.
ALTER TABLE integration_tokens DROP CONSTRAINT uq_integration_tokens_user;

-- The unique constraint above was also the index behind the per-user lookups
-- and the ON DELETE CASCADE from users.
CREATE INDEX idx_integration_tokens_user ON integration_tokens (user_id);

-- Optional label chosen by the user. The application trims it and stores a
-- blank name as NULL; the check is the safety net for "never blank".
ALTER TABLE integration_tokens ADD COLUMN name VARCHAR(100);
ALTER TABLE integration_tokens ADD CONSTRAINT ck_integration_tokens_name_not_blank
    CHECK (name IS NULL OR btrim(name) <> '');

-- When the secret was last replaced (regenerated) while keeping the token's id
-- and name; NULL for a token that still has its original secret.
ALTER TABLE integration_tokens ADD COLUMN regenerated_at TIMESTAMPTZ;

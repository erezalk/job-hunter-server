-- Excluded Companies: companies the user wants AI job discovery (MCP clients
-- such as Claude or Codex) to skip. Strictly a discovery preference: the table
-- has no relation to jobs, companies or applications, so adding, renaming or
-- removing an entry never touches the Board, and jobs at an excluded company
-- can still be added.
--
-- Additive: a new table only; no existing row is read or rewritten.
CREATE TABLE excluded_companies (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_excluded_companies_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- The application trims the name (and collapses inner whitespace); the
    -- check is the safety net for "trimmed and never empty".
    CONSTRAINT ck_excluded_companies_name_trimmed CHECK (name = btrim(name) AND name <> '')
);

-- One entry per company per user, ignoring case ("Flix" and "FLIX" are the
-- same company). Also the index behind the per-user lookups and the
-- ON DELETE CASCADE from users.
CREATE UNIQUE INDEX uq_excluded_companies_user_name_ignore_case ON excluded_companies (user_id, lower(name));

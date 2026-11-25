-- Search Prompt: the user's free-text job-search instructions (Settings →
-- Search Prompt), read by MCP clients such as Claude or Codex through
-- get_search_prompt. At most one per user; "no prompt configured" is simply
-- no row (clearing the prompt deletes it).
--
-- The text is stored exactly as entered (plain text, never rewritten): TEXT,
-- so there is no column length limit (the application caps the length of a
-- request). The check only rules out an empty string, which the application
-- never stores.
--
-- Additive: a new table only; no existing row is read or rewritten.
CREATE TABLE search_prompts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    prompt     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_search_prompts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- One prompt per user; also the index behind the per-user lookup and the
    -- ON DELETE CASCADE from users.
    CONSTRAINT uq_search_prompts_user UNIQUE (user_id),
    CONSTRAINT ck_search_prompts_prompt_not_empty CHECK (prompt <> '')
);

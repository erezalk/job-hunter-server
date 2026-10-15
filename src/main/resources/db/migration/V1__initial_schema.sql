-- Job Hunter database schema.
--
-- Every row of user data belongs to exactly one user:
--
--   users ─┬─< cvs
--          ├── settings              (one per user)
--          ├── integration_tokens    (at most one per user)
--          ├─< companies             (name unique per user)
--          └─< jobs                  (company + title identity unique per user)
--                ├── applications    (the Board ticket: exactly one per job, same user)
--                │     ├─< application_status_history
--                │     └─< application_stages
--                └── job_matches     (at most one per job, same user; optional CV of the same user)
--
-- Composite foreign keys (child.(parent_id, user_id) -> parent.(id, user_id))
-- make it impossible at the database level to attach a row to another user's
-- job, company or CV, whatever the application code does. Deleting a user
-- cascades through exactly that user's rows.
--
-- application_statuses is the only shared table: read-only reference data.

-- --- Accounts ----------------------------------------------------------------

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- Every login/token lookup resolves the user by email ignoring case
-- (upper(email) = upper(?)), so two accounts may never differ only in case.
CREATE UNIQUE INDEX uq_users_email_ignore_case ON users (upper(email));

-- --- Reference data ----------------------------------------------------------

-- Allowed application statuses. Adding one is a row insert, not a schema change.
CREATE TABLE application_statuses (
    code        VARCHAR(50)  PRIMARY KEY,
    label       VARCHAR(100) NOT NULL,
    sort_order  INTEGER      NOT NULL,
    is_terminal BOOLEAN      NOT NULL DEFAULT false
);

-- LOW_MATCH: a job that was successfully AI-evaluated below the user's minimum
-- fit score; kept as an "already seen and evaluated" record that still takes
-- part in job deduplication. Its Board column is opt-in (see
-- settings.visible_board_statuses).
INSERT INTO application_statuses (code, label, sort_order, is_terminal) VALUES
    ('NEW', 'New', 10, false),
    ('CV_SENT', 'CV Sent', 20, false),
    ('INTERVIEW_PROCESS', 'Interview Process', 30, false),
    ('OFFER', 'Offer', 40, false),
    ('REJECTED', 'Rejected', 50, true),
    ('ARCHIVED', 'Archived', 60, true),
    ('LOW_MATCH', 'Low Match', 70, false);

-- --- Per-user profile --------------------------------------------------------

-- Uploaded CVs. Each upload is a new row, so earlier CVs are retained; at most
-- one is primary ("active") per user. The file itself lives in CV storage
-- (storage_path); extracted_text is what AI matching reads.
CREATE TABLE cvs (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            BIGINT        NOT NULL,
    title              VARCHAR(255)  NOT NULL,
    file_name          VARCHAR(255),
    content_type       VARCHAR(100),
    file_size_bytes    BIGINT,
    storage_path       VARCHAR(1000),
    extraction_status  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    extraction_warning TEXT,
    extracted_text     TEXT,
    is_primary         BOOLEAN       NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_cvs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- Target of job_matches.(cv_id, user_id): a match can only reference its owner's CV.
    CONSTRAINT uq_cvs_id_user UNIQUE (id, user_id),
    CONSTRAINT chk_cvs_extraction_status CHECK (extraction_status IN ('SUCCESS', 'EMPTY', 'FAILED', 'PENDING'))
);

CREATE INDEX idx_cvs_user_id ON cvs (user_id);
CREATE UNIQUE INDEX uq_cvs_user_primary ON cvs (user_id) WHERE is_primary;

-- One row per user. visible_board_statuses is the comma-separated list of
-- Board columns shown (a UI preference over the fixed status codes; LOW_MATCH
-- is opt-in, hence absent from the default). Provider API keys are stored
-- AES-GCM encrypted with a key derived from APP_ENCRYPTION_SECRET, which is
-- never stored in the database; one column per provider so switching
-- provider keeps the other key.
CREATE TABLE settings (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id                     BIGINT       NOT NULL,
    theme                       VARCHAR(50)  NOT NULL DEFAULT 'SYSTEM',
    timezone                    VARCHAR(100) NOT NULL DEFAULT 'UTC',
    visible_board_statuses      VARCHAR(255) NOT NULL DEFAULT 'NEW,CV_SENT,INTERVIEW_PROCESS,OFFER,REJECTED,ARCHIVED',
    minimum_fit_score           INTEGER      NOT NULL DEFAULT 70,
    ai_provider                 VARCHAR(20),
    ai_model                    VARCHAR(100),
    anthropic_api_key_encrypted TEXT,
    gemini_api_key_encrypted    TEXT,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_settings_user_id UNIQUE (user_id),
    CONSTRAINT chk_settings_minimum_fit_score CHECK (minimum_fit_score BETWEEN 0 AND 100),
    CONSTRAINT chk_settings_ai_provider CHECK (ai_provider IS NULL OR ai_provider IN ('ANTHROPIC', 'GEMINI'))
);

-- Personal MCP integration token (at most one per user). Only the SHA-256
-- digest is stored; the raw token is shown once at generation time and can
-- never be recovered. Tokens do not expire: they stay valid until the user
-- revokes (row deleted) or replaces them.
CREATE TABLE integration_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    token_hash   VARCHAR(64) NOT NULL,
    token_suffix VARCHAR(8)  NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_integration_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_integration_tokens_user UNIQUE (user_id),
    CONSTRAINT uq_integration_tokens_hash UNIQUE (token_hash)
);

-- --- Job domain --------------------------------------------------------------

-- A user's company: the name shown on their jobs plus optional website/notes.
-- name_key is the normalized name (whitespace collapsed, trimmed, lowercased;
-- see JobService.normalizeKeyPart), the same normalization as jobs.company_key.
CREATE TABLE companies (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    name       VARCHAR(255) NOT NULL,
    name_key   VARCHAR(600) NOT NULL,
    website    VARCHAR(500),
    notes      TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_companies_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_companies_user_name_key UNIQUE (user_id, name_key),
    CONSTRAINT uq_companies_id_user UNIQUE (id, user_id)
);

-- A posting one user tracks. It is not an application by itself; its Board
-- ticket is the applications row. Deduplication is per user: normalized
-- company + normalized title (company_key/title_key; company_key is '' for a
-- job without company), and source + external_id. source is an optional,
-- user-editable free-text label. description_discarded is set when the
-- description was deliberately dropped after a low AI match, to tell
-- "discarded" apart from "never provided".
CREATE TABLE jobs (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id               BIGINT        NOT NULL,
    company_id            BIGINT,
    title                 VARCHAR(255)  NOT NULL,
    description           TEXT,
    description_discarded BOOLEAN       NOT NULL DEFAULT false,
    location              VARCHAR(255),
    work_model            VARCHAR(100),
    url                   VARCHAR(1000),
    normalized_url        VARCHAR(1000),
    company_key           VARCHAR(600)  NOT NULL,
    title_key             VARCHAR(600)  NOT NULL,
    salary_min            INTEGER,
    salary_max            INTEGER,
    currency              VARCHAR(10),
    source                VARCHAR(100),
    external_id           VARCHAR(255),
    discovered_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_jobs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- The company must be the same user's. No cascade: a company in use cannot
    -- be deleted (MATCH SIMPLE: a job without company skips the check).
    CONSTRAINT fk_jobs_company FOREIGN KEY (company_id, user_id) REFERENCES companies (id, user_id),
    CONSTRAINT uq_jobs_user_company_title UNIQUE (user_id, company_key, title_key),
    CONSTRAINT uq_jobs_id_user UNIQUE (id, user_id),
    CONSTRAINT chk_jobs_salary_non_negative CHECK ((salary_min IS NULL OR salary_min >= 0) AND (salary_max IS NULL OR salary_max >= 0)),
    CONSTRAINT chk_jobs_salary_range CHECK (salary_min IS NULL OR salary_max IS NULL OR salary_min <= salary_max)
);

CREATE INDEX idx_jobs_company_id ON jobs (company_id);
CREATE UNIQUE INDEX uq_jobs_user_source_external_id ON jobs (user_id, source, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX idx_jobs_user_normalized_url ON jobs (user_id, normalized_url);

-- The Board ticket: exactly one per job, owned by the job's user. starred and
-- board_position (0-based manual order inside the status column) organize the
-- user's Board.
CREATE TABLE applications (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id         BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    status_code    VARCHAR(50) NOT NULL,
    applied_at     TIMESTAMPTZ,
    notes          TEXT,
    starred        BOOLEAN     NOT NULL DEFAULT false,
    board_position INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_applications_job FOREIGN KEY (job_id, user_id) REFERENCES jobs (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_applications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_applications_status FOREIGN KEY (status_code) REFERENCES application_statuses (code),
    CONSTRAINT uq_applications_job UNIQUE (job_id)
);

CREATE INDEX idx_applications_user_status_position ON applications (user_id, status_code, board_position);

-- Status-change audit trail of one application (owned through it), independent
-- of the application's current status_code.
CREATE TABLE application_status_history (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT      NOT NULL,
    status_code    VARCHAR(50) NOT NULL,
    entered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes          TEXT,
    CONSTRAINT fk_application_status_history_application FOREIGN KEY (application_id) REFERENCES applications (id) ON DELETE CASCADE,
    CONSTRAINT fk_application_status_history_status FOREIGN KEY (status_code) REFERENCES application_statuses (code)
);

CREATE INDEX idx_application_status_history_application_id ON application_status_history (application_id);

-- Manually entered recruitment/interview events of one application (owned through it).
CREATE TABLE application_stages (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id    BIGINT       NOT NULL,
    name              VARCHAR(255) NOT NULL,
    scheduled_at      TIMESTAMPTZ,
    interviewer_names VARCHAR(500),
    notes             TEXT,
    outcome           VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_application_stages_application FOREIGN KEY (application_id) REFERENCES applications (id) ON DELETE CASCADE
);

CREATE INDEX idx_application_stages_application_id ON application_stages (application_id);
CREATE INDEX idx_application_stages_scheduled_at ON application_stages (scheduled_at) WHERE scheduled_at IS NOT NULL;

-- The owner's AI evaluation of the job: at most one per job; re-evaluating
-- overwrites it. The skill/concern columns hold JSON string arrays.
-- fingerprint (SHA-256 of CV, job content, provider, model and prompt version)
-- lets an evaluation with unchanged inputs be reused. The CV it was computed from must be the same
-- user's; deleting that CV only clears the link.
CREATE TABLE job_matches (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id          BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    cv_id           BIGINT,
    match_score     NUMERIC(5, 2),
    provider        VARCHAR(30),
    model           VARCHAR(100),
    matching_skills TEXT,
    missing_skills  TEXT,
    concerns        TEXT,
    fingerprint     VARCHAR(64),
    evaluated_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_job_matches_job FOREIGN KEY (job_id, user_id) REFERENCES jobs (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_job_matches_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_matches_cv FOREIGN KEY (cv_id, user_id) REFERENCES cvs (id, user_id) ON DELETE SET NULL (cv_id),
    CONSTRAINT uq_job_matches_job UNIQUE (job_id),
    CONSTRAINT chk_job_matches_score CHECK (match_score IS NULL OR match_score BETWEEN 0 AND 100)
);

CREATE INDEX idx_job_matches_user_id ON job_matches (user_id);

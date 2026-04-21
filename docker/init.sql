-- ============================================================
-- VoiceNova AI — Database Schema
-- docker/init.sql — runs automatically on first start
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";  -- for fast text search

-- ── Call Sessions ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS call_sessions (
    id               TEXT PRIMARY KEY,
    from_number      TEXT NOT NULL,
    to_number        TEXT NOT NULL,
    direction        TEXT NOT NULL,          -- INBOUND | OUTBOUND
    status           TEXT NOT NULL DEFAULT 'ACTIVE',
    detected_language TEXT NOT NULL DEFAULT 'hi-IN',
    agent_name       TEXT,
    company_name     TEXT,
    caller_name      TEXT,
    caller_plan      TEXT,
    quality_score    FLOAT,
    started_at       BIGINT NOT NULL,
    ended_at         BIGINT,
    intent_summary   TEXT,
    resolved         BOOLEAN DEFAULT FALSE,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_status ON call_sessions(status);
CREATE INDEX IF NOT EXISTS idx_sessions_started ON call_sessions(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_language ON call_sessions(detected_language);

-- ── Messages / Transcript ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS messages (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id   TEXT NOT NULL REFERENCES call_sessions(id) ON DELETE CASCADE,
    role         TEXT NOT NULL,              -- user | assistant
    content      TEXT NOT NULL,
    language     TEXT NOT NULL,
    confidence   FLOAT DEFAULT 1.0,
    timestamp_ms BIGINT NOT NULL,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);
CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(timestamp_ms);

-- ── Knowledge Chunks ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id          TEXT PRIMARY KEY,
    source      TEXT NOT NULL,
    category    TEXT NOT NULL,
    content     TEXT NOT NULL,
    languages   TEXT[] DEFAULT ARRAY['all'],
    usage_count INT DEFAULT 0,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunks_category ON knowledge_chunks(category);
CREATE INDEX IF NOT EXISTS idx_chunks_content_fts ON knowledge_chunks USING GIN(to_tsvector('english', content));

-- ── Learned Facts (self-learning) ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS learned_facts (
    id          TEXT PRIMARY KEY,
    question    TEXT NOT NULL,
    answer      TEXT NOT NULL,
    language    TEXT NOT NULL,
    call_id     TEXT REFERENCES call_sessions(id),
    approved    BOOLEAN DEFAULT FALSE,
    usage_count INT DEFAULT 0,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_facts_approved ON learned_facts(approved);
CREATE INDEX IF NOT EXISTS idx_facts_language ON learned_facts(language);

-- ── Analytics (pre-aggregated for dashboard) ─────────────────────────────────
CREATE TABLE IF NOT EXISTS daily_stats (
    date         DATE NOT NULL,
    language     TEXT NOT NULL,
    total_calls  INT DEFAULT 0,
    resolved     INT DEFAULT 0,
    transferred  INT DEFAULT 0,
    avg_duration_sec FLOAT DEFAULT 0,
    avg_score    FLOAT,
    PRIMARY KEY (date, language)
);

-- ── Agent Configurations ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_configs (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name                TEXT NOT NULL,
    company_name        TEXT NOT NULL,
    system_instructions TEXT,
    personality         TEXT[],
    call_rules          TEXT[],
    supported_languages TEXT[],
    primary_language    TEXT DEFAULT 'hi-IN',
    escalation_keywords TEXT[],
    greetings           JSONB DEFAULT '{}',
    is_active           BOOLEAN DEFAULT TRUE,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);

-- Insert default agent config
INSERT INTO agent_configs (name, company_name, system_instructions, personality, supported_languages)
VALUES (
    'Nova',
    'My Company',
    'Be helpful, concise, and professional. Always respond in the caller''s language.',
    ARRAY['professional', 'warm', 'patient'],
    ARRAY['hi-IN', 'en-IN', 'ta-IN', 'te-IN', 'bn-IN', 'gu-IN', 'mr-IN', 'kn-IN', 'ml-IN', 'pa-IN']
) ON CONFLICT DO NOTHING;

-- ── Users (for dashboard auth) ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email        TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role         TEXT DEFAULT 'admin',
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- Default admin user (password: admin123 — change immediately!)
INSERT INTO users (email, password_hash, role)
VALUES ('admin@voicenova.ai', '$2a$10$placeholder_change_this_hash', 'admin')
ON CONFLICT DO NOTHING;

-- ── Useful views for dashboard ────────────────────────────────────────────────
CREATE OR REPLACE VIEW v_recent_calls AS
SELECT
    cs.id,
    cs.from_number,
    cs.detected_language,
    cs.status,
    cs.agent_name,
    cs.quality_score,
    cs.resolved,
    ROUND((COALESCE(cs.ended_at, EXTRACT(EPOCH FROM NOW()) * 1000) - cs.started_at) / 1000.0) AS duration_sec,
    cs.started_at,
    COUNT(m.id) AS message_count,
    MAX(CASE WHEN m.role = 'user' THEN m.content END) AS last_user_message
FROM call_sessions cs
LEFT JOIN messages m ON m.session_id = cs.id
GROUP BY cs.id
ORDER BY cs.started_at DESC;

CREATE OR REPLACE VIEW v_language_stats AS
SELECT
    detected_language,
    COUNT(*) AS total_calls,
    AVG(quality_score) AS avg_score,
    SUM(CASE WHEN resolved THEN 1 ELSE 0 END)::float / COUNT(*) * 100 AS resolution_rate
FROM call_sessions
WHERE started_at > EXTRACT(EPOCH FROM NOW() - INTERVAL '30 days') * 1000
GROUP BY detected_language
ORDER BY total_calls DESC;

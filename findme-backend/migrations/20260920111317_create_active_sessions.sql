CREATE TABLE IF NOT EXISTS active_sessions (
    user_id        UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    last_heartbeat TIMESTAMPTZ NOT NULL DEFAULT now()
);
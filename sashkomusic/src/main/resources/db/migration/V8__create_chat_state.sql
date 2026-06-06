CREATE TABLE IF NOT EXISTS chat_state (
    chat_id    BIGINT      NOT NULL,
    flow_key   TEXT        NOT NULL,
    payload    JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, flow_key)
);

CREATE INDEX IF NOT EXISTS idx_chat_state_updated_at ON chat_state (updated_at);

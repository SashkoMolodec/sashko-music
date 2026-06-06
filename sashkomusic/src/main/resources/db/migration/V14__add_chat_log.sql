CREATE TABLE chat_log (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    role            VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    source          VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_log_conv_time ON chat_log(conversation_id, created_at DESC);

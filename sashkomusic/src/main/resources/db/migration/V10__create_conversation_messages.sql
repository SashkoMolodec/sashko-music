CREATE TABLE IF NOT EXISTS conversation_messages (
    conversation_id TEXT        NOT NULL,
    messages        JSONB       NOT NULL DEFAULT '[]',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id)
);

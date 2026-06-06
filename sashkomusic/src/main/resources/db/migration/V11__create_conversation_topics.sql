CREATE TABLE conversation_topics (
    chat_id     BIGINT      NOT NULL,
    topic_id    INTEGER     NOT NULL,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, topic_id)
);

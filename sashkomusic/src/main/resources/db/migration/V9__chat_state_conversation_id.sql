ALTER TABLE chat_state ADD COLUMN IF NOT EXISTS conversation_id TEXT;
UPDATE chat_state SET conversation_id = chat_id::TEXT WHERE conversation_id IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'chat_state' AND constraint_name = 'chat_state_pkey'
          AND constraint_type = 'PRIMARY KEY'
          AND (
            SELECT string_agg(column_name, ',' ORDER BY ordinal_position)
            FROM information_schema.key_column_usage
            WHERE constraint_name = 'chat_state_pkey' AND table_name = 'chat_state'
          ) = 'chat_id,flow_key'
    ) THEN
        ALTER TABLE chat_state ALTER COLUMN conversation_id SET NOT NULL;
        ALTER TABLE chat_state DROP CONSTRAINT chat_state_pkey;
        ALTER TABLE chat_state ADD PRIMARY KEY (conversation_id, flow_key);
        ALTER TABLE chat_state DROP COLUMN IF EXISTS chat_id;
    END IF;
END$$;

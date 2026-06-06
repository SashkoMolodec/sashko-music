DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'chat_state' AND column_name = 'chat_id'
    ) THEN
        ALTER TABLE chat_state ADD COLUMN IF NOT EXISTS conversation_id TEXT;
        UPDATE chat_state SET conversation_id = chat_id::TEXT WHERE conversation_id IS NULL;
        ALTER TABLE chat_state ALTER COLUMN conversation_id SET NOT NULL;
        ALTER TABLE chat_state DROP CONSTRAINT chat_state_pkey;
        ALTER TABLE chat_state ADD PRIMARY KEY (conversation_id, flow_key);
        ALTER TABLE chat_state DROP COLUMN chat_id;
    END IF;
END$$;

ALTER TABLE chat_state ADD COLUMN conversation_id TEXT;
UPDATE chat_state SET conversation_id = chat_id::TEXT;
ALTER TABLE chat_state ALTER COLUMN conversation_id SET NOT NULL;
ALTER TABLE chat_state DROP CONSTRAINT chat_state_pkey;
ALTER TABLE chat_state ADD PRIMARY KEY (conversation_id, flow_key);
ALTER TABLE chat_state DROP COLUMN chat_id;

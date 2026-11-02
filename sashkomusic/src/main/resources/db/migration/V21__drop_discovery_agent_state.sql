-- DiscoveryAgent was folded into MainAgent: nothing reads or writes the ":d" keys any more.
-- Conversation ids are "<chatId>" or "<chatId>:<topicId>", both numeric, so ":d" can only be that agent's.
DELETE FROM conversation_messages WHERE conversation_id LIKE '%:d';
DELETE FROM chat_state WHERE conversation_id LIKE '%:d';

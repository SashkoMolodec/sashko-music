package com.sashkomusic.agents.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class PostgresChatMemoryStore implements ChatMemoryStore {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String convId = memoryId.toString();
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT messages FROM conversation_messages WHERE conversation_id = ?",
                    String.class, convId);
            if (json == null || json.isBlank()) return new ArrayList<>();
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (EmptyResultDataAccessException e) {
            return new ArrayList<>();
        } catch (Exception e) {
            log.error("Failed to load messages for conversation={}: {}", convId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String convId = memoryId.toString();
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            jdbcTemplate.update("""
                    INSERT INTO conversation_messages (conversation_id, messages, updated_at)
                    VALUES (?, ?::jsonb, now())
                    ON CONFLICT (conversation_id) DO UPDATE
                      SET messages = EXCLUDED.messages, updated_at = now()
                    """, convId, json);
        } catch (Exception e) {
            log.error("Failed to update messages for conversation={}: {}", convId, e.getMessage());
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String convId = memoryId.toString();
        jdbcTemplate.update("DELETE FROM conversation_messages WHERE conversation_id = ?", convId);
    }
}

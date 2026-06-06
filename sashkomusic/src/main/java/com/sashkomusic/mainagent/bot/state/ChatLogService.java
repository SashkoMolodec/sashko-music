package com.sashkomusic.mainagent.bot.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLogService {

    private final JdbcTemplate jdbcTemplate;

    public void log(String conversationId, String role, String content, String source) {
        if (content == null || content.isBlank()) return;
        try {
            jdbcTemplate.update(
                    "INSERT INTO chat_log (conversation_id, role, content, source) VALUES (?, ?, ?, ?)",
                    conversationId, role, content, source);
        } catch (Exception e) {
            log.warn("Failed to write chat_log entry for {}: {}", conversationId, e.getMessage());
        }
    }

    public List<ChatLogEntry> getLastN(String conversationId, int n) {
        try {
            return jdbcTemplate.query("""
                    SELECT role, content, source, created_at
                    FROM (
                        SELECT role, content, source, created_at
                        FROM chat_log
                        WHERE conversation_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                    ) sub
                    ORDER BY created_at ASC
                    """,
                    (rs, rowNum) -> new ChatLogEntry(
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("source"),
                            rs.getTimestamp("created_at").toInstant()),
                    conversationId, n);
        } catch (Exception e) {
            log.warn("Failed to read chat_log for {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    public void deleteConversation(String conversationId) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM chat_log WHERE conversation_id = ?", conversationId);
            log.info("Cleared {} chat_log entries for {}", deleted, conversationId);
        } catch (Exception e) {
            log.warn("Failed to delete chat_log for {}: {}", conversationId, e.getMessage());
        }
    }

    public record ChatLogEntry(String role, String content, String source, Instant createdAt) {}
}

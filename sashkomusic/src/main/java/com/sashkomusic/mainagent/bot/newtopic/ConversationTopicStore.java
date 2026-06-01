package com.sashkomusic.mainagent.bot.newtopic;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConversationTopicStore {

    private final JdbcTemplate jdbcTemplate;

    public void save(long chatId, int topicId, String name) {
        jdbcTemplate.update("""
                INSERT INTO conversation_topics(chat_id, topic_id, name) VALUES(?, ?, ?)
                ON CONFLICT DO NOTHING
                """, chatId, topicId, name);
    }
}

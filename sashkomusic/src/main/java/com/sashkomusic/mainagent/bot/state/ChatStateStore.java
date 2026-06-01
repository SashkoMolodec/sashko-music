package com.sashkomusic.mainagent.bot.state;

import java.util.Optional;

/**
 * Per-conversation state store. Keyed by (conversationId, flowKey).
 * conversationId = chatId for DMs, "chatId:topicId" for group topics.
 * Persisted to Postgres; survives JVM restart.
 */
public interface ChatStateStore {

    <T> Optional<T> get(String conversationId, String flowKey, Class<T> type);

    void put(String conversationId, String flowKey, Object payload);

    void remove(String conversationId, String flowKey);

    int clearAll(String flowKey);
}

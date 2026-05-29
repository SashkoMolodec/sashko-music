package com.sashkomusic.mainagent.bot.state;

import java.util.Optional;

/**
 * Per-chat conversation state. Each entry is keyed by (chatId, flowKey)
 * and stores a JSON-serialisable payload.
 * <p>
 * Use one stable {@code flowKey} per ContextHolder (e.g. "dj_tag", "download", "search").
 * Default Spring impl ({@link JpaChatStateStore}) persists to Postgres so state survives restart.
 */
public interface ChatStateStore {

    <T> Optional<T> get(long chatId, String flowKey, Class<T> type);

    void put(long chatId, String flowKey, Object payload);

    void remove(long chatId, String flowKey);

    int clearAll(String flowKey);
}

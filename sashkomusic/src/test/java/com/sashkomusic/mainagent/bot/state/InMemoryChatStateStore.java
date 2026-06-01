package com.sashkomusic.mainagent.bot.state;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double — no Postgres, no transactions. JSON serialisation matches the
 * real {@link JpaChatStateStore} to keep round-trip semantics realistic.
 */
public class InMemoryChatStateStore implements ChatStateStore {

    private final Map<Key, String> store = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public InMemoryChatStateStore() {
        this(new ObjectMapper());
    }

    public InMemoryChatStateStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public <T> Optional<T> get(String conversationId, String flowKey, Class<T> type) {
        String json = store.get(new Key(conversationId, flowKey));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, type));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void put(String conversationId, String flowKey, Object payload) {
        try {
            store.put(new Key(conversationId, flowKey), mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void remove(String conversationId, String flowKey) {
        store.remove(new Key(conversationId, flowKey));
    }

    @Override
    public int clearAll(String flowKey) {
        int[] removed = {0};
        store.keySet().removeIf(k -> {
            if (k.flowKey().equals(flowKey)) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }

    public void clearEverything() {
        store.clear();
    }

    private record Key(String conversationId, String flowKey) {}
}

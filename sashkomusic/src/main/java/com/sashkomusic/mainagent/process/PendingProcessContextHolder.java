package com.sashkomusic.mainagent.process;

import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PendingProcessContextHolder {

    private static final String FLOW_KEY = "pending_process";

    private final ChatStateStore store;

    public void set(String conversationId, String folderPath) {
        store.put(conversationId, FLOW_KEY, new PendingProcessContext(folderPath));
        log.debug("Stored pending process folder for {}: {}", conversationId, folderPath);
    }

    public Optional<PendingProcessContext> get(String conversationId) {
        return store.get(conversationId, FLOW_KEY, PendingProcessContext.class);
    }

    public void clear(String conversationId) {
        store.remove(conversationId, FLOW_KEY);
    }

    public void clearAll() {
        int removed = store.clearAll(FLOW_KEY);
        log.info("Cleared {} pending process contexts", removed);
    }

    public record PendingProcessContext(String folderPath) {}
}

package com.sashkomusic.mainagent.library;

import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class LastReleaseContextHolder {

    private static final String FLOW_KEY = "last_release";

    private final ChatStateStore store;

    public void set(String conversationId, Long releaseId, String title, String artist) {
        store.put(conversationId, FLOW_KEY, new LastReleaseContext(releaseId, title, artist));
        log.debug("Set last release for conversation {}: id={}, '{}'", conversationId, releaseId, title);
    }

    public Optional<LastReleaseContext> get(String conversationId) {
        return store.get(conversationId, FLOW_KEY, LastReleaseContext.class);
    }

    public void clear(String conversationId) {
        store.remove(conversationId, FLOW_KEY);
    }

    public void clearAll() {
        int removed = store.clearAll(FLOW_KEY);
        log.info("Cleared {} last-release contexts", removed);
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        clear(event.conversationId());
    }

    public record LastReleaseContext(Long releaseId, String title, String artist) {}
}

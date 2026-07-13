package com.sashkomusic.mainagent.library;

import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SmartlistLabelContextHolder {

    private static final String FLOW_KEY = "sml_label";

    private final ChatStateStore store;

    public void set(String conversationId, LabelContext ctx) {
        store.put(conversationId, FLOW_KEY, ctx);
    }

    public Optional<LabelContext> get(String conversationId) {
        return store.get(conversationId, FLOW_KEY, LabelContext.class);
    }

    public void clear(String conversationId) {
        store.remove(conversationId, FLOW_KEY);
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        clear(event.conversationId());
    }

    public record LabelContext(String mode, Long targetId, List<Long> markerIds, int page) {
        public static final String MODE_TRACK = "T";
        public static final String MODE_ALBUM = "A";
    }
}

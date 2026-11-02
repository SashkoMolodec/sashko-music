package com.sashkomusic.mainagent.library;

import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** The 🏷 picker of one chat: adding a marker ({@code sml_label}) or taking one off ({@code sml_label_rm}) — never both. */
@Service
@RequiredArgsConstructor
public class SmartlistLabelContextHolder {

    private static final String FLOW_KEY = "sml_label";
    private static final String REMOVAL_FLOW_KEY = "sml_label_rm";

    private final ChatStateStore store;

    public void set(String conversationId, LabelContext ctx) {
        store.remove(conversationId, REMOVAL_FLOW_KEY);
        store.put(conversationId, FLOW_KEY, ctx);
    }

    public Optional<LabelContext> get(String conversationId) {
        return store.get(conversationId, FLOW_KEY, LabelContext.class);
    }

    public void setRemoval(String conversationId, RemovalContext ctx) {
        store.remove(conversationId, FLOW_KEY);
        store.put(conversationId, REMOVAL_FLOW_KEY, ctx);
    }

    public Optional<RemovalContext> getRemoval(String conversationId) {
        return store.get(conversationId, REMOVAL_FLOW_KEY, RemovalContext.class);
    }

    public void clear(String conversationId) {
        store.remove(conversationId, FLOW_KEY);
        store.remove(conversationId, REMOVAL_FLOW_KEY);
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        clear(event.conversationId());
    }

    public record LabelContext(String mode, Long targetId, List<Long> markerIds, int page) {
        public static final String MODE_TRACK = "T";
        public static final String MODE_ALBUM = "A";
    }

    /** The markers that were on the target when the list was shown; a button's number indexes into it. */
    public record RemovalContext(String mode, Long targetId, List<String> markerNames) {}
}

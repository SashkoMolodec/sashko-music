package com.sashkomusic.mainagent.library;

import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class DjTagContextHolder {

    private static final String FLOW_KEY = "dj_tag";

    private final ChatStateStore store;

    public void setTrackContext(String conversationId, TrackDto track, String navidromeId, boolean waitingForComment) {
        store.put(conversationId, FLOW_KEY, new DjTagContext(track, navidromeId, waitingForComment));
        log.debug("Set track context for conversation {}, track {}, waitingForComment={}",
                conversationId, track.id(), waitingForComment);
    }

    public void activateCommentMode(String conversationId) {
        DjTagContext existing = getContext(conversationId);
        if (existing != null) {
            store.put(conversationId, FLOW_KEY,
                    new DjTagContext(existing.track, existing.navidromeId, true));
            log.debug("Activated comment mode for conversation {}, track {}", conversationId, existing.track.id());
        } else {
            log.warn("Cannot activate comment mode for conversation {} - no track context", conversationId);
        }
    }

    public boolean isWaitingForComment(String conversationId) {
        DjTagContext ctx = getContext(conversationId);
        return ctx != null && ctx.waitingForComment;
    }

    public DjTagContext getContext(String conversationId) {
        return store.get(conversationId, FLOW_KEY, DjTagContext.class).orElse(null);
    }

    public void clearContext(String conversationId) {
        store.remove(conversationId, FLOW_KEY);
        log.debug("Cleared context for conversation {}", conversationId);
    }

    public void clearAllContexts() {
        int removed = store.clearAll(FLOW_KEY);
        log.info("Cleared {} DJ tag contexts", removed);
    }

    public record DjTagContext(TrackDto track, String navidromeId, boolean waitingForComment) {
        public Long trackId() {
            return track.id();
        }
    }
}

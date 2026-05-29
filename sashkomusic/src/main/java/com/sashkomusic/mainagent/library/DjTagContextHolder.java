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

    public void setTrackContext(long chatId, TrackDto track, String navidromeId, boolean waitingForComment) {
        store.put(chatId, FLOW_KEY, new DjTagContext(track, navidromeId, waitingForComment));
        log.debug("Set track context for chat {}, track {}, waitingForComment={}",
                chatId, track.id(), waitingForComment);
    }

    public void activateCommentMode(long chatId) {
        DjTagContext existing = getContext(chatId);
        if (existing != null) {
            store.put(chatId, FLOW_KEY,
                    new DjTagContext(existing.track, existing.navidromeId, true));
            log.debug("Activated comment mode for chat {}, track {}", chatId, existing.track.id());
        } else {
            log.warn("Cannot activate comment mode for chat {} - no track context", chatId);
        }
    }

    public boolean isWaitingForComment(long chatId) {
        DjTagContext ctx = getContext(chatId);
        return ctx != null && ctx.waitingForComment;
    }

    public DjTagContext getContext(long chatId) {
        return store.get(chatId, FLOW_KEY, DjTagContext.class).orElse(null);
    }

    public void clearContext(long chatId) {
        store.remove(chatId, FLOW_KEY);
        log.debug("Cleared context for chat {}", chatId);
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

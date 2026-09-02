package com.sashkomusic.mainagent.library;

import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TrackRemovalContextHolder {

    private static final String FLOW_KEY = "track_removal_select";

    private final ChatStateStore stateStore;

    public void markSelecting(String conversationId, Long releaseId) {
        stateStore.put(conversationId, FLOW_KEY, new PendingSelection(releaseId));
    }

    public Optional<PendingSelection> get(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, PendingSelection.class);
    }

    public void clear(String conversationId) {
        stateStore.remove(conversationId, FLOW_KEY);
    }

    public record PendingSelection(Long releaseId) {}
}

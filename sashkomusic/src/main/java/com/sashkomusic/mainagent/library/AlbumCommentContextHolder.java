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
public class AlbumCommentContextHolder {

    private static final String FLOW_KEY = "np_album_comment";

    private final ChatStateStore store;

    public void set(String conversationId, Long releaseId) {
        store.put(conversationId, FLOW_KEY, releaseId);
    }

    public Optional<Long> get(String conversationId) {
        return store.get(conversationId, FLOW_KEY, Long.class);
    }

    public void clear(String conversationId) {
        store.remove(conversationId, FLOW_KEY);
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        clear(event.conversationId());
    }
}

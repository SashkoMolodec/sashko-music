package com.sashkomusic.mainagent.download;

import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds at most one pending Soulseek directory confirmation per conversation. Each entry carries a
 * random {@code token} that is echoed back in the SLSK_DIR_OK/SEL/NO callback data — a click is only
 * honored if its token still matches what's stored here, so a second preview overwriting the slot
 * (e.g. another concurrent lookup funneled into the same download topic) invalidates the first
 * card's buttons instead of silently downloading whatever is currently pending.
 */
@Service
@RequiredArgsConstructor
public class SoulseekDirectoryConfirmContextHolder {

    private static final String FLOW_KEY = "slsk_dir_confirm";

    private final ChatStateStore stateStore;

    /**
     * Stores the pending confirmation under a freshly generated token and returns it, so the caller
     * can encode it into the SLSK_DIR_* callback data.
     */
    public String save(String conversationId, String releaseId, DownloadOption expandedOption) {
        String token = UUID.randomUUID().toString();
        stateStore.put(conversationId, FLOW_KEY, new PendingConfirm(token, releaseId, expandedOption, false));
        return token;
    }

    public void markSelecting(String conversationId) {
        get(conversationId).ifPresent(pending ->
                stateStore.put(conversationId, FLOW_KEY,
                        new PendingConfirm(pending.token(), pending.releaseId(), pending.expandedOption(), true)));
    }

    public Optional<PendingConfirm> get(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, PendingConfirm.class);
    }

    /**
     * Returns the pending confirmation only if it is still there AND its token matches — otherwise
     * empty, meaning the clicked button is stale (superseded by a later preview or already resolved).
     */
    public Optional<PendingConfirm> getIfMatches(String conversationId, String token) {
        return get(conversationId).filter(pending -> pending.token().equals(token));
    }

    public void clear(String conversationId) {
        stateStore.remove(conversationId, FLOW_KEY);
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        clear(event.conversationId());
    }

    public record PendingConfirm(String token, String releaseId, DownloadOption expandedOption, boolean selecting) {}
}

package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SoulseekDirectoryConfirmContextHolder {

    private static final String FLOW_KEY = "slsk_dir_confirm";

    private final ChatStateStore stateStore;

    public void save(String conversationId, String releaseId, DownloadOption expandedOption) {
        stateStore.put(conversationId, FLOW_KEY, new PendingConfirm(releaseId, expandedOption));
    }

    public Optional<PendingConfirm> get(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, PendingConfirm.class);
    }

    public void clear(String conversationId) {
        stateStore.remove(conversationId, FLOW_KEY);
    }

    public record PendingConfirm(String releaseId, DownloadOption expandedOption) {}
}

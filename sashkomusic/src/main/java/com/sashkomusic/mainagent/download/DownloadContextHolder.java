package com.sashkomusic.mainagent.download;

import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadContextHolder {

    private static final String FLOW_KEY = "dl_ctx";

    private final ChatStateStore stateStore;

    public void saveDownloadOptions(String conversationId, String releaseId, List<DownloadFlowHandler.OptionReport> optionReports) {
        log.debug("Saving download options for conversation: {}, releaseId: {}", conversationId, releaseId);
        stateStore.put(conversationId, FLOW_KEY, new DownloadContext(releaseId, optionReports));
    }

    public List<DownloadFlowHandler.OptionReport> getDownloadOptions(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, DownloadContext.class)
                .map(DownloadContext::optionReports)
                .orElse(List.of());
    }

    public String getChosenRelease(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, DownloadContext.class)
                .map(DownloadContext::chosenReleaseId)
                .orElse(null);
    }

    public void clearSession(String conversationId) {
        log.debug("Clearing download session for conversation: {}", conversationId);
        stateStore.remove(conversationId, FLOW_KEY);
    }

    public void clearAllSessions() {
        int count = stateStore.clearAll(FLOW_KEY);
        log.info("Cleared all download sessions: {} sessions", count);
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        clearSession(event.conversationId());
    }

    public record DownloadContext(
            String chosenReleaseId,
            List<DownloadFlowHandler.OptionReport> optionReports
    ) {}
}

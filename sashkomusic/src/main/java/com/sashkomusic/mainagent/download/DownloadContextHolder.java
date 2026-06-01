package com.sashkomusic.mainagent.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DownloadContextHolder {

    private final Map<String, DownloadContext> downloadSessions = new ConcurrentHashMap<>();

    public void saveDownloadOptions(String conversationId, String releaseId, List<DownloadFlowHandler.OptionReport> optionReports) {
        log.debug("Saving download options for conversation: {}, releaseId: {}", conversationId, releaseId);
        downloadSessions.put(conversationId, new DownloadContext(releaseId, optionReports));
    }

    public List<DownloadFlowHandler.OptionReport> getDownloadOptions(String conversationId) {
        DownloadContext context = downloadSessions.get(conversationId);
        if (context != null) {
            return context.optionReports();
        }
        return List.of();
    }

    public String getChosenRelease(String conversationId) {
        DownloadContext context = downloadSessions.get(conversationId);
        if (context != null) {
            return context.chosenReleaseId();
        }
        return null;
    }

    public void clearSession(String conversationId) {
        log.debug("Clearing download session for conversation: {}", conversationId);
        downloadSessions.remove(conversationId);
    }

    public void clearAllSessions() {
        int count = downloadSessions.size();
        downloadSessions.clear();
        log.info("Cleared all download sessions: {} sessions", count);
    }

    private record DownloadContext(
            String chosenReleaseId,
            List<DownloadFlowHandler.OptionReport> optionReports
    ) {
    }
}

package com.sashkomusic.mainagent.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DownloadContextHolder {

    private final Map<Long, DownloadContext> downloadSessions = new ConcurrentHashMap<>();

    public void saveDownloadOptions(long chatId, String releaseId, List<DownloadFlowHandler.OptionReport> optionReports) {
        log.debug("Saving download options for chatId: {}, releaseId: {}", chatId, releaseId);
        downloadSessions.put(chatId, new DownloadContext(releaseId, optionReports));
    }

    public List<DownloadFlowHandler.OptionReport> getDownloadOptions(long chatId) {
        DownloadContext context = downloadSessions.get(chatId);
        if (context != null) {
            return context.optionReports();
        }
        return List.of();
    }

    public String getChosenRelease(long chatId) {
        DownloadContext context = downloadSessions.get(chatId);
        if (context != null) {
            return context.chosenReleaseId();
        }
        return null;
    }

    public void clearSession(long chatId) {
        log.debug("Clearing download session for chatId: {}", chatId);
        downloadSessions.remove(chatId);
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

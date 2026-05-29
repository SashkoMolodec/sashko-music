package com.sashkomusic.downloadagent.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ActiveDownloadRegistry {

    private final ConcurrentHashMap<String, CancellationHandle> activeDownloads = new ConcurrentHashMap<>();

    public void registerCancelHandle(String releaseId, CancellationHandle handle) {
        activeDownloads.put(releaseId, handle);
        log.info("Registered active download for releaseId={}", releaseId);
    }

    public boolean cancel(String releaseId) {
        CancellationHandle handle = activeDownloads.remove(releaseId);
        if (handle != null) {
            try {
                handle.cancel();
                log.info("Successfully cancelled download for releaseId={}", releaseId);
                return true;
            } catch (Exception e) {
                log.error("Error cancelling download for releaseId={}", releaseId, e);
                return false;
            }
        }
        log.warn("No active download found for releaseId={}", releaseId);
        return false;
    }

    public void remove(String releaseId) {
        activeDownloads.remove(releaseId);
        log.debug("Removed download registry entry for releaseId={}", releaseId);
    }

    public interface CancellationHandle {
        void cancel() throws Exception;
    }
}

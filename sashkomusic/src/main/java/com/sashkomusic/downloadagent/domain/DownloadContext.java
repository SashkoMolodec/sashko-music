package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.downloadagent.config.SlskdPathConfig;
import com.sashkomusic.downloadagent.domain.model.DownloadBatch;
import com.sashkomusic.mainagent.download.DownloadEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class DownloadContext {

    private final SlskdPathConfig pathConfig;

    private final ConcurrentHashMap<String, DownloadBatch> batches = new ConcurrentHashMap<>();

    public void registerBatch(long chatId, String releaseId, List<String> filenames, DownloadEngine source) {
        String remoteDirectoryPath = filenames.isEmpty() ? "" : extractDirectory(filenames.getFirst());

        DownloadBatch batch = new DownloadBatch(chatId, releaseId, remoteDirectoryPath, filenames, source);
        batches.put(releaseId, batch);

        log.info("Registered download batch: releaseId={}, directory={}, source={}, files={}",
                releaseId, remoteDirectoryPath, source, filenames.size());
    }

    public DownloadBatch markFileCompleted(String remoteFilename, String localFilename) {
        DownloadBatch batch = batches.values().stream()
                .filter(b -> b.getAllFiles().contains(remoteFilename))
                .findFirst()
                .orElse(null);

        if (batch == null) {
            log.warn("No batch found for file: {}", remoteFilename);
            return null;
        }

        batch.markFileCompleted(remoteFilename);

        String transformedLocalPath = pathConfig.transformToLocalPath(localFilename);
        log.debug("Path transformation: {} -> {}", localFilename, transformedLocalPath);
        batch.addLocalFilename(transformedLocalPath);

        if (batch.isComplete()) {
            batches.remove(batch.getReleaseId());
            log.info("Download batch completed: releaseId={}", batch.getReleaseId());
        }

        return batch;
    }

    public DownloadBatch findBatchByReleaseId(String releaseId) {
        return batches.get(releaseId);
    }

    public boolean removeBatchByReleaseId(String releaseId) {
        DownloadBatch batch = batches.remove(releaseId);
        if (batch != null) {
            log.info("Removed download batch for releaseId={}", releaseId);
            return true;
        }
        log.warn("No batch found to remove for releaseId={}", releaseId);
        return false;
    }

    private String extractDirectory(String path) {
        if (path == null) {
            return "";
        }
        int lastSlash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        if (lastSlash > 0) {
            return path.substring(0, lastSlash);
        }
        return "";
    }
}
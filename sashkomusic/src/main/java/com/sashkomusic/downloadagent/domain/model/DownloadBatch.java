package com.sashkomusic.downloadagent.domain.model;

import com.sashkomusic.mainagent.download.DownloadEngine;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Getter
public class DownloadBatch {
    private final long chatId;
    private final String releaseId;
    private final String remoteDirectoryPath;
    private final List<String> allFiles;
    private final DownloadEngine source;

    private final List<String> localFilenames;

    private final Set<String> remainingFiles;

    public DownloadBatch(long chatId, String releaseId, String remoteDirectoryPath, List<String> files, DownloadEngine source) {
        this.chatId = chatId;
        this.releaseId = releaseId;
        this.remoteDirectoryPath = remoteDirectoryPath;
        this.allFiles = List.copyOf(files);
        this.source = source;
        this.localFilenames = new ArrayList<>();
        this.remainingFiles = new HashSet<>(files);
        log.debug("Created download batch: releaseId={}, directory={}, source={}, totalFiles={}",
                releaseId, remoteDirectoryPath, source, files.size());
    }

    public void markFileCompleted(String remoteFilename) {
        boolean removed = remainingFiles.remove(remoteFilename);
        if (removed) {
            log.debug("File completed: {} ({}/{} files remaining)",
                    remoteFilename, remainingFiles.size(), allFiles.size());
        }
    }

    public void addLocalFilename(String localFilename) {
        localFilenames.add(localFilename);
    }

    public boolean isComplete() {
        return remainingFiles.isEmpty();
    }

    public int getTotalFiles() {
        return allFiles.size();
    }

    public int getRemainingCount() {
        return remainingFiles.size();
    }

    public String getLocalDirectoryPath() {
        if (localFilenames.isEmpty()) {
            return "";
        }
        // Extract directory from first local filename
        String firstLocal = localFilenames.getFirst();
        if (firstLocal == null) {
            return "";
        }
        int lastSlash = Math.max(firstLocal.lastIndexOf('\\'), firstLocal.lastIndexOf('/'));
        if (lastSlash > 0) {
            return firstLocal.substring(0, lastSlash);
        }
        return "";
    }
}
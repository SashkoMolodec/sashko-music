package com.sashkomusic.downloadagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

@JsonTypeName("download_batch_complete")
public record DownloadBatchCompleteDto(
        long chatId,
        String releaseId,
        String directoryPath,
        List<String> allFiles,
        int totalFiles
) {
    public static DownloadBatchCompleteDto of(long chatId, String releaseId, String directoryPath, List<String> allFiles) {
        return new DownloadBatchCompleteDto(chatId, releaseId, directoryPath, allFiles, allFiles.size());
    }
}
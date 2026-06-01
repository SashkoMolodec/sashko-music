package com.sashkomusic.downloadagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

@JsonTypeName("download_batch_complete")
public record DownloadBatchCompleteDto(
        String conversationId,
        String releaseId,
        String directoryPath,
        List<String> allFiles,
        int totalFiles
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static DownloadBatchCompleteDto of(String conversationId, String releaseId, String directoryPath, List<String> allFiles) {
        return new DownloadBatchCompleteDto(conversationId, releaseId, directoryPath, allFiles, allFiles.size());
    }
}

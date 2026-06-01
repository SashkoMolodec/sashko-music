package com.sashkomusic.mainagent.process.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;

import java.util.List;

@JsonTypeName("process_library")
public record ProcessLibraryTaskDto(
        String conversationId,
        String directoryPath,
        List<String> downloadedFiles,
        ReleaseMetadata metadata
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static ProcessLibraryTaskDto of(String conversationId, String directoryPath, List<String> files, ReleaseMetadata metadata) {
        return new ProcessLibraryTaskDto(conversationId, directoryPath, files, metadata);
    }
}

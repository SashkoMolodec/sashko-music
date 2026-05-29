package com.sashkomusic.mainagent.process.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;

import java.util.List;

@JsonTypeName("process_library")
public record ProcessLibraryTaskDto(
        long chatId,
        String directoryPath,
        List<String> downloadedFiles,
        ReleaseMetadata metadata
) {
    public static ProcessLibraryTaskDto of(long chatId, String directoryPath, List<String> files, ReleaseMetadata metadata) {
        return new ProcessLibraryTaskDto(chatId, directoryPath, files, metadata);
    }
}
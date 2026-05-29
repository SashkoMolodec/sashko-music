package com.sashkomusic.mainagent.process.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.process.ReprocessReleasesFlowService.ReprocessOptions;

@JsonTypeName("reprocess_release")
public record ReprocessReleaseTaskDto(
        long chatId,
        String directoryPath,
        ReleaseMetadata metadata,
        int newMetadataVersion,
        ReprocessOptions options
) {
}

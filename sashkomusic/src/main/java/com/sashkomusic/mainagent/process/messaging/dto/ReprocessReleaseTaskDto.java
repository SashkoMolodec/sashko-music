package com.sashkomusic.mainagent.process.messaging.dto;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.process.ReprocessReleasesFlowService.ReprocessOptions;

@JsonTypeName("reprocess_release")
public record ReprocessReleaseTaskDto(
        String conversationId,
        String directoryPath,
        ReleaseMetadata metadata,
        int newMetadataVersion,
        ReprocessOptions options
) {
    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }
}

package com.sashkomusic.downloadagent.infrastructure.client.slskd.dto;

import java.util.List;

public record SlskdDownloadResponse(
        List<EnqueuedDownload> enqueued,
        List<Object> failed
) {
    public record EnqueuedDownload(
            String id,
            String username,
            String direction,
            String filename,
            long size,
            long startOffset,
            String state,
            String stateDescription
    ) {
    }
}
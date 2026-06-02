package com.sashkomusic.downloadagent.messaging.producer.dto;

import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;

import java.util.List;

public record SearchFilesResultDto(
        String conversationId,
        String releaseId,
        DownloadEngine source,
        List<DownloadOption> results) {

    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }
}

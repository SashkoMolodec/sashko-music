package com.sashkomusic.downloadagent.messaging.producer.dto;

import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;

import java.util.List;

public record SearchFilesResultDto(
        long chatId,
        String releaseId,
        DownloadEngine source,
        List<DownloadOption> results,
        boolean autoDownload) {
}

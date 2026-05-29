package com.sashkomusic.mainagent.download.messaging.dto;

import com.sashkomusic.mainagent.download.DownloadEngine;

public record SearchFilesTaskDto(
        long chatId,
        String releaseId,
        String artist,
        String title,
        DownloadEngine source) {

    public static SearchFilesTaskDto of(long chatId, String releaseId, String artist, String title) {
        return new SearchFilesTaskDto(chatId, releaseId, artist, title, null);
    }

    public static SearchFilesTaskDto of(long chatId, String releaseId, String artist, String title, DownloadEngine source) {
        return new SearchFilesTaskDto(chatId, releaseId, artist, title, source);
    }
}

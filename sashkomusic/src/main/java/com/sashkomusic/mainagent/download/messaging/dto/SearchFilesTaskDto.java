package com.sashkomusic.mainagent.download.messaging.dto;

import com.sashkomusic.mainagent.download.DownloadEngine;

public record SearchFilesTaskDto(
        String conversationId,
        String releaseId,
        String artist,
        String title,
        DownloadEngine source) {

    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static SearchFilesTaskDto of(String conversationId, String releaseId, String artist, String title) {
        return new SearchFilesTaskDto(conversationId, releaseId, artist, title, null);
    }

    public static SearchFilesTaskDto of(String conversationId, String releaseId, String artist, String title, DownloadEngine source) {
        return new SearchFilesTaskDto(conversationId, releaseId, artist, title, source);
    }
}

package com.sashkomusic.agents.contract;

import com.sashkomusic.mainagent.download.DownloadEngine;

public record DownloadRequest(
        long chatId,
        String releaseId,
        String artist,
        String album,
        DownloadEngine engine
) implements AgentRequest {

    public static DownloadRequest byReleaseId(long chatId, String releaseId) {
        return new DownloadRequest(chatId, releaseId, null, null, null);
    }

    public static DownloadRequest byQuery(long chatId, String artist, String album) {
        return new DownloadRequest(chatId, null, artist, album, null);
    }
}

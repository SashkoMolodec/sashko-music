package com.sashkomusic.agents.contract;

import com.sashkomusic.mainagent.download.DownloadEngine;

public record DownloadRequest(
        String conversationId,
        String releaseId,
        String artist,
        String album,
        DownloadEngine engine
) implements AgentRequest {

    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static DownloadRequest byReleaseId(String conversationId, String releaseId) {
        return new DownloadRequest(conversationId, releaseId, null, null, null);
    }

    public static DownloadRequest byQuery(String conversationId, String artist, String album) {
        return new DownloadRequest(conversationId, null, artist, album, null);
    }
}

package com.sashkomusic.downloadagent.infrastructure.client.youtubemusic;

public record YouTubeMusicSearchResult(
        String playlistId,
        String title,
        String artist,
        String year,
        String thumbnailUrl
) {}

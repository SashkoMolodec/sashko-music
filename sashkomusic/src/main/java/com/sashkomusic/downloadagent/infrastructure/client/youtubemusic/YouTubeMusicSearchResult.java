package com.sashkomusic.downloadagent.infrastructure.client.youtubemusic;

public record YouTubeMusicSearchResult(
        String playlistId,
        String videoId,
        String title,
        String artist,
        String year,
        String thumbnailUrl
) {}

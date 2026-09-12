package com.sashkomusic.shared.download;

import lombok.Getter;

@Getter
public enum DownloadEngine {
    SOULSEEK("soulseek"),
    QOBUZ("qobuz"),
    APPLE_MUSIC("apple music"),
    BANDCAMP("bandcamp"),
    YOUTUBE_MUSIC("youtube music");

    final String name;

    DownloadEngine(String name) {
        this.name = name;
    }

}

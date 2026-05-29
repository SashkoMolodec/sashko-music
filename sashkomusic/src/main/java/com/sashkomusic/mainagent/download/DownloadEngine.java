package com.sashkomusic.mainagent.download;

import lombok.Getter;

@Getter
public enum DownloadEngine {
    SOULSEEK("soulseek"),
    QOBUZ("qobuz"),
    APPLE_MUSIC("apple music"),
    BANDCAMP("bandcamp");

    final String name;

    DownloadEngine(String name) {
        this.name = name;
    }

}

package com.sashkomusic.downloadagent.config;

import com.sashkomusic.downloadagent.domain.MusicSourcePort;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.downloadagent.infrastructure.client.applemusic.AppleMusicClient;
import com.sashkomusic.downloadagent.infrastructure.client.bandcamp.BandcampDownloadClient;
import com.sashkomusic.downloadagent.infrastructure.client.qobuz.QobuzClient;
import com.sashkomusic.downloadagent.infrastructure.client.slskd.SlskdClient;
import com.sashkomusic.downloadagent.infrastructure.client.youtubemusic.YouTubeMusicClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class MusicSourceConfig {

    @Bean
    public Map<DownloadEngine, MusicSourcePort> musicSources(
            QobuzClient qobuzClient,
            SlskdClient slskdClient,
            AppleMusicClient appleMusicClient,
            BandcampDownloadClient bandcampClient,
            YouTubeMusicClient youtubeMusicClient
    ) {
        return Map.of(
                DownloadEngine.QOBUZ, qobuzClient,
                DownloadEngine.SOULSEEK, slskdClient,
                DownloadEngine.APPLE_MUSIC, appleMusicClient,
                DownloadEngine.BANDCAMP, bandcampClient,
                DownloadEngine.YOUTUBE_MUSIC, youtubeMusicClient
        );
    }
}

package com.sashkomusic.mainagent.download.config;

import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.AppleMusicDownloadFlowHandler;
import com.sashkomusic.mainagent.download.BandcampDownloadFlowHandler;
import com.sashkomusic.mainagent.download.DownloadFlowHandler;
import com.sashkomusic.mainagent.download.QobuzDownloadFlowHandler;
import com.sashkomusic.mainagent.download.SoulseekDownloadFlowHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class DownloadSourceConfig {

    @Bean
    public Map<DownloadEngine, DownloadFlowHandler> downloadFlowHandlers(
            QobuzDownloadFlowHandler qobuzHandler,
            SoulseekDownloadFlowHandler soulseekHandler,
            AppleMusicDownloadFlowHandler appleMusicHandler,
            BandcampDownloadFlowHandler bandcampHandler
    ) {
        return Map.of(
                DownloadEngine.QOBUZ, qobuzHandler,
                DownloadEngine.SOULSEEK, soulseekHandler,
                DownloadEngine.APPLE_MUSIC, appleMusicHandler,
                DownloadEngine.BANDCAMP, bandcampHandler
        );
    }
}

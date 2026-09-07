package com.sashkomusic.mainagent.library.client;

import com.sashkomusic.mainagent.library.config.AppleMusicSyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Talks to itunes_agent.py on the win10 VM (tools/itunes-agent/) — the HTTP side of the
 * Apple Music library sync. Only the /remove endpoint is used here; /add is driven by
 * applemusic_sync.py directly (staging/transcoding needs a real process, not a plain HTTP call).
 */
@Slf4j
@Component
public class ITunesAgentClient {

    private final RestClient restClient;
    private final AppleMusicSyncConfig config;

    public ITunesAgentClient(RestClient.Builder builder, AppleMusicSyncConfig config) {
        this.config = config;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);

        this.restClient = builder
                .baseUrl("http://" + config.getHost() + ":" + config.getPort())
                .requestFactory(factory)
                .build();
    }

    /** Fire-and-log: a dead/unreachable VM must never block local library removal. */
    public void removeTrack(long dbid) {
        if (!config.isEnabled()) {
            return;
        }
        try {
            restClient.post()
                    .uri("/remove")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("dbid", dbid))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Removed Apple Music track dbid={}", dbid);
        } catch (Exception e) {
            log.warn("Failed to remove Apple Music track dbid={}: {}", dbid, e.getMessage());
        }
    }
}

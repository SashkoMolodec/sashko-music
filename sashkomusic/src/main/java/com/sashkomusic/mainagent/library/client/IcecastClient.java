package com.sashkomusic.mainagent.library.client;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sashkomusic.mainagent.library.config.IcecastConfig;
import com.sashkomusic.mainagent.library.client.NavidromeClient.CurrentTrackInfo;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

@Slf4j
@Component
public class IcecastClient {

    private final RestClient restClient;
    private final IcecastConfig config;

    public IcecastClient(RestClient.Builder restClientBuilder, IcecastConfig config) {
        this.restClient = restClientBuilder.build();
        this.config = config;
    }

    @CircuitBreaker(name = "icecastClient", fallbackMethod = "getCurrentlyPlayingTrackInfoFallback")
    @Retry(name = "icecastClient")
    public CurrentTrackInfo getCurrentlyPlayingTrackInfo() {
        try {
            URI uri = buildStatusJsonUri();

            log.info("Fetching currently playing track info from Icecast");
            log.debug("Icecast request URL: {}", uri);

            IcecastStatusResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(IcecastStatusResponse.class);

            if (response != null &&
                    response.icestats() != null &&
                    response.icestats().source() != null &&
                    !response.icestats().source().isEmpty()) {

                Source targetSource = response.icestats().source().getFirst();

                if (targetSource == null && response.icestats().source().size() == 1) {
                    targetSource = response.icestats().source().getFirst();
                }

                if (targetSource == null) {
                    log.warn("Mountpoint '{}' not found in Icecast sources", config.getMountpoint());
                    return null;
                }

                String artist = targetSource.artist();
                String title = targetSource.title();

                if (title == null || title.trim().isEmpty()) {
                    log.warn("No title metadata in Icecast stream");
                    return null;
                }

                log.info("✓ Successfully fetched Icecast track: {} - {}", artist, title);

                return new CurrentTrackInfo(null, artist, title);
            }

            log.warn("No active streams found in Icecast");
            return null;

        } catch (Exception e) {
            log.error("Failed to fetch Icecast track info: {}", e.getMessage());
            throw e;
        }
    }

    private CurrentTrackInfo getCurrentlyPlayingTrackInfoFallback(Exception e) {
        log.warn("Icecast getCurrentlyPlayingTrackInfo fallback triggered: {}", e.getMessage());
        return null;
    }

    private URI buildStatusJsonUri() {
        String url = config.getBaseUrl() + "/status-json.xsl";
        return URI.create(url);
    }

    public record IcecastStatusResponse(
            IceStats icestats
    ) {
    }

    public record IceStats(
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            List<Source> source
    ) {
    }

    public record Source(
            String artist,
            String title
    ) {
    }
}
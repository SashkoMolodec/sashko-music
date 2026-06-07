package com.sashkomusic.downloadagent.infrastructure.client.youtubemusic;

import com.sashkomusic.downloadagent.domain.ActiveDownloadRegistry;
import com.sashkomusic.downloadagent.domain.DownloadMonitorService;
import com.sashkomusic.downloadagent.domain.MusicSourcePort;
import com.sashkomusic.downloadagent.domain.exception.MusicDownloadException;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class YouTubeMusicClient implements MusicSourcePort {

    private static final String PLAYLIST_BASE = "https://music.youtube.com/playlist?list=";
    private static final String VIDEO_BASE = "https://music.youtube.com/watch?v=";

    private final RestClient scraperClient;
    private final com.sashkomusic.downloadagent.infrastructure.process.ProcessCommandExecutor commandExecutor;
    private final DownloadMonitorService monitorService;
    private final ActiveDownloadRegistry downloadRegistry;
    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    @Value("${ytdlp.cli-path:/usr/local/bin/yt-dlp}")
    private String cliPath;

    @Value("${ytdlp.download-path:/downloads/ytmusic}")
    private String downloadPath;

    @Value("${ytdlp.cookies-path:}")
    private String cookiesPath;

    @Value("${ytdlp.search-limit:5}")
    private int searchLimit;

    public YouTubeMusicClient(RestClient.Builder builder,
                              @Value("${sm.scraper.url}") String scraperUrl,
                              com.sashkomusic.downloadagent.infrastructure.process.ProcessCommandExecutor commandExecutor,
                              DownloadMonitorService monitorService,
                              ActiveDownloadRegistry downloadRegistry) {
        this.scraperClient = builder.baseUrl(scraperUrl).build();
        this.commandExecutor = commandExecutor;
        this.monitorService = monitorService;
        this.downloadRegistry = downloadRegistry;
    }


    @Override
    public List<DownloadOption> search(String artist, String release, String conversationId) {
        log.info("Searching YouTube Music: artist='{}', release='{}'", artist, release);
        try {
            List<YouTubeMusicSearchResult> results = scraperClient.get()
                    .uri(u -> u.path("/ytmusic/search")
                            .queryParam("artist", artist)
                            .queryParam("album", release)
                            .queryParam("limit", searchLimit)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (results == null || results.isEmpty()) {
                log.info("No YouTube Music results for: {} {}", artist, release);
                return List.of();
            }
            log.info("Found {} YouTube Music results", results.size());
            return results.stream().map(this::toDownloadOption).toList();
        } catch (Exception e) {
            log.error("YouTube Music search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private DownloadOption toDownloadOption(YouTubeMusicSearchResult r) {
        boolean isSingle = r.playlistId() == null || r.playlistId().isBlank();
        String qualityLabel = cookiesPath.isBlank() ? "AAC 128kbps" : "AAC 256kbps";
        String typeTag = isSingle ? "single" : "album";
        String displayName = r.artist() + " - " + r.title()
                + (r.year() == null || r.year().isBlank() ? "" : " (" + r.year() + ")")
                + " [" + typeTag + ", " + qualityLabel + "]";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("playlistId", r.playlistId() != null ? r.playlistId() : "");
        metadata.put("videoId", r.videoId() != null ? r.videoId() : "");
        metadata.put("artist", r.artist());
        metadata.put("title", r.title());
        metadata.put("year", r.year() != null ? r.year() : "");
        metadata.put("qualityLabel", qualityLabel);
        String optionId = isSingle ? "ytm-v-" + r.videoId() : "ytm-" + r.playlistId();
        return new DownloadOption(
                optionId,
                DownloadEngine.YOUTUBE_MUSIC,
                displayName,
                0,
                List.of(),
                metadata
        );
    }

    @Override
    public String initiateDownload(DownloadOption option, String releaseId, String conversationId) {
        String playlistId = option.technicalMetadata().get("playlistId");
        String videoId = option.technicalMetadata().get("videoId");
        boolean isSingle = playlistId == null || playlistId.isBlank();
        if (isSingle && (videoId == null || videoId.isBlank())) {
            throw new MusicDownloadException("Missing YouTube Music playlistId and videoId");
        }
        String url = isSingle ? VIDEO_BASE + videoId : PLAYLIST_BASE + playlistId;
        log.info("Initiating YouTube Music download: url={}, releaseId={}", url, releaseId);
        try {
            downloadRegistry.registerCancelHandle(releaseId, () -> {
                Process process = activeProcesses.get(releaseId);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    log.info("Killed yt-dlp process for releaseId={}", releaseId);
                }
                activeProcesses.remove(releaseId);
                monitorService.stopMonitoring(releaseId);
            });

            List<String> cmd = buildCommand(url, isSingle);
            Process process = commandExecutor.execute("yt-dlp", conversationId, cmd.toArray(new String[0]));
            activeProcesses.put(releaseId, process);
            log.info("YouTube Music download completed for releaseId={}", releaseId);
            return isSingle ? videoId : playlistId;
        } catch (Exception e) {
            log.error("Error starting YouTube Music download: {}", e.getMessage(), e);
            throw new MusicDownloadException("не вийшло завантажити з YouTube Music: " + e.getMessage(), e);
        }
    }

    private List<String> buildCommand(String url) {
        return buildCommand(url, false);
    }

    private List<String> buildCommand(String url, boolean isSingle) {
        List<String> cmd = new ArrayList<>(List.of(
                cliPath,
                "-f", "ba*",
                "-x", "--audio-format", "m4a",
                "--embed-metadata",
                "--embed-thumbnail"
        ));
        if (!cookiesPath.isBlank()) {
            cmd.add("--cookies");
            cmd.add(cookiesPath);
        }
        cmd.add("-o");
        String outputTemplate = isSingle
                ? downloadPath + "/%(uploader)s/%(album,title)s/%(title)s.%(ext)s"
                : downloadPath + "/%(uploader)s/%(album)s/%(playlist_index)02d - %(title)s.%(ext)s";
        cmd.add(outputTemplate);
        cmd.add(url);
        return cmd;
    }

    @Override
    public String getDownloadPath(DownloadOption option) {
        return downloadPath;
    }

    @Override
    public void handleDownloadCompletion(String conversationId, String releaseId,
                                         DownloadOption option, String downloadPath) {
        String artist = option.technicalMetadata().get("artist");
        String title = option.technicalMetadata().get("title");
        int expectedFileCount = option.files().isEmpty() ? 1 : option.files().size();
        monitorService.startMonitoring(conversationId, releaseId, downloadPath, expectedFileCount, artist, title);
        log.info("Started monitoring for YouTube Music download: {}", downloadPath);
    }

}

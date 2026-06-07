package com.sashkomusic.downloadagent.infrastructure.client.applemusic;

import com.sashkomusic.downloadagent.domain.ActiveDownloadRegistry;
import com.sashkomusic.downloadagent.domain.DownloadMonitorService;
import com.sashkomusic.downloadagent.domain.MusicSourcePort;
import com.sashkomusic.downloadagent.domain.exception.MusicDownloadException;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Service
public class AppleMusicClient implements MusicSourcePort {

    private final ITunesSearchClient searchClient;
    private final DownloadMonitorService monitorService;
    private final ActiveDownloadRegistry downloadRegistry;
    private final com.sashkomusic.downloadagent.infrastructure.process.ProcessCommandExecutor commandExecutor;

    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    @Value("${applemusic.gamdl.path}")
    private String gamdlPath;

    @Value("${applemusic.gamdl.cookies}")
    private String cookiesPath;

    @Value("${applemusic.gamdl.output}")
    private String outputPath;

    public AppleMusicClient(ITunesSearchClient searchClient,
                            DownloadMonitorService monitorService,
                            ActiveDownloadRegistry downloadRegistry,
                            com.sashkomusic.downloadagent.infrastructure.process.ProcessCommandExecutor commandExecutor) {
        this.searchClient = searchClient;
        this.monitorService = monitorService;
        this.downloadRegistry = downloadRegistry;
        this.commandExecutor = commandExecutor;
    }


    @Override
    public List<DownloadOption> search(String artist, String release) {
        log.info("Searching Apple Music: artist='{}', release='{}'", artist, release);

        try {
            var searchResults = searchClient.search(artist, release);

            if (searchResults.isEmpty()) {
                log.info("No Apple Music results found for: {} - {}", artist, release);
                return List.of();
            }

            log.info("Found {} Apple Music results", searchResults.size());

            return searchResults.stream()
                    .map(this::toDownloadOption)
                    .toList();

        } catch (Exception e) {
            log.error("Error searching Apple Music: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public String initiateDownload(DownloadOption option, String releaseId) {
        String url = option.technicalMetadata().get("url");

        if (url == null) {
            throw new MusicDownloadException("Missing Apple Music URL in metadata");
        }

        log.info("Initiating Apple Music download: url={}, releaseId={}", url, releaseId);

        try {
            if (!Files.exists(Path.of(gamdlPath))) {
                throw new MusicDownloadException("gamdl executable not found: " + gamdlPath);
            }

            if (!Files.exists(Path.of(cookiesPath))) {
                throw new MusicDownloadException("Cookies file not found: " + cookiesPath);
            }

            downloadRegistry.registerCancelHandle(releaseId, () -> {
                Process process = activeProcesses.get(releaseId);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    log.info("Forcibly killed Apple Music download process for releaseId={}", releaseId);
                }
                activeProcesses.remove(releaseId);
                monitorService.stopMonitoring(releaseId);
            });

            Process process = commandExecutor.execute("gamdl",
                    gamdlPath,
                    url,
                    "--cookies-path", cookiesPath,
                    "--output-path", outputPath,
                    "--language", "uk",
                    "--song-codec", "aac-legacy"
            );

            activeProcesses.put(releaseId, process);

            log.info("Apple Music download completed");

            String batchId = option.technicalMetadata().get("albumId");
            return batchId != null ? batchId : option.id();

        } catch (Exception e) {
            log.error("Error downloading from Apple Music: {}", e.getMessage(), e);
            throw new MusicDownloadException("не вийшло скачати з Apple Music: " + e.getMessage(), e);
        }
    }

    @Override
    public String getDownloadPath(DownloadOption option) {
        return outputPath;
    }

    @Override
    public void handleDownloadCompletion(String conversationId, String releaseId, DownloadOption option, String downloadPath) {
        log.info("Handling Apple Music download completion: conversationId={}, releaseId={}", conversationId, releaseId);

        String artist = option.technicalMetadata().get("artist");
        String albumName = option.technicalMetadata().get("albumName");
        int trackCount = Integer.parseInt(option.technicalMetadata().getOrDefault("trackCount", "1"));

        log.info("Starting monitoring for Apple Music album: artist='{}', album='{}', expectedTracks={}",
                artist, albumName, trackCount);

        monitorService.startMonitoring(conversationId, releaseId, downloadPath, trackCount, artist, albumName);
    }

    private DownloadOption toDownloadOption(ITunesSearchClient.AppleMusicSearchResult result) {
        String optionId = "applemusic-" + result.id();
        String displayName = result.displayName();

        int totalSizeMB = 0;
        List<DownloadOption.FileItem> files = List.of();

        Map<String, String> metadata = new HashMap<>();
        metadata.put("url", result.url());
        metadata.put("albumId", result.id());
        metadata.put("artist", result.artistName());
        metadata.put("albumName", result.albumName());
        metadata.put("trackCount", String.valueOf(result.trackCount()));

        return new DownloadOption(
                optionId,
                DownloadEngine.APPLE_MUSIC,
                displayName,
                totalSizeMB,
                files,
                metadata
        );
    }

    @Override
    public void cancelDownload(String releaseId) {
        downloadRegistry.cancel(releaseId);
    }
}

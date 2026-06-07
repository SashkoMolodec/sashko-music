package com.sashkomusic.downloadagent.infrastructure.client.bandcamp;

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
public class BandcampDownloadClient implements MusicSourcePort {

    private final BandcampSearchClient searchClient;
    private final DownloadMonitorService monitorService;
    private final ActiveDownloadRegistry downloadRegistry;
    private final com.sashkomusic.downloadagent.infrastructure.process.ProcessCommandExecutor commandExecutor;

    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    @Value("${bandcamp.cli-path}")
    private String bandcampDlPath;

    @Value("${bandcamp.download-path}")
    private String outputPath;

    public BandcampDownloadClient(BandcampSearchClient searchClient,
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
        log.info("Searching Bandcamp: artist='{}', release='{}'", artist, release);

        try {
            var searchResults = searchClient.search(artist, release);

            if (searchResults.isEmpty()) {
                log.info("No Bandcamp results found for: {} - {}", artist, release);
                return List.of();
            }

            log.info("Found {} Bandcamp results", searchResults.size());

            return searchResults.stream()
                    .map(this::toDownloadOption)
                    .toList();

        } catch (Exception e) {
            log.error("Error searching Bandcamp: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public String initiateDownload(DownloadOption option, String releaseId, String conversationId) {
        String url = option.technicalMetadata().get("url");

        if (url == null) {
            throw new MusicDownloadException("Missing Bandcamp URL in metadata");
        }

        log.info("Initiating Bandcamp download: url={}, releaseId={}", url, releaseId);

        try {
            if (!Files.exists(Path.of(bandcampDlPath))) {
                throw new MusicDownloadException("bandcamp-dl executable not found: " + bandcampDlPath);
            }

            downloadRegistry.registerCancelHandle(releaseId, () -> {
                Process process = activeProcesses.get(releaseId);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    log.info("Forcibly killed Bandcamp download process for releaseId={}", releaseId);
                }
                activeProcesses.remove(releaseId);
                monitorService.stopMonitoring(releaseId);
            });

            Process process = commandExecutor.execute("bandcamp-dl", conversationId,
                    bandcampDlPath,
                    url,
                    "--base-dir", outputPath,
                    "--template", "%{artist}/%{album}/%{track} - %{title}"
            );

            activeProcesses.put(releaseId, process);

            log.info("Bandcamp download completed");

            return option.id();

        } catch (Exception e) {
            log.error("Error downloading from Bandcamp: {}", e.getMessage(), e);
            throw new MusicDownloadException("не вийшло скачати з Bandcamp: " + e.getMessage(), e);
        }
    }

    @Override
    public String getDownloadPath(DownloadOption option) {
        return outputPath;
    }

    @Override
    public void handleDownloadCompletion(String conversationId, String releaseId, DownloadOption option, String downloadPath) {
        log.info("Handling Bandcamp download completion: conversationId={}, releaseId={}", conversationId, releaseId);

        String artist = option.technicalMetadata().get("artist");
        String title = option.technicalMetadata().get("title");
        // Bandcamp doesn't provide trackCount in search, use 1 as default
        int expectedFiles = 1;

        log.info("Starting monitoring for Bandcamp release: artist='{}', title='{}', expectedFiles={}",
                artist, title, expectedFiles);

        monitorService.startMonitoring(conversationId, releaseId, downloadPath, expectedFiles, artist, title);
    }

    private DownloadOption toDownloadOption(BandcampSearchResult result) {
        String optionId = "bandcamp-" + UUID.randomUUID();
        String displayName = result.artist() + " - " + result.title() + " [" + result.type() + "]";

        int totalSizeMB = 0; // Unknown until download
        List<DownloadOption.FileItem> files = List.of(); // Not available from search

        Map<String, String> metadata = new HashMap<>();
        metadata.put("url", result.url());
        metadata.put("artist", result.artist());
        metadata.put("title", result.title());
        metadata.put("type", result.type());

        return new DownloadOption(
                optionId,
                DownloadEngine.BANDCAMP,
                displayName,
                totalSizeMB,
                files,
                metadata
        );
    }

}

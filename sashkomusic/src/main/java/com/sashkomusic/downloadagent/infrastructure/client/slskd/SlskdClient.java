package com.sashkomusic.downloadagent.infrastructure.client.slskd;

import com.sashkomusic.downloadagent.config.SlskdPathConfig;
import com.sashkomusic.downloadagent.domain.ActiveDownloadRegistry;
import com.sashkomusic.downloadagent.domain.MusicSourcePort;
import com.sashkomusic.downloadagent.domain.exception.MusicDownloadException;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import com.sashkomusic.downloadagent.infrastructure.client.slskd.dto.SlskdDownloadResponse;
import com.sashkomusic.downloadagent.infrastructure.client.slskd.dto.SlskdSearchEntryResponse;
import com.sashkomusic.downloadagent.infrastructure.client.slskd.dto.SlskdSearchEventResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class SlskdClient implements MusicSourcePort {

    private static final long POLL_TIMEOUT_MS = 20_000;
    private static final long POLL_INTERVAL_MS = 3_000;
    private static final long STABILIZATION_DELAY_MS = 10_000;

    private final RestClient client;
    private final String apiKey;
    private final SlskdPathConfig pathConfig;
    private final ActiveDownloadRegistry downloadRegistry;

    private final ConcurrentHashMap<String, List<String>> transferIds = new ConcurrentHashMap<>();

    public SlskdClient(RestClient.Builder builder,
                       @Value("${slskd.api-key:}") String apiKey,
                       @Value("${slskd.base-url:http://localhost:5030}") String baseUrl,
                       SlskdPathConfig pathConfig,
                       ActiveDownloadRegistry downloadRegistry) {
        log.info("Initializing SlskdClient with base URL: {}", baseUrl);
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.pathConfig = pathConfig;
        this.downloadRegistry = downloadRegistry;
    }


    @Override
    @CircuitBreaker(name = "slskdClient", fallbackMethod = "searchFallback")
    @Retry(name = "slskdClient")
    public List<DownloadOption> search(String artist, String release) {
        var query = artist + " " + release;
        log.info("🔄 Soulseek search attempt for: {}", query);

        var searchId = initiateSearchRequest(query);
        int fileCount = waitForSearchToComplete(searchId);
        waitToStabilize(fileCount);

        List<DownloadOption> results = getSearchResults(searchId);

        if (results.isEmpty()) {
            log.warn("❌ No results found for query: {}, throwing exception to trigger retry", query);
            throw new NoSearchResultsException("No results found for: " + query);
        }

        log.info("✅ Found {} results", results.size());
        return results;
    }

    private List<DownloadOption> searchFallback(String artist, String release, Exception e) {
        log.warn("Slskd search fallback triggered for '{}' - '{}': {}", artist, release, e.getMessage());
        return List.of();
    }

    private UUID initiateSearchRequest(String query) {
        Map<String, Object> searchRequest = Map.of(
                "searchText", query,
                "searchTimeout", (int) POLL_TIMEOUT_MS,
                "responseLimit", 70,
                "filterResponses", true,
                "minimumResponseFileCount", 1,
                "minimumPeerUploadSpeed", 0
        );

        try {
            SlskdSearchEventResponse response = client.post()
                    .uri("/api/v0/searches")
                    .header("X-API-KEY", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(searchRequest)
                    .retrieve()
                    .body(SlskdSearchEventResponse.class);

            if (response == null || response.getId() == null) {
                throw new RuntimeException("Failed to initiate search for query: " + query);
            }

            log.info("Search initiated: '{}' (ID: {})", query, response.getId());
            return response.getId();
        } catch (Exception e) {
            log.error("Failed to initiate search for query '{}': {}", query, e.getMessage(), e);
            throw new RuntimeException("Failed to connect to slskd service. Check if slskd is running and accessible", e);
        }
    }

    private int waitForSearchToComplete(UUID searchId) {
        long endTime = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        SlskdSearchEventResponse lastStatus = null;

        while (System.currentTimeMillis() < endTime) {
            try {
                var status = getSearchStatus(searchId);
                lastStatus = status;
                log.info("Status: searchId={}, state={}, isComplete={}, fileCount={}",
                        searchId, status.getState(), status.getIsComplete(), status.getFileCount());

                if (Boolean.TRUE.equals(status.getIsComplete())) {
                    return status.getFileCount() != null ? status.getFileCount() : 0;
                }

                Thread.sleep(POLL_INTERVAL_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Search interrupted", e);
            } catch (Exception e) {
                log.warn("Error polling status, retrying in next tick: {}", e.getMessage());
            }
        }
        log.warn("Search polling timed out locally for ID: {}. Returning accumulated results.", searchId);
        return lastStatus != null && lastStatus.getFileCount() != null ? lastStatus.getFileCount() : 0;
    }

    private static void waitToStabilize(int fileCount) {
        if (fileCount > 0) {
            try {
                log.debug("Waiting {}ms for results to stabilize...", STABILIZATION_DELAY_MS);
                Thread.sleep(STABILIZATION_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            log.info("No files found, skipping stabilization wait");
        }
    }

    private SlskdSearchEventResponse getSearchStatus(UUID searchId) {
        return client.get()
                .uri("/api/v0/searches/{id}", searchId)
                .header("X-API-KEY", apiKey)
                .retrieve()
                .body(SlskdSearchEventResponse.class);
    }

    private List<DownloadOption> getSearchResults(UUID searchId) {
        List<SlskdSearchEntryResponse> responses = client.get()
                .uri("/api/v0/searches/{id}/responses", searchId.toString())
                .header("X-API-KEY", apiKey)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        return toDomain(responses);
    }

    private List<DownloadOption> toDomain(List<SlskdSearchEntryResponse> response) {
        if (response == null) return List.of();

        log.info("Raw responses from slskd: {}", response.size());
        long hasFiles = response.stream().filter(r -> r.files() != null && !r.files().isEmpty()).count();
        long noLocked = response.stream().filter(r -> r.files() != null && !r.files().isEmpty()).filter(r -> r.lockedFileCount() == 0).count();
        long canDl = response.stream().filter(r -> r.files() != null && !r.files().isEmpty()).filter(r -> r.lockedFileCount() == 0).filter(SlskdSearchEntryResponse::canDownload).count();
        log.info("After filters — hasFiles: {}, noLocked: {}, canDownload: {}", hasFiles, noLocked, canDl);

        return response.stream()
                .filter(r -> r.files() != null && !r.files().isEmpty())
                .filter(r -> r.lockedFileCount() == 0)
                .filter(SlskdSearchEntryResponse::canDownload)
                .flatMap(this::splitByAlbumFolder)
                .limit(10)
                .toList();
    }

    private Stream<DownloadOption> splitByAlbumFolder(SlskdSearchEntryResponse response) {
        Map<String, List<SlskdSearchEntryResponse.SoulseekFile>> groupedByFolder = response.files().stream()
                .filter(f -> f.size() > 0)
                .filter(SlskdSearchEntryResponse.SoulseekFile::isAudioFile)
                .collect(Collectors.groupingBy(f -> extractAlbumFolder(f.filename())));

        return groupedByFolder.entrySet().stream()
                .map(entry -> {
                    String albumFolder = entry.getKey();
                    List<SlskdSearchEntryResponse.SoulseekFile> filesInFolder = entry.getValue();

                    double totalSizeMB = filesInFolder.stream()
                            .mapToLong(SlskdSearchEntryResponse.SoulseekFile::size)
                            .sum() / (1024.0 * 1024.0);

                    return mapOption(response, albumFolder, filesInFolder, totalSizeMB);
                });
    }

    private static DownloadOption mapOption(
            SlskdSearchEntryResponse response,
            String albumFolder,
            List<SlskdSearchEntryResponse.SoulseekFile> files,
            double totalSizeMB) {

        var fileItems = files.stream()
                .map(SlskdClient::mapFileItem)
                .collect(Collectors.toList());

        Map<String, String> metadata = new HashMap<>();
        metadata.put("username", response.username());
        metadata.put("albumFolder", albumFolder);

        return new DownloadOption(
                UUID.randomUUID().toString(),
                DownloadEngine.SOULSEEK,
                response.username() + " - " + albumFolder,
                (int) totalSizeMB,
                fileItems,
                metadata
        );
    }

    private static DownloadOption.FileItem mapFileItem(SlskdSearchEntryResponse.SoulseekFile f) {
        return new DownloadOption.FileItem(
                f.filename(),
                f.size(),
                f.bitRate(),
                f.bitDepth(),
                f.sampleRate(),
                f.length() != null ? f.length() : 0
        );
    }

    private static String extractAlbumFolder(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "Unknown Album";
        }

        String normalized = filePath.replace("/", "\\");
        int lastSlash = normalized.lastIndexOf('\\');

        if (lastSlash > 0) {
            return normalized.substring(0, lastSlash);
        }

        return "Unknown Album";
    }

    @Override
    @CircuitBreaker(name = "slskdClient", fallbackMethod = "initiateDownloadFallback")
    @Retry(name = "slskdClient")
    public String initiateDownload(DownloadOption option, String releaseId, String conversationId) {
        String username = option.technicalMetadata().get("username");

        if (username == null) {
            log.error("Missing required metadata: username is null");
            throw new MusicDownloadException("трохи даних бракує для запиту шоби скачати музло");
        }

        log.info("Attempting download from user={}, files={}, releaseId={}", username, option.files().size(), releaseId);

        List<Map<String, Object>> files = option.files().stream()
                .map(f -> Map.<String, Object>of(
                        "filename", f.filename(),
                        "size", f.size()
                ))
                .toList();

        log.info("Initiating download from user={}, files count={}", username, files.size());
        files.forEach(f -> log.debug("  - {}", f.get("filename")));

        try {
            var response = client.post()
                    .uri("/api/v0/transfers/downloads/{username}", username)
                    .header("X-API-KEY", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(files)
                    .retrieve()
                    .body(SlskdDownloadResponse.class);

            if (response == null || response.enqueued() == null || response.enqueued().isEmpty()) {
                log.warn("No files were enqueued for download from username={}", username);
                throw new MusicDownloadException("ніц не виходе скачати...");
            }

            log.info("Download initiated for username={}, enqueued {} files",
                    username, response.enqueued().size());

            List<String> ids = response.enqueued().stream()
                    .map(SlskdDownloadResponse.EnqueuedDownload::id)
                    .toList();
            transferIds.put(releaseId, ids);
            log.debug("Stored {} transfer IDs for releaseId={}", ids.size(), releaseId);

            downloadRegistry.registerCancelHandle(releaseId, () -> cancelSlskdTransfers(releaseId));

            String batchId = response.enqueued().getFirst().id();
            log.info("Batch ID: {}", batchId);

            return batchId;
        } catch (MusicDownloadException e) {
            log.warn("Download attempt failed for username={}, will retry: {}", username, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to initiate download for username={}: {}", username, e.getMessage());
            throw new MusicDownloadException("не вийшло розпочати скачування: " + e.getMessage(), e);
        }
    }

    private String initiateDownloadFallback(DownloadOption option, String releaseId, Throwable t) {
        log.warn("Slskd initiateDownload fallback triggered for releaseId={}: {}", releaseId, t.getMessage());
        throw new MusicDownloadException("не вдалося розпочати скачування через slskd: " + t.getMessage(), t);
    }

    private void cancelSlskdTransfers(String releaseId) {
        List<String> ids = transferIds.remove(releaseId);
        if (ids != null) {
            for (String transferId : ids) {
                try {
                    client.delete()
                            .uri("/api/v0/transfers/" + transferId)
                            .header("X-API-KEY", apiKey)
                            .retrieve()
                            .toBodilessEntity();
                    log.info("Cancelled Slskd transfer: {}", transferId);
                } catch (Exception e) {
                    log.error("Failed to cancel transfer {}: {}", transferId, e.getMessage());
                }
            }
        }
    }

    @Override
    public String getDownloadPath(DownloadOption option) {
        String username = option.technicalMetadata().get("username");
        String albumFolder = option.technicalMetadata().get("albumFolder");

        if (username == null || albumFolder == null) {
            throw new IllegalArgumentException("Missing required metadata: username or albumFolder");
        }

        String containerPath = pathConfig.getContainerPath() + "/" + username + "/" + albumFolder;
        containerPath = containerPath.replace("\\\\", "/").replace("\\", "/");

        return pathConfig.transformToLocalPath(containerPath);
    }

    @Override
    public void handleDownloadCompletion(String conversationId, String releaseId, DownloadOption option, String downloadPath) {
        // Soulseek uses webhooks - no monitoring needed
        log.info("Soulseek download will be completed via webhook");
    }
}
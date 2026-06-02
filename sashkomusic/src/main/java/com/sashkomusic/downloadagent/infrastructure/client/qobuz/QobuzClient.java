package com.sashkomusic.downloadagent.infrastructure.client.qobuz;

import com.sashkomusic.downloadagent.domain.ActiveDownloadRegistry;
import com.sashkomusic.downloadagent.domain.DownloadMonitorService;
import com.sashkomusic.downloadagent.domain.MusicSourcePort;
import com.sashkomusic.downloadagent.domain.exception.MusicDownloadException;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import com.sashkomusic.downloadagent.infrastructure.client.qobuz.dto.QobuzSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class QobuzClient implements MusicSourcePort {

    private static final String APP_ID = "798273057";
    private static final String API_BASE = "https://www.qobuz.com/api.json/0.2";

    private final RestClient restClient;
    private final QobuzCommandExecutor commandExecutor;
    private final DownloadMonitorService monitorService;
    private final ActiveDownloadRegistry downloadRegistry;

    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    @Value("${qobuz.email:}")
    private String email;

    @Value("${qobuz.password:}")
    private String password;

    @Value("${qobuz.auth-token:}")
    private String configuredAuthToken;

    @Value("${qobuz.cli-path:/usr/local/bin/rip}")
    private String cliPath;

    @Value("${qobuz.download-path:/downloads/qobuz}")
    private String downloadPath;

    @Value("${qobuz.search-limit:5}")
    private int searchLimit;

    private volatile String authToken;

    public QobuzClient(RestClient.Builder restClientBuilder,
                       QobuzCommandExecutor commandExecutor,
                       DownloadMonitorService monitorService,
                       ActiveDownloadRegistry downloadRegistry) {
        this.restClient = restClientBuilder
                .baseUrl(API_BASE)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .build();
        this.commandExecutor = commandExecutor;
        this.monitorService = monitorService;
        this.downloadRegistry = downloadRegistry;
    }


    @Override
    public List<DownloadOption> search(String artist, String release) {
        log.info("Searching Qobuz API: artist='{}', release='{}'", artist, release);
        try {
            if (authToken == null) authenticate();
            if (authToken == null) {
                log.warn("Qobuz authentication failed, skipping search");
                return List.of();
            }
            return doSearch(artist + " " + release);
        } catch (Exception e) {
            log.error("Qobuz search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void authenticate() {
        if (!configuredAuthToken.isBlank()) {
            log.info("Using configured Qobuz auth token");
            authToken = configuredAuthToken;
            return;
        }
        if (email.isBlank() || password.isBlank()) {
            log.warn("Qobuz credentials not configured");
            return;
        }
        try {
            log.info("Authenticating with Qobuz as {}", email);
            String hashedPassword = md5(password);
            Map<String, Object> response = restClient.post()
                    .uri("/user/login")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-App-Id", APP_ID)
                    .body("email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                          + "&password=" + hashedPassword
                          + "&app_id=" + APP_ID)
                    .retrieve()
                    .body(Map.class);

            if (response != null) {
                authToken = (String) response.get("user_auth_token");
            }
            if (authToken != null) {
                log.info("Qobuz authentication successful");
            } else {
                log.warn("Qobuz auth response did not contain token: {}", response);
            }
        } catch (Exception e) {
            log.error("Qobuz authentication failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<DownloadOption> doSearch(String query) {
        Map<String, Object> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/album/search")
                        .queryParam("query", query)
                        .queryParam("app_id", APP_ID)
                        .queryParam("limit", searchLimit)
                        .build())
                .header("X-User-Auth-Token", authToken)
                .retrieve()
                .body(Map.class);

        if (response == null) return List.of();

        Map<String, Object> albums = (Map<String, Object>) response.get("albums");
        if (albums == null) return List.of();

        List<Map<String, Object>> items = (List<Map<String, Object>>) albums.get("items");
        if (items == null || items.isEmpty()) {
            log.info("No Qobuz results for query: {}", query);
            return List.of();
        }

        log.info("Found {} Qobuz results", items.size());
        return items.stream().map(this::toDownloadOption).filter(Objects::nonNull).toList();
    }

    @SuppressWarnings("unchecked")
    private DownloadOption toDownloadOption(Map<String, Object> item) {
        try {
            String id = String.valueOf(item.get("id"));
            String title = (String) item.getOrDefault("title", "");
            Map<String, Object> artistMap = (Map<String, Object>) item.get("artist");
            String artist = artistMap != null ? (String) artistMap.getOrDefault("name", "") : "";
            int trackCount = ((Number) item.getOrDefault("tracks_count", 0)).intValue();

            String year = "";
            Object releasedAt = item.get("released_at");
            if (releasedAt != null) {
                long ts = ((Number) releasedAt).longValue();
                year = String.valueOf(java.time.Instant.ofEpochSecond(ts)
                        .atZone(java.time.ZoneOffset.UTC).getYear());
            }

            Map<String, Object> imageMap = (Map<String, Object>) item.get("image");
            String imageUrl = imageMap != null ? (String) imageMap.get("large") : "";

            String albumUrl = "https://open.qobuz.com/album/" + id;

            int quality = resolveQuality(item);
            String qualityLabel = getQualityLabel(quality);
            String displayName = artist + " - " + title + " (" + year + ") [" + qualityLabel + "]";

            Map<String, String> metadata = new HashMap<>();
            metadata.put("albumUrl", albumUrl);
            metadata.put("albumId", id);
            metadata.put("quality", String.valueOf(quality));
            metadata.put("qualityLabel", qualityLabel);
            metadata.put("artist", artist);
            metadata.put("title", title);
            metadata.put("releaseDate", year);

            return new DownloadOption(
                    "qobuz-" + id + "-q" + quality,
                    DownloadEngine.QOBUZ,
                    displayName,
                    0,
                    List.of(),
                    metadata
            );
        } catch (Exception e) {
            log.warn("Failed to map Qobuz result: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private int resolveQuality(Map<String, Object> item) {
        try {
            Map<String, Object> audio = (Map<String, Object>) item.get("audio_info");
            if (audio != null) {
                int bitDepth = ((Number) audio.getOrDefault("bit_depth", 16)).intValue();
                double sampleRate = ((Number) audio.getOrDefault("maximum_sampling_rate", 44.1)).doubleValue();
                if (bitDepth == 24 && sampleRate >= 192) return 27;
                if (bitDepth == 24 && sampleRate >= 96) return 7;
                if (bitDepth == 24) return 7;
            }
        } catch (Exception ignored) {}
        return 6;
    }

    @Override
    public String initiateDownload(DownloadOption option, String releaseId) {
        String albumUrl = option.technicalMetadata().get("albumUrl");
        String quality = option.technicalMetadata().get("quality");

        if (albumUrl == null || quality == null) {
            throw new MusicDownloadException("Missing Qobuz metadata: albumUrl or quality");
        }

        log.info("Initiating Qobuz download: url={}, quality={}, releaseId={}", albumUrl, quality, releaseId);

        try {
            downloadRegistry.registerCancelHandle(releaseId, () -> {
                Process process = activeProcesses.get(releaseId);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    log.info("Forcibly killed Qobuz download process for releaseId={}", releaseId);
                }
                activeProcesses.remove(releaseId);
                monitorService.stopMonitoring(releaseId);
            });

            String ripQuality = toRipQuality(quality);
            Process process = commandExecutor.execute(
                    cliPath, "-ndb", "-f", downloadPath, "-q", ripQuality, "url", albumUrl
            );

            activeProcesses.put(releaseId, process);
            log.info("Qobuz download completed");

            String batchId = option.technicalMetadata().get("albumId");
            return batchId != null ? batchId : option.id();

        } catch (Exception e) {
            log.error("Error initiating Qobuz download: {}", e.getMessage(), e);
            throw new MusicDownloadException("не вийшло розпочати скачування з Qobuz: " + e.getMessage(), e);
        }
    }

    @Override
    public String getDownloadPath(DownloadOption option) {
        return downloadPath;
    }

    @Override
    public void handleDownloadCompletion(String conversationId, String releaseId, DownloadOption option, String downloadPath) {
        String artist = option.technicalMetadata().get("artist");
        String title = option.technicalMetadata().get("title");
        int expectedFileCount = option.files().isEmpty() ? 1 : option.files().size();
        monitorService.startMonitoring(conversationId, releaseId, downloadPath, expectedFileCount, artist, title);
        log.info("Started monitoring for Qobuz download: {}", downloadPath);
    }

    @Override
    public void cancelDownload(String releaseId) {
        downloadRegistry.cancel(releaseId);
    }

    private static String toRipQuality(String qobuzQuality) {
        return switch (qobuzQuality) {
            case "5"  -> "1";
            case "6"  -> "2";
            case "7"  -> "3";
            case "27" -> "4";
            default   -> "2";
        };
    }

    private static String md5(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 failed", e);
        }
    }

    public String getQualityLabel(int qualityCode) {
        return switch (qualityCode) {
            case 5 -> "MP3 320kbps";
            case 6 -> "FLAC 16bit/44.1kHz";
            case 7 -> "FLAC 24bit/96kHz";
            case 27 -> "FLAC 24bit/192kHz";
            default -> "Quality " + qualityCode;
        };
    }
}

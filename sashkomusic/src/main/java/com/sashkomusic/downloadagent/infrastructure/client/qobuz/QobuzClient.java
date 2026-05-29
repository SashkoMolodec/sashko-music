package com.sashkomusic.downloadagent.infrastructure.client.qobuz;

import com.sashkomusic.downloadagent.domain.ActiveDownloadRegistry;
import com.sashkomusic.downloadagent.domain.DownloadMonitorService;
import com.sashkomusic.downloadagent.domain.MusicSourcePort;
import com.sashkomusic.downloadagent.domain.exception.MusicDownloadException;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import com.sashkomusic.downloadagent.infrastructure.client.qobuz.dto.QobuzSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class QobuzClient implements MusicSourcePort {

    private final RestClient restClient;
    private final QobuzCommandExecutor commandExecutor;
    private final DownloadMonitorService monitorService;
    private final ActiveDownloadRegistry downloadRegistry;

    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    @Value("${qobuz.cli-path:/usr/local/bin/qobuz-dl}")
    private String cliPath;

    @Value("${qobuz.download-path:/downloads/qobuz}")
    private String downloadPath;

    @Value("${qobuz.search-limit:10}")
    private int searchLimit;

    public QobuzClient(RestClient.Builder restClientBuilder,
                       QobuzCommandExecutor commandExecutor,
                       DownloadMonitorService monitorService,
                       ActiveDownloadRegistry downloadRegistry) {
        this.restClient = restClientBuilder
                .baseUrl("https://www.qobuz.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .build();
        this.commandExecutor = commandExecutor;
        this.monitorService = monitorService;
        this.downloadRegistry = downloadRegistry;
    }

    @Override
    public boolean autoDownloadEnabled() {
        return true;
    }

    @Override
    public List<DownloadOption> search(String artist, String release) {
        log.info("Searching Qobuz via web scraping for: artist='{}', release='{}'", artist, release);

        try {
            String query = artist + " " + release;

            // Fetch HTML from Qobuz search page
            String html = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/us-en/search/albums/{query}")
                            .build(query))
                    .retrieve()
                    .body(String.class);

            if (html == null || html.isEmpty()) {
                log.warn("Empty response from Qobuz");
                return List.of();
            }

            // Parse HTML with JSoup
            List<QobuzSearchResult> searchResults = parseHtmlSearchResults(html);

            if (searchResults.isEmpty()) {
                log.info("No Qobuz results found for query: {}", query);
                return List.of();
            }

            log.info("Found {} albums on Qobuz", searchResults.size());

            // Convert to DownloadOptions (one option per album)
            List<DownloadOption> options = new ArrayList<>();
            for (QobuzSearchResult searchResult : searchResults) {
                options.add(createOptionForAlbum(searchResult));
            }

            log.info("Created {} download options from Qobuz results", options.size());
            return options;

        } catch (Exception e) {
            log.error("Error searching Qobuz: {}", e.getMessage(), e);
            return List.of();
        }
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

            Process process = commandExecutor.execute(
                    cliPath, "dl", albumUrl, "-q", quality, "-d", downloadPath, "--no-db"
            );

            activeProcesses.put(releaseId, process);

            log.info("Qobuz download completed");

            // Return album ID as batch ID
            String batchId = option.technicalMetadata().get("albumId");
            return batchId != null ? batchId : option.id();

        } catch (Exception e) {
            log.error("Error initiating Qobuz download: {}", e.getMessage(), e);
            throw new MusicDownloadException("не вийшло розпочати скачування з Qobuz: " + e.getMessage(), e);
        }
    }

    private List<QobuzSearchResult> parseHtmlSearchResults(String html) {
        List<QobuzSearchResult> results = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul.product__wrapper > li");

            log.info("Found {} product items in HTML", items.size());

            for (Element item : items) {
                try {
                    Element releaseCard = item.selectFirst("div.ReleaseCard");
                    if (releaseCard == null) continue;

                    // Album title
                    Element titleElement = releaseCard.selectFirst("a.ReleaseCardInfosTitle");
                    if (titleElement == null) continue;
                    String title = titleElement.text().trim();

                    // Album URL
                    Element urlElement = releaseCard.selectFirst("a.CoverModelOverlay");
                    if (urlElement == null) continue;
                    String relativeUrl = urlElement.attr("href");
                    String albumUrl = relativeUrl.startsWith("http") ? relativeUrl : "https://www.qobuz.com" + relativeUrl;

                    // Extract album ID from URL
                    String albumId = extractAlbumIdFromUrl(albumUrl);

                    // Artist
                    Element artistElement = releaseCard.selectFirst("p.ReleaseCardInfosSubtitle a");
                    String artist = artistElement != null ? artistElement.text().trim() : "";

                    // Cover URL
                    Element coverElement = releaseCard.selectFirst("img.CoverModel");
                    String coverUrl = coverElement != null ? coverElement.attr("src") : null;
                    if (coverUrl != null && !coverUrl.startsWith("http")) {
                        coverUrl = "https:" + coverUrl;
                    }

                    // Release date and other info
                    Element infoElement = releaseCard.selectFirst("p.ReleaseCardInfosData");
                    String releaseDate = "";
                    if (infoElement != null) {
                        String infoText = infoElement.text();
                        // Extract year from text like "May 23, 2025"
                        Pattern yearPattern = Pattern.compile("\\b(\\d{4})\\b");
                        Matcher yearMatcher = yearPattern.matcher(infoText);
                        if (yearMatcher.find()) {
                            releaseDate = yearMatcher.group(1);
                        }
                    }

                    // Parse available qualities
                    List<Integer> availableQualities = parseQualitiesFromHtml(releaseCard);

                    // Track count - extract from CoverModelData or use 0 if not available
                    int trackCount = 0;
                    Element trackCountElement = releaseCard.selectFirst("p.CoverModelDataDefault.ReleaseCardActionsText");
                    if (trackCountElement != null) {
                        String trackText = trackCountElement.text();
                        Pattern trackPattern = Pattern.compile("(\\d+)\\s+tracks?");
                        Matcher trackMatcher = trackPattern.matcher(trackText);
                        if (trackMatcher.find()) {
                            trackCount = Integer.parseInt(trackMatcher.group(1));
                        }
                    }

                    QobuzSearchResult result = new QobuzSearchResult(
                            albumId,
                            title,
                            artist,
                            releaseDate,
                            albumUrl,
                            trackCount,
                            availableQualities,
                            coverUrl
                    );
                    results.add(result);

                    log.debug("Parsed Qobuz album: {} - {} ({}) with qualities: {}",
                            artist, title, releaseDate, availableQualities);

                } catch (Exception e) {
                    log.warn("Failed to parse Qobuz search result item: {}", e.getMessage());
                }
            }

        } catch (Exception ex) {
            log.error("Error parsing Qobuz search results HTML: {}", ex.getMessage(), ex);
        }

        return results.stream().limit(searchLimit).toList();
    }

    private String extractAlbumIdFromUrl(String url) {
        // URL format: https://www.qobuz.com/us-en/album/title/album-id
        String[] parts = url.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : String.valueOf(url.hashCode());
    }

    /**
     * Parses audio quality from HTML text and maps to Qobuz quality codes
     * Examples:
     * - "16-Bit/44.1 kHz" -> quality code 6
     * - "24-Bit/96 kHz" -> quality code 7
     * - "24-Bit/192 kHz" -> quality code 27
     */
    private List<Integer> parseQualitiesFromHtml(Element releaseCard) {
        List<Integer> qualities = new ArrayList<>();

        try {
            // Find quality text in ReleaseCardQualityText
            Elements qualitySpans = releaseCard.select("div.ReleaseCardQualityText span");

            String bitDepthText = null;
            String sampleRateText = null;

            for (Element span : qualitySpans) {
                String text = span.text().trim();
                // Look for bit depth/sample rate pattern
                if (text.matches(".*\\d+-Bit.*kHz.*") || text.matches(".*\\d+.*kHz.*")) {
                    // Parse something like "16-Bit/44.1 kHz" or "24-Bit/96 kHz"
                    Pattern pattern = Pattern.compile("(\\d+)-Bit.*?([\\d.]+)\\s*kHz");
                    Matcher matcher = pattern.matcher(text);
                    if (matcher.find()) {
                        bitDepthText = matcher.group(1);
                        sampleRateText = matcher.group(2);
                        break;
                    }
                }
            }

            // Map to Qobuz quality codes
            if (bitDepthText != null && sampleRateText != null) {
                int bitDepth = Integer.parseInt(bitDepthText);
                double sampleRate = Double.parseDouble(sampleRateText);

                if (bitDepth == 16 && sampleRate == 44.1) {
                    qualities.add(6); // CD quality
                } else if (bitDepth == 24 && sampleRate >= 96.0 && sampleRate < 192.0) {
                    qualities.add(7); // Hi-Res 24/96
                } else if (bitDepth == 24 && sampleRate >= 192.0) {
                    qualities.add(27); // Maximum quality 24/192
                } else if (bitDepth == 24) {
                    // Generic 24-bit, assume Hi-Res
                    qualities.add(7);
                }
            }

            // If we couldn't determine quality from HTML, assume standard qualities available
            if (qualities.isEmpty()) {
                log.debug("Could not parse quality from HTML, assuming default qualities");
                qualities.addAll(List.of(27, 7, 6, 5)); // All qualities
            }

        } catch (Exception e) {
            log.warn("Error parsing quality from HTML: {}", e.getMessage());
            qualities.addAll(List.of(27, 7, 6, 5)); // Fallback to all qualities
        }

        return qualities;
    }

    private DownloadOption createOptionForAlbum(QobuzSearchResult album) {
        // One album = one quality = one option
        // In Qobuz, each album exists in only one quality
        List<Integer> qualities = album.availableQualities();

        if (qualities.isEmpty()) {
            throw new IllegalStateException("Album has no quality info: " + album.albumId());
        }

        // Take the first (and only) quality
        Integer quality = qualities.get(0);

        return createOption(album, quality);
    }

    private DownloadOption createOption(QobuzSearchResult album, int quality) {
        String optionId = "qobuz-" + album.albumId() + "-q" + quality;
        String qualityLabel = getQualityLabel(quality);
        String displayName = album.artist() + " - " + album.title() + " - " + qualityLabel;

        // No size/length estimation - will be known after download
        int totalSizeMB = 0;

        // Empty file list - track info will be known after download
        List<DownloadOption.FileItem> files = List.of();

        // Technical metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("albumUrl", album.url());
        metadata.put("albumId", album.albumId());
        metadata.put("quality", String.valueOf(quality));
        metadata.put("qualityLabel", qualityLabel);
        metadata.put("artist", album.artist());
        metadata.put("title", album.title());
        metadata.put("releaseDate", album.releaseDate());

        return new DownloadOption(
                optionId,
                DownloadEngine.QOBUZ,
                displayName,
                totalSizeMB,
                files,
                metadata
        );
    }

    @Override
    public String getDownloadPath(DownloadOption option) {
        // qobuz-dl creates folder: "Artist - AlbumTitle (Year) [Quality]" directly in downloadPath
        // We return the base downloadPath and monitor will search for folder matching artist+title
        return downloadPath;
    }

    @Override
    public void handleDownloadCompletion(long chatId, String releaseId, DownloadOption option, String downloadPath) {
        int expectedFileCount = option.files().isEmpty() ? 1 : option.files().size();

        String artist = option.technicalMetadata().get("artist");
        String title = option.technicalMetadata().get("title");

        monitorService.startMonitoring(
                chatId,
                releaseId,
                downloadPath,
                expectedFileCount,
                artist,
                title
        );

        log.info("Started monitoring for Qobuz download: {}", downloadPath);
    }

    @Override
    public void cancelDownload(String releaseId) {
        downloadRegistry.cancel(releaseId);
    }

    public String getQualityLabel(int qualityCode) {
        return switch (qualityCode) {
            case 5 -> "MP3 320kbps";
            case 6 -> "FLAC 16bit/44.1kHz";
            case 7 -> "FLAC 24bit/96kHz (Hi-Res)";
            case 27 -> "FLAC Maximum Quality";
            default -> "Quality " + qualityCode;
        };
    }
}

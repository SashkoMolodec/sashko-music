package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.downloadagent.messaging.producer.DownloadBatchCompleteProducer;
import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadBatchCompleteDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class DownloadMonitorService {

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "flac", "mp3", "wav", "m4a", "aac", "alac", "aiff", "ogg", "wma", "ape"
    );

    private static final Set<String> TEMP_EXTENSIONS = Set.of(
            "part", "tmp", "temp", "download", "crdownload", "partial"
    );

    private final DownloadBatchCompleteProducer batchCompleteProducer;
    private final Map<String, DownloadMonitorTask> activeTasks = new ConcurrentHashMap<>();

    public void startMonitoring(String conversationId, String releaseId, String downloadPath,
                                 int expectedFileCount, String artist, String title) {
        String taskId = releaseId + ":" + conversationId;

        DownloadMonitorTask task = new DownloadMonitorTask(
                conversationId, releaseId, downloadPath, expectedFileCount, artist, title
        );

        activeTasks.put(taskId, task);
        log.info("Started monitoring download: taskId={}, path={}, expectedFiles={}, artist={}, title={}",
                 taskId, downloadPath, expectedFileCount, artist, title);
    }

    public void stopMonitoring(String releaseId) {
        activeTasks.entrySet().removeIf(entry -> {
            if (entry.getValue().releaseId().equals(releaseId)) {
                log.info("Stopped monitoring for releaseId={}", releaseId);
                return true;
            }
            return false;
        });
    }

    @Scheduled(fixedDelay = 3000) // Every 3 seconds
    public void checkDownloads() {
        if (activeTasks.isEmpty()) {
            return;
        }

        log.info("Checking {} active downloads", activeTasks.size());

        activeTasks.entrySet().removeIf(entry -> {
            String taskId = entry.getKey();
            DownloadMonitorTask task = entry.getValue();

            try {
                Path basePath = Path.of(task.downloadPath());

                if (!Files.exists(basePath)) {
                    log.info("Base directory not yet created: {}", basePath);
                    return false; // Keep monitoring
                }

                String artist = task.artist();
                String title = task.title();

                if (artist == null || title == null) {
                    log.warn("Missing artist or title metadata for download");
                    return false;
                }

                Path downloadDir = findAlbumFolder(basePath, artist, title).orElse(null);
                if (downloadDir == null) {
                    log.info("Album folder not yet created for: {} - {}", artist, title);
                    return false;
                }

                List<String> audioFiles = findAudioFiles(downloadDir);
                int currentCount = audioFiles.size();

                log.info("taskId={}, checking: {} audio files", taskId, currentCount);

                // Check for temp files - download still in progress
                if (hasTempFiles(downloadDir)) {
                    log.info("taskId={}, download in progress: found temp files", taskId);
                    task.resetStableChecks();
                    return false; // Keep monitoring
                }

                // Check if files are stable (not changing for 6 seconds)
                if (task.isStable(currentCount)) {
                    log.info("Download complete (stable): taskId={}, files={}", taskId, currentCount);
                    batchCompleteProducer.sendBatchComplete(
                            DownloadBatchCompleteDto.of(
                                    task.conversationId(),
                                    task.releaseId(),
                                    downloadDir.toString(),
                                    audioFiles
                            )
                    );

                    return true; // Remove from active tasks
                }

            } catch (Exception e) {
                log.error("Error checking download taskId={}: {}", taskId, e.getMessage(), e);
            }

            return false; // Keep monitoring
        });
    }

    private List<String> findAudioFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> isAudioFile(path.getFileName().toString()))
                    .map(Path::toString)
                    .toList();
        }
    }

    private boolean isAudioFile(String filename) {
        String lower = filename.toLowerCase();
        return AUDIO_EXTENSIONS.stream().anyMatch(ext -> lower.endsWith("." + ext));
    }

    private boolean hasTempFiles(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .anyMatch(this::isTempFile);
        }
    }

    private boolean isTempFile(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        return TEMP_EXTENSIONS.stream().anyMatch(ext -> filename.endsWith("." + ext));
    }

    /**
     * Normalizes strings for matching by removing special characters, spaces, and converting to lowercase.
     * Examples:
     * - "HYPERREAL EP [SCX021]" -> "hyperrealepscx021"
     * - "hyperreal-ep-scx021" -> "hyperrealepscx021"
     * - "Artist - Album (2024)" -> "artistalbum2024"
     */
    private String normalizeForMatching(String text) {
        return text.toLowerCase()
                .replaceAll("[\\s\\-_\\[\\](){}]", ""); // Remove spaces, dashes, underscores, brackets, parentheses
    }

    /**
     * Finds the album folder by searching for both artist and title in the path.
     * If not found, falls back to searching only by title (for Compilations/Various Artists folders).
     *
     * @param basePath the base download directory to search in
     * @param artist the artist name
     * @param title the album title
     * @return Optional containing the album folder path if found, empty otherwise
     */
    private Optional<Path> findAlbumFolder(Path basePath, String artist, String title) throws IOException {
        String normalizedArtist = normalizeForMatching(artist);
        String normalizedTitle = normalizeForMatching(title);

        log.debug("Searching for normalized artist='{}' and title='{}' (original: '{}', '{}')",
                normalizedArtist, normalizedTitle, artist, title);

        // Step 1: Try matching both artist and title
        try (Stream<Path> subdirs = Files.walk(basePath, 2)) {
            Optional<Path> albumFolder = subdirs
                    .filter(Files::isDirectory)
                    .filter(p -> !p.equals(basePath))
                    .filter(p -> {
                        String fullPath = normalizeForMatching(p.toString());
                        return fullPath.contains(normalizedArtist) &&
                               fullPath.contains(normalizedTitle);
                    })
                    .findFirst();

            if (albumFolder.isPresent()) {
                log.info("Found album folder (artist+title match): {}", albumFolder.get());
                return albumFolder;
            }
        }

        // Step 2: Fallback - try matching only title (for Compilations/Various Artists)
        log.debug("No folder matching both artist='{}' and title='{}', trying title only",
                artist, title);

        try (Stream<Path> subdirs = Files.walk(basePath, 2)) {
            Optional<Path> albumFolder = subdirs
                    .filter(Files::isDirectory)
                    .filter(p -> !p.equals(basePath))
                    .filter(p -> {
                        String fullPath = normalizeForMatching(p.toString());
                        return fullPath.contains(normalizedTitle);
                    })
                    .findFirst();

            if (albumFolder.isPresent()) {
                log.info("Found album folder (title-only match): {}", albumFolder.get());
            }
            return albumFolder;
        }
    }

    public static class DownloadMonitorTask {
        private final String conversationId;
        private final String releaseId;
        private final String downloadPath;
        private final int expectedFileCount;
        private final String artist;
        private final String title;
        private int lastFileCount = -1;
        private int stableChecks = 0;

        public DownloadMonitorTask(String conversationId, String releaseId, String downloadPath,
                                    int expectedFileCount, String artist, String title) {
            this.conversationId = conversationId;
            this.releaseId = releaseId;
            this.downloadPath = downloadPath;
            this.expectedFileCount = expectedFileCount;
            this.artist = artist;
            this.title = title;
        }

        public String conversationId() { return conversationId; }
        public String releaseId() { return releaseId; }
        public String downloadPath() { return downloadPath; }
        public int expectedFileCount() { return expectedFileCount; }
        public String artist() { return artist; }
        public String title() { return title; }

        public void resetStableChecks() {
            stableChecks = 0;
        }

        public boolean isStable(int currentCount) {
            if (currentCount == lastFileCount && currentCount > 0) {
                stableChecks++;
            } else {
                stableChecks = 0;
            }
            lastFileCount = currentCount;
            return stableChecks >= 2; // Stable for 2 checks (6 seconds)
        }
    }
}

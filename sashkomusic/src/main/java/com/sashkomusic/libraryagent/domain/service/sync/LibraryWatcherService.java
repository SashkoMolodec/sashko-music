package com.sashkomusic.libraryagent.domain.service.sync;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class LibraryWatcherService {

    private final TrackTagSyncService syncService;

    @Value("${watch.enabled:true}")
    private boolean watchEnabled;

    @Value("${library.root-path}")
    private String libraryPath;

    private DirectoryWatcher watcher;
    private CompletableFuture<Void> watchFuture;

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "wma", "alac"
    );

    @PostConstruct
    public void startWatching() {
        if (!watchEnabled) {
            log.info("Library file watching is disabled");
            return;
        }

        try {
            Path rootPath = Paths.get(libraryPath);
            log.info("Initializing directory watcher for library: {}", rootPath);

            watcher = DirectoryWatcher.builder()
                    .path(rootPath)
                    .listener(this::handleFileEvent)
                    .fileHashing(false) // Use last modified time instead of file hashing for performance
                    .build();

            // Start watching asynchronously
            watchFuture = watcher.watchAsync();

            log.info("Started watching library directory: {}", libraryPath);
            log.info("Watching for changes in audio files: {}", AUDIO_EXTENSIONS);

        } catch (IOException e) {
            log.error("Failed to start directory watcher: {}", e.getMessage(), e);
            log.warn("Falling back to scheduled sync only");
        }
    }

    /**
     * Handle file system events
     * Only processes MODIFY events for audio files
     */
    private void handleFileEvent(DirectoryChangeEvent event) {
        try {
            Path changedFile = event.path();

            if (event.eventType() != DirectoryChangeEvent.EventType.MODIFY) {
                return;
            }

            if (!isAudioFile(changedFile)) {
                return;
            }

            log.debug("Detected file modification: {}", changedFile.getFileName());

            syncService.syncTrackByPath(changedFile);

        } catch (Exception e) {
            log.error("Error handling file event for {}: {}", event.path(), e.getMessage());
        }
    }

    private boolean isAudioFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        int lastDot = fileName.lastIndexOf('.');

        if (lastDot == -1) {
            return false;
        }

        String extension = fileName.substring(lastDot + 1);
        return AUDIO_EXTENSIONS.contains(extension);
    }

    @PreDestroy
    public void stopWatching() {
        if (watcher != null) {
            try {
                log.info("Stopping directory watcher...");
                watcher.close();

                if (watchFuture != null && !watchFuture.isDone()) {
                    watchFuture.cancel(true);
                }

                log.info("Directory watcher stopped successfully");
            } catch (IOException e) {
                log.error("Error stopping directory watcher: {}", e.getMessage());
            }
        }
    }

    public boolean isWatching() {
        return watcher != null && watchFuture != null && !watchFuture.isDone();
    }
}

package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.LibraryProcessingCompleteEvent;
import com.sashkomusic.events.RemoveReleaseCompleteEvent;
import com.sashkomusic.libraryagent.messaging.producer.dto.LibraryProcessingCompleteDto;
import com.sashkomusic.mainagent.library.client.NavidromeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@Slf4j
@RequiredArgsConstructor
public class NavidromeNotificationService {

    private final NavidromeClient navidromeClient;

    @Value("${library.root-path}")
    private String libraryRootPath;

    @Value("${navidrome.library-path}")
    private String navidromeLibraryPath;

    @EventListener
    @Async
    public void handleLibraryProcessingComplete(LibraryProcessingCompleteEvent event) {
        LibraryProcessingCompleteDto dto = event.payload();
        log.debug("Received library-processing-complete event: conversationId={}, masterId={}, success={}, directoryPath={}",
                dto.conversationId(), dto.masterId(), dto.success(), dto.directoryPath());

        if (!dto.success()) {
            log.debug("Skipping Navidrome scan - library processing was not successful");
            return;
        }

        String directoryPath = dto.directoryPath();
        if (directoryPath == null || directoryPath.isEmpty()) {
            log.warn("Skipping Navidrome scan - directory path is empty");
            return;
        }

        String relativePath = extractRelativePath(directoryPath);
        if (relativePath == null || relativePath.isEmpty()) {
            log.warn("Skipping Navidrome scan - could not extract relative path from: {}", directoryPath);
            return;
        }

        String navidromePath = navidromeLibraryPath.stripTrailing() + "/" + relativePath;
        log.info("Triggering Navidrome scan for: {}", navidromePath);
        navidromeClient.triggerScan(navidromePath);
    }

    /**
     * Navidrome only re-checks a directory when it's scanned. A release/track removal deletes
     * or moves files without telling Navidrome, so without this the library and cover art keep
     * showing/playing removed content until Navidrome's next scheduled scan.
     */
    @EventListener
    @Async
    public void handleReleaseRemoved(RemoveReleaseCompleteEvent event) {
        if (!event.success() || event.directoryPath() == null || event.directoryPath().isEmpty()) {
            return;
        }

        String relativePath = extractRelativePath(event.directoryPath());
        if (relativePath == null || relativePath.isEmpty()) {
            log.warn("Skipping Navidrome scan after removal - could not extract relative path from: {}", event.directoryPath());
            return;
        }

        String navidromePath = navidromeLibraryPath.stripTrailing() + "/" + relativePath;
        log.info("Triggering Navidrome scan after release removal: {}", navidromePath);
        navidromeClient.triggerScan(navidromePath);
    }

    private String extractRelativePath(String fullPath) {
        try {
            Path full = Paths.get(fullPath).toAbsolutePath().normalize();
            Path root = Paths.get(libraryRootPath).toAbsolutePath().normalize();

            if (!full.startsWith(root)) {
                log.warn("Directory path {} is not under library root {}", fullPath, libraryRootPath);
                return null;
            }

            return root.relativize(full).toString();
        } catch (Exception e) {
            log.error("Failed to extract relative path from {}: {}", fullPath, e.getMessage(), e);
            return null;
        }
    }
}

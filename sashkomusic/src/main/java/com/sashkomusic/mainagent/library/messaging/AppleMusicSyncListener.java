package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.LibraryProcessingCompleteEvent;
import com.sashkomusic.mainagent.library.AppleMusicSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Slf4j
@RequiredArgsConstructor
public class AppleMusicSyncListener {

    private final AppleMusicSyncService syncService;

    @EventListener
    @Async("asyncExecutor")
    public void handleLibraryProcessingComplete(LibraryProcessingCompleteEvent event) {
        if (!event.success()) {
            log.debug("Skipping Apple Music sync - library processing was not successful");
            return;
        }

        String directoryPath = event.directoryPath();
        if (directoryPath == null || directoryPath.isEmpty()) {
            log.warn("Skipping Apple Music sync - directory path is empty");
            return;
        }

        Path folder = Path.of(directoryPath).getFileName();
        var submission = syncService.submit(event.conversationId(), directoryPath,
                folder != null ? folder.toString() : directoryPath);
        log.info("Apple Music sync after processing {}: {}", directoryPath, submission);
    }
}

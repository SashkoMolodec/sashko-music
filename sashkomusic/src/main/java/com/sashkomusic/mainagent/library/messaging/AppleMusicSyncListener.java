package com.sashkomusic.mainagent.library.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sashkomusic.downloadagent.infrastructure.process.ProcessCommandExecutor;
import com.sashkomusic.events.AppleMusicSyncCompleteEvent;
import com.sashkomusic.events.LibraryProcessingCompleteEvent;
import com.sashkomusic.libraryagent.config.AppleMusicSyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class AppleMusicSyncListener {

    private final ProcessCommandExecutor commandExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final AppleMusicSyncConfig config;

    @EventListener
    @Async("asyncExecutor")
    public void handleLibraryProcessingComplete(LibraryProcessingCompleteEvent event) {
        if (!config.isEnabled()) {
            return;
        }
        if (!event.success()) {
            log.debug("Skipping Apple Music sync - library processing was not successful");
            return;
        }

        String directoryPath = event.directoryPath();
        if (directoryPath == null || directoryPath.isEmpty()) {
            log.warn("Skipping Apple Music sync - directory path is empty");
            return;
        }

        log.info("Triggering Apple Music sync for: {}", directoryPath);
        try {
            String output = commandExecutor.executeCapturing("applemusic-sync",
                    "python3", config.getScriptPath(), directoryPath,
                    "--host", config.getHost(), "--port", String.valueOf(config.getPort()));
            publishDbidMappings(directoryPath, output);
        } catch (Exception e) {
            log.error("Apple Music sync failed for {}: {}", directoryPath, e.getMessage(), e);
        }
    }

    private void publishDbidMappings(String directoryPath, String output) {
        List<AppleMusicSyncCompleteEvent.TrackDbid> mappings = AppleMusicSyncOutputParser.parse(objectMapper, output);
        if (!mappings.isEmpty()) {
            eventPublisher.publishEvent(new AppleMusicSyncCompleteEvent(directoryPath, mappings));
        }
    }
}

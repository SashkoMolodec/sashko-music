package com.sashkomusic.mainagent.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sashkomusic.downloadagent.infrastructure.process.ProcessCommandExecutor;
import com.sashkomusic.events.AppleMusicSyncCompleteEvent;
import com.sashkomusic.events.AppleMusicSyncFinishedEvent;
import com.sashkomusic.libraryagent.config.AppleMusicSyncConfig;
import com.sashkomusic.mainagent.library.messaging.AppleMusicSyncOutputParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Starts Apple Music syncs in the background and reports each one back as an
 * {@link AppleMusicSyncFinishedEvent}. Both triggers — the post-/process listener and the 🍎
 * button — go through here, so neither blocks its caller (the button used to hold the Telegram
 * polling thread for the whole run) and the same release can never be synced twice at once
 * (iTunes would happily create duplicate tracks).
 */
@Slf4j
@Service
public class AppleMusicSyncService {

    public enum Submission {STARTED, ALREADY_RUNNING, QUEUE_FULL, DISABLED}

    private final ProcessCommandExecutor commandExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final AppleMusicSyncConfig config;
    private final Executor executor;

    /** Directories queued or running; released only once the run has fully finished. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public AppleMusicSyncService(ProcessCommandExecutor commandExecutor,
                                 ApplicationEventPublisher eventPublisher,
                                 ObjectMapper objectMapper,
                                 AppleMusicSyncConfig config,
                                 @Qualifier("appleMusicSyncExecutor") Executor executor) {
        this.commandExecutor = commandExecutor;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.config = config;
        this.executor = executor;
    }

    /**
     * @param conversationId where to report the result; null runs the sync silently (log only)
     * @param label          human name of the release for the notification
     */
    public Submission submit(String conversationId, String directoryPath, String label) {
        if (!config.isEnabled()) {
            return Submission.DISABLED;
        }
        if (!inFlight.add(directoryPath)) {
            log.info("Apple Music sync already queued/running for {}", directoryPath);
            return Submission.ALREADY_RUNNING;
        }
        try {
            executor.execute(() -> run(conversationId, directoryPath, label));
            return Submission.STARTED;
        } catch (RejectedExecutionException e) {
            inFlight.remove(directoryPath);
            log.warn("Apple Music sync queue is full, rejected {}", directoryPath);
            return Submission.QUEUE_FULL;
        }
    }

    private void run(String conversationId, String directoryPath, String label) {
        int staged = 0;
        int added = 0;
        String error = null;
        try {
            log.info("Apple Music sync started: {}", directoryPath);
            var result = commandExecutor.runCapturing("applemusic-sync", config.getTimeoutSeconds(),
                    "python3", config.getScriptPath(), directoryPath,
                    "--host", config.getHost(), "--port", String.valueOf(config.getPort()));

            var summary = AppleMusicSyncOutputParser.parseSummary(objectMapper, result.stdout());
            if (summary != null) {
                staged = summary.staged();
                added = summary.added();
            }
            publishDbidMappings(directoryPath, result.stdout());

            if (result.timedOut()) {
                error = "таймаут " + config.getTimeoutSeconds() + "с, скрипт перервано";
            } else if (summary == null) {
                error = "скрипт не повернув результат (код " + result.exitCode() + ")";
            }
        } catch (Exception e) {
            log.error("Apple Music sync failed for {}: {}", directoryPath, e.getMessage(), e);
            error = e.getMessage();
        } finally {
            inFlight.remove(directoryPath);
        }

        log.info("Apple Music sync finished: {} — {}/{} added, error={}", directoryPath, added, staged, error);
        if (conversationId != null) {
            eventPublisher.publishEvent(new AppleMusicSyncFinishedEvent(conversationId, label, staged, added, error));
        }
    }

    private void publishDbidMappings(String directoryPath, String output) {
        List<AppleMusicSyncCompleteEvent.TrackDbid> mappings = AppleMusicSyncOutputParser.parse(objectMapper, output);
        if (!mappings.isEmpty()) {
            eventPublisher.publishEvent(new AppleMusicSyncCompleteEvent(directoryPath, mappings));
        }
    }
}

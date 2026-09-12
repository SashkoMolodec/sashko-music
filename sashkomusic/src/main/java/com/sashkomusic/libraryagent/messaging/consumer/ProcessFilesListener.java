package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.ProcessLibraryTaskEvent;
import com.sashkomusic.libraryagent.domain.service.processFolder.LibraryProcessingService;
import com.sashkomusic.shared.task.ProcessLibraryTask;
import com.sashkomusic.events.LibraryProcessingCompleteEvent;
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
public class ProcessFilesListener {

    private final LibraryProcessingService processingService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    @Async("asyncExecutor")
    public void handleProcessFilesTask(ProcessLibraryTaskEvent event) {
        ProcessLibraryTask task = event.payload();
        log.info("Received library processing task: conversationId={}, masterId={}, files={}",
                task.conversationId(), task.metadata().masterId(), task.downloadedFiles().size());

        try {
            LibraryProcessingService.ProcessingResult result = processingService.processLibrary(task);

            eventPublisher.publishEvent(new LibraryProcessingCompleteEvent(
                    task.conversationId(), task.metadata().masterId(), result.directoryPath(),
                    result.processedFiles(), result.success(), result.message(), result.errors()));
            log.info("Library processing completed: success={}, directoryPath={}, processedFiles={}",
                    result.success(), result.directoryPath(), result.processedFiles().size());

        } catch (Exception ex) {
            log.error("Fatal error processing library task: {}", ex.getMessage(), ex);

            eventPublisher.publishEvent(new LibraryProcessingCompleteEvent(
                    task.conversationId(), task.metadata().masterId(), task.directoryPath(),
                    List.of(), false, "Fatal error: " + ex.getMessage(), List.of(ex.getMessage())));
        }
    }
}

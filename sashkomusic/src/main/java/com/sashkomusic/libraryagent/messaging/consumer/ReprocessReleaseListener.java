package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.ReprocessReleaseTaskEvent;
import com.sashkomusic.libraryagent.domain.service.processFolder.ReprocessingService;
import com.sashkomusic.libraryagent.messaging.producer.ReprocessReleaseResultProducer;
import com.sashkomusic.libraryagent.messaging.producer.dto.ReprocessReleaseResultDto;
import com.sashkomusic.mainagent.process.messaging.dto.ReprocessReleaseTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReprocessReleaseListener {

    private final ReprocessingService reprocessingService;
    private final ReprocessReleaseResultProducer resultProducer;

    @EventListener
    @Async
    public void handleReprocessTask(ReprocessReleaseTaskEvent event) {
        ReprocessReleaseTaskDto task = event.payload();
        log.info("Received reprocess task: chatId={}, directoryPath={}, version={}, options={}",
                task.chatId(), task.directoryPath(), task.newMetadataVersion(), task.options());

        try {
            ReprocessingService.ReprocessResult result = reprocessingService.reprocess(
                    task.directoryPath(), task.metadata(), task.newMetadataVersion(), task.options()
            );

            ReprocessReleaseResultDto resultDto = new ReprocessReleaseResultDto(
                    task.chatId(), task.directoryPath(), result.success(), result.message(),
                    result.filesProcessed(), result.errors()
            );
            resultProducer.send(resultDto);
            log.info("Reprocessing completed: success={}, filesProcessed={}, errors={}",
                    result.success(), result.filesProcessed(), result.errors());

        } catch (Exception ex) {
            log.error("Fatal error during reprocessing: {}", ex.getMessage(), ex);
            ReprocessReleaseResultDto errorDto = new ReprocessReleaseResultDto(
                    task.chatId(), task.directoryPath(), false, "Fatal error: " + ex.getMessage(), 0, 1
            );
            resultProducer.send(errorDto);
        }
    }
}

package com.sashkomusic.downloadagent.messaging.consumer;

import com.sashkomusic.downloadagent.domain.DownloadService;
import com.sashkomusic.events.DownloadCancelTaskEvent;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadCancelTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadCancelListener {

    private final DownloadService downloadService;

    @EventListener
    @Async
    public void handleCancelTask(DownloadCancelTaskEvent event) {
        DownloadCancelTaskDto dto = event.payload();
        log.info("Received cancel download task: chatId={}, releaseId={}", dto.chatId(), dto.releaseId());
        downloadService.cancelDownload(dto.chatId(), dto.releaseId());
    }
}

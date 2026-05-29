package com.sashkomusic.downloadagent.messaging.consumer;

import com.sashkomusic.downloadagent.domain.DownloadService;
import com.sashkomusic.events.FilesDownloadTaskEvent;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadFilesTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadTaskListener {

    private final DownloadService downloadService;

    @EventListener
    @Async
    public void handleDownloadTask(FilesDownloadTaskEvent event) {
        DownloadFilesTaskDto dto = event.payload();
        log.info("Received download task: chatId={}, releaseId={}", dto.chatId(), dto.releaseId());
        downloadService.download(dto);
    }
}

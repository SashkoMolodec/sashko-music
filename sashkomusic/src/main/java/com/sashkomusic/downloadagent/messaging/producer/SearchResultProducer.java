package com.sashkomusic.downloadagent.messaging.producer;

import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import com.sashkomusic.downloadagent.messaging.producer.dto.SearchFilesResultDto;
import com.sashkomusic.events.FileSearchResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchResultProducer {

    private final ApplicationEventPublisher eventPublisher;

    public void sendResults(long chatId, String releaseId, DownloadEngine source, List<DownloadOption> results, boolean autoDownload) {
        log.info("Sending {} results from {} back to chat {} (autoDownload={})", results.size(), source, chatId, autoDownload);
        SearchFilesResultDto dto = new SearchFilesResultDto(chatId, releaseId, source, results, autoDownload);
        eventPublisher.publishEvent(new FileSearchResultEvent(dto));
    }
}

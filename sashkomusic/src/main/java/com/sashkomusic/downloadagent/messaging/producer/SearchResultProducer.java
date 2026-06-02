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

    public void sendResults(String conversationId, String releaseId, DownloadEngine source, List<DownloadOption> results) {
        log.info("Sending {} results from {} back to conversationId={}", results.size(), source, conversationId);
        SearchFilesResultDto dto = new SearchFilesResultDto(conversationId, releaseId, source, results);
        eventPublisher.publishEvent(new FileSearchResultEvent(dto));
    }
}

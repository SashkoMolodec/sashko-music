package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.shared.download.DownloadEngine;
import com.sashkomusic.shared.download.DownloadOption;
import com.sashkomusic.shared.task.SearchFilesTask;
import com.sashkomusic.events.FileSearchResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AcquisitionService {

    private final Map<DownloadEngine, MusicSourcePort> musicSources;
    private final ApplicationEventPublisher eventPublisher;

    public void search(SearchFilesTask task) {
        String artist = task.artist();
        String title = task.title();

        log.info("Starting music search: artist='{}', title='{}', source={}, releaseId={}", artist, title, task.source(), task.releaseId());
        MusicSourcePort source = musicSources.get(task.source());

        List<DownloadOption> results = source.search(artist, title, task.conversationId());

        log.info("Sending {} results from {} back to conversationId={}", results.size(), task.source(), task.conversationId());
        eventPublisher.publishEvent(new FileSearchResultEvent(
                task.conversationId(), task.releaseId(), task.source(), results));
    }
}


package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import com.sashkomusic.mainagent.download.messaging.dto.SearchFilesTaskDto;
import com.sashkomusic.downloadagent.messaging.producer.SearchResultProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AcquisitionService {

    private final Map<DownloadEngine, MusicSourcePort> musicSources;
    private final SearchResultProducer searchResultProducer;

    public void search(SearchFilesTaskDto task) {
        String artist = task.artist();
        String title = task.title();

        log.info("Starting music search: artist='{}', title='{}', source={}, releaseId={}", artist, title, task.source(), task.releaseId());
        MusicSourcePort source = musicSources.get(task.source());

        List<DownloadOption> results = source.search(artist, title);

        searchResultProducer.sendResults(task.conversationId(), task.releaseId(), task.source(), results);
    }
}


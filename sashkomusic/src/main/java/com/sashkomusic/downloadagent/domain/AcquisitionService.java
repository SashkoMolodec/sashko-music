package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import com.sashkomusic.downloadagent.domain.util.SearchMatchingUtil;
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

        boolean autoDownload = source.autoDownloadEnabled() && hasAutoDownloadOption(artist, title, results);
        searchResultProducer.sendResults(task.chatId(), task.releaseId(), task.source(), results, autoDownload);
    }

    private boolean hasAutoDownloadOption(String artist, String title, List<DownloadOption> results) {
        List<DownloadOption> matchingResults = results.stream()
                .filter(option -> matchesSearchQuery(option, artist, title))
                .toList();

        log.info("Found {} matching results after filtering", matchingResults.size());
        return matchingResults.size() == 1;
    }

    private boolean matchesSearchQuery(DownloadOption option, String searchArtist, String searchTitle) {
        String resultArtist = extractArtist(option);
        String resultTitle = extractTitle(option);

        boolean matches = SearchMatchingUtil.matches(searchArtist, searchTitle, resultArtist, resultTitle);

        log.info("Match check: searchArtist='{}', searchTitle='{}' vs resultArtist='{}', resultTitle='{}' (displayName: '{}', metadata: {}) → {}",
                searchArtist, searchTitle, resultArtist, resultTitle, option.displayName(), option.technicalMetadata(), matches);

        return matches;
    }

    private String extractArtist(DownloadOption option) {
        String artist = option.technicalMetadata().getOrDefault("artist", "");
        if (artist.isBlank()) {
            String[] parts = option.displayName().split(" - ", 2);
            if (parts.length >= 2) {
                artist = parts[0].trim();
            }
        }
        return artist;
    }

    private String extractTitle(DownloadOption option) {
        String title = option.technicalMetadata().getOrDefault("title",
                option.technicalMetadata().getOrDefault("albumName", ""));

        if (title.isBlank()) {
            String[] parts = option.displayName().split(" - ", 2);
            if (parts.length >= 2) {
                title = parts[1].trim();
            } else {
                title = option.displayName();
            }
        }
        return title;
    }
}


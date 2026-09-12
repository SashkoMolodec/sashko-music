package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.downloadagent.domain.exception.MusicDownloadException;
import com.sashkomusic.events.DownloadErrorEvent;
import com.sashkomusic.events.FileSearchResultEvent;
import com.sashkomusic.shared.download.DownloadEngine;
import com.sashkomusic.shared.download.DownloadOption;
import com.sashkomusic.shared.task.DownloadFilesTask;
import com.sashkomusic.shared.task.SearchFilesTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * downloadagent publishes its results by injecting ApplicationEventPublisher directly —
 * there are no *Producer beans. These cases pin that wiring.
 */
@SpringJUnitConfig
@RecordApplicationEvents
@Import({AcquisitionService.class, DownloadService.class, DownloadEventPublishingTest.TestConfig.class})
class DownloadEventPublishingTest {

    private static final String CONVERSATION_ID = "-100:7";

    @Autowired AcquisitionService acquisitionService;
    @Autowired DownloadService downloadService;
    @Autowired MusicSourcePort soulseek;
    @Autowired ApplicationEvents events;

    private static DownloadOption option() {
        return new DownloadOption("opt-1", DownloadEngine.SOULSEEK, "user - album", 42,
                List.of(new DownloadOption.FileItem("music\\a.flac", 1024L, null, null, null, 300)),
                Map.of());
    }

    @Test
    void search_publishes_results_back_to_the_originating_conversation() {
        when(soulseek.search("Burial", "Untrue", CONVERSATION_ID)).thenReturn(List.of(option()));

        acquisitionService.search(new SearchFilesTask(CONVERSATION_ID, "rel-1", "Burial", "Untrue",
                DownloadEngine.SOULSEEK));

        var published = events.stream(FileSearchResultEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(published.getFirst().releaseId()).isEqualTo("rel-1");
        assertThat(published.getFirst().source()).isEqualTo(DownloadEngine.SOULSEEK);
        assertThat(published.getFirst().results()).hasSize(1);
        assertThat(published.getFirst().chatId()).isEqualTo(-100L);
    }

    @Test
    void a_failed_download_publishes_an_error_event_instead_of_throwing() {
        when(soulseek.initiateDownload(any(), anyString(), anyString()))
                .thenThrow(new MusicDownloadException("slskd unreachable"));

        downloadService.download(new DownloadFilesTask(CONVERSATION_ID, "rel-1", option()));

        var published = events.stream(DownloadErrorEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(published.getFirst().errorMessage()).contains("slskd unreachable");
    }

    @Configuration
    static class TestConfig {
        @Bean MusicSourcePort soulseek() { return mock(MusicSourcePort.class); }

        @Bean Map<DownloadEngine, MusicSourcePort> musicSources(MusicSourcePort soulseek) {
            return Map.of(DownloadEngine.SOULSEEK, soulseek);
        }

        @Bean DownloadContext downloadContext() { return mock(DownloadContext.class); }
    }
}

package com.sashkomusic.mainagent.download;

import com.sashkomusic.events.FilesSearchTaskEvent;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.CallbackDispatcher;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.download.messaging.DownloadCancelTaskProducer;
import com.sashkomusic.mainagent.download.messaging.DownloadTaskProducer;
import com.sashkomusic.mainagent.download.messaging.SearchFilesTaskProducer;
import com.sashkomusic.mainagent.library.DjTagFlowService;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.bot.state.InMemoryChatStateStore;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.streaming.StreamingFlowService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@RecordApplicationEvents
@Import({CallbackDispatcher.class, MusicDownloadFlowService.class,
        SearchFilesTaskProducer.class, DownloadTaskProducer.class, DownloadCancelTaskProducer.class,
        SearchContextService.class, DownloadContextHolder.class,
        DownloadFlowIntegrationTest.TestConfig.class})
class DownloadFlowIntegrationTest {

    private static final long CHAT_ID = 7L;
    private static final ConversationContext CTX = ConversationContext.dm(CHAT_ID);
    private static final String RELEASE_ID = "burial-untrue-id";

    @Autowired CallbackDispatcher dispatcher;
    @Autowired SearchContextService searchContext;
    @Autowired DownloadContextHolder downloadContext;
    @Autowired ApplicationEvents events;

    @BeforeEach
    void seed() {
        searchContext.clearAllCaches();
        downloadContext.clearAllSessions();
        ReleaseMetadata release = new ReleaseMetadata(
                RELEASE_ID, null, SearchEngine.MUSICBRAINZ,
                "Burial", "Untrue", 100,
                List.of("2007"), List.of("Album"), 13, 13, 1,
                List.of(), null, List.of(), null);
        searchContext.saveSearchContext(CTX.conversationId(), SearchEngine.MUSICBRAINZ, "burial untrue",
                null, List.of(release));
    }

    @Test
    void DL_callback_publishes_FilesSearchTaskEvent_with_qobuz_default() {
        List<BotResponse> resp = dispatcher.dispatch(CTX, "DL:" + RELEASE_ID, null);

        assertThat(resp).hasSize(1);
        assertThat(resp.get(0).text()).contains("шукаю опції завантаження");

        var published = events.stream(FilesSearchTaskEvent.class).toList();
        assertThat(published).hasSize(1);
        var payload = published.get(0).payload();
        assertThat(payload.chatId()).isEqualTo(CHAT_ID);
        assertThat(payload.releaseId()).isEqualTo(RELEASE_ID);
        assertThat(payload.artist()).isEqualTo("Burial");
        assertThat(payload.title()).isEqualTo("Untrue");
        assertThat(payload.source()).isEqualTo(DownloadEngine.QOBUZ);
    }

    @Test
    void DL_callback_with_unknown_releaseId_publishes_no_event() {
        List<BotResponse> resp = dispatcher.dispatch(CTX, "DL:does-not-exist", null);

        assertThat(resp).hasSize(1);
        assertThat(resp.get(0).text()).contains("не получило");
        assertThat(events.stream(FilesSearchTaskEvent.class)).isEmpty();
    }

    @Configuration
    static class TestConfig {
        @Bean ChatStateStore chatStateStore() { return new InMemoryChatStateStore(); }
        @Bean Map<SearchEngine, SearchEngineService> searchEngines() { return Map.of(); }
        @Bean Map<DownloadEngine, DownloadFlowHandler> downloadHandlers() { return Map.of(); }
        @Bean ReleaseSearchFlowService releaseSearchFlowService() {
            return mock(ReleaseSearchFlowService.class);
        }
        @Bean StreamingFlowService streamingFlowService() { return mock(StreamingFlowService.class); }
        @Bean NowPlayingFlowService nowPlayingFlowService() { return mock(NowPlayingFlowService.class); }
        @Bean DjTagFlowService djTagFlowService() {
            DjTagFlowService m = mock(DjTagFlowService.class);
            when(m.isWaitingForComment(any())).thenReturn(false);
            return m;
        }
        @Bean com.sashkomusic.mainagent.library.RemoveReleaseFlowService removeReleaseFlowService() {
            return mock(com.sashkomusic.mainagent.library.RemoveReleaseFlowService.class);
        }
        @Bean com.sashkomusic.mainagent.library.SublibraryAssignmentHandler sublibraryAssignmentHandler() {
            return mock(com.sashkomusic.mainagent.library.SublibraryAssignmentHandler.class);
        }
        @Bean com.sashkomusic.mainagent.process.PendingProcessCallbackHandler pendingProcessCallbackHandler() {
            return mock(com.sashkomusic.mainagent.process.PendingProcessCallbackHandler.class);
        }
        @Bean com.sashkomusic.mainagent.process.ProcessFolderFlowService processFolderFlowService() {
            return mock(com.sashkomusic.mainagent.process.ProcessFolderFlowService.class);
        }
    }
}

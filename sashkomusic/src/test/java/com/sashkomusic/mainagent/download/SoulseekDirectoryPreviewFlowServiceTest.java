package com.sashkomusic.mainagent.download;

import com.sashkomusic.shared.download.DownloadEngine;
import com.sashkomusic.shared.download.DownloadOption;
import com.sashkomusic.downloadagent.domain.SoulseekDirectoryService;
import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.CallbackDispatcher;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.DjTagFlowService;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.bot.state.InMemoryChatStateStore;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.streaming.StreamingFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the production bug where a second, unrelated Soulseek directory preview
 * overwrote the shared per-conversation confirm slot before the user's click on the first card was
 * processed, causing the wrong directory to download. The fix: SLSK_DIR_OK/SEL/NO callback data now
 * carries a per-preview token that must match what's currently stored before the click is honored.
 */
@SpringJUnitConfig
@RecordApplicationEvents
@Import({CallbackDispatcher.class, SoulseekDirectoryPreviewFlowService.class,
        SoulseekDirectoryConfirmContextHolder.class, DownloadContextHolder.class,
        SoulseekDirectoryPreviewFlowServiceTest.TestConfig.class})
class SoulseekDirectoryPreviewFlowServiceTest {

    private static final long CHAT_ID = 42L;
    private static final ConversationContext CTX = ConversationContext.dm(CHAT_ID);
    private static final String RELEASE_ID = "release-1";

    @Autowired CallbackDispatcher dispatcher;
    @Autowired SoulseekDirectoryPreviewFlowService previewFlow;
    @Autowired SoulseekDirectoryConfirmContextHolder confirmHolder;
    @Autowired SoulseekDirectoryService directoryService;
    @Autowired ApplicationEvents events;

    private DownloadOption original;

    @BeforeEach
    void seed() {
        confirmHolder.clear(CTX.conversationId());
        original = new DownloadOption("peerA", DownloadEngine.SOULSEEK, "Artist - Album", 10,
                List.of(new DownloadOption.FileItem("Album\\01.flac", 1024L, null, 16, 44100, 200)),
                Map.of("username", "peerA", "albumFolder", "Album"));
        when(directoryService.fetchExpandedOption(any())).thenReturn(original);
    }

    private String extractToken(BotResponse response, String prefix) {
        return response.buttonRows().get(0).stream()
                .filter(b -> b.callbackData().startsWith(prefix))
                .findFirst()
                .orElseThrow()
                .callbackData()
                .substring(prefix.length());
    }

    @Test
    void matching_token_confirms_and_downloads_the_previewed_directory() {
        BotResponse card = previewFlow.fetchAndShowPreview(CTX, RELEASE_ID, original).get(0);
        String token = extractToken(card, "SLSK_DIR_OK:");

        List<BotResponse> resp = dispatcher.dispatch(CTX, "SLSK_DIR_OK:" + token, null);

        assertThat(resp.get(0).text()).contains("ок, качаю");
        assertThat(confirmHolder.get(CTX.conversationId())).isEmpty();
    }

    @Test
    void stale_token_is_rejected_when_a_second_preview_overwrote_the_slot() {
        BotResponse firstCard = previewFlow.fetchAndShowPreview(CTX, RELEASE_ID, original).get(0);
        String staleToken = extractToken(firstCard, "SLSK_DIR_OK:");

        // A second, unrelated preview lands in the same conversation before the user clicks the first.
        DownloadOption other = new DownloadOption("peerB", DownloadEngine.SOULSEEK, "Other - Release", 50000,
                List.of(), Map.of("username", "peerB", "albumFolder", "Other"));
        when(directoryService.fetchExpandedOption(any())).thenReturn(other);
        previewFlow.fetchAndShowPreview(CTX, "release-2", original);

        List<BotResponse> resp = dispatcher.dispatch(CTX, "SLSK_DIR_OK:" + staleToken, null);

        assertThat(resp.get(0).text()).contains("протухло");
        // The still-pending (second) preview must remain untouched — not silently downloaded.
        assertThat(confirmHolder.get(CTX.conversationId())).isPresent();
    }

    @Test
    void matching_token_cancels_and_clears_state() {
        BotResponse card = previewFlow.fetchAndShowPreview(CTX, RELEASE_ID, original).get(0);
        String token = extractToken(card, "SLSK_DIR_NO:");

        List<BotResponse> resp = dispatcher.dispatch(CTX, "SLSK_DIR_NO:" + token, null);

        assertThat(resp.get(0).text()).contains("скасовано");
        assertThat(confirmHolder.get(CTX.conversationId())).isEmpty();
    }

    @Test
    void stale_cancel_token_does_not_clear_current_pending_state() {
        BotResponse firstCard = previewFlow.fetchAndShowPreview(CTX, RELEASE_ID, original).get(0);
        String staleToken = extractToken(firstCard, "SLSK_DIR_NO:");
        previewFlow.fetchAndShowPreview(CTX, "release-2", original);

        List<BotResponse> resp = dispatcher.dispatch(CTX, "SLSK_DIR_NO:" + staleToken, null);

        assertThat(resp.get(0).text()).contains("протухло");
        assertThat(confirmHolder.get(CTX.conversationId())).isPresent();
    }

    @Test
    void hard_reset_clears_pending_confirmation() {
        previewFlow.fetchAndShowPreview(CTX, RELEASE_ID, original);
        assertThat(confirmHolder.get(CTX.conversationId())).isPresent();

        confirmHolder.onHardReset(new ChatHardResetEvent(CTX.conversationId()));

        assertThat(confirmHolder.get(CTX.conversationId())).isEmpty();
    }

    @Configuration
    static class TestConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            var props = new java.util.Properties();
            props.setProperty("telegram.default-chat-id", String.valueOf(CHAT_ID));
            props.setProperty("telegram.download-topic-id", "");
            var configurer = new PropertySourcesPlaceholderConfigurer();
            configurer.setProperties(props);
            return configurer;
        }
        @Bean ChatStateStore chatStateStore() { return new InMemoryChatStateStore(); }
        @Bean SoulseekDirectoryService soulseekDirectoryService() { return mock(SoulseekDirectoryService.class); }
        @Bean MusicDownloadFlowService musicDownloadFlowService() { return mock(MusicDownloadFlowService.class); }
        @Bean SoulseekCustomSearchFlowService soulseekCustomSearchFlowService() { return mock(SoulseekCustomSearchFlowService.class); }
        @Bean com.sashkomusic.mainagent.process.ProcessFolderFlowService processFolderFlowService() {
            return mock(com.sashkomusic.mainagent.process.ProcessFolderFlowService.class);
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
        @Bean com.sashkomusic.mainagent.library.SmartlistCreationFlowService smartlistCreationFlowService() {
            return mock(com.sashkomusic.mainagent.library.SmartlistCreationFlowService.class);
        }
        @Bean com.sashkomusic.mainagent.library.SmartlistsFlowService smartlistsFlowService() {
            return mock(com.sashkomusic.mainagent.library.SmartlistsFlowService.class);
        }
        @Bean com.sashkomusic.mainagent.library.NowPlayingAlbumFlowService nowPlayingAlbumFlowService() {
            return mock(com.sashkomusic.mainagent.library.NowPlayingAlbumFlowService.class);
        }
        @Bean com.sashkomusic.mainagent.library.SmartlistLabelFlowService smartlistLabelFlowService() {
            return mock(com.sashkomusic.mainagent.library.SmartlistLabelFlowService.class);
        }
        @Bean com.sashkomusic.mainagent.library.MarkersFlowService markersFlowService() {
            return mock(com.sashkomusic.mainagent.library.MarkersFlowService.class);
        }
        @Bean ReleaseSearchFlowService releaseSearchFlowService() {
            return mock(ReleaseSearchFlowService.class);
        }
    }
}

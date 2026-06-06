package com.sashkomusic.mainagent.library;

import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.api.service.TrackService;
import com.sashkomusic.events.RateTrackTaskEvent;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.bot.state.InMemoryChatStateStore;
import com.sashkomusic.mainagent.library.client.IcecastClient;
import com.sashkomusic.mainagent.library.client.NavidromeClient;
import com.sashkomusic.mainagent.library.config.IcecastConfig;
import com.sashkomusic.mainagent.library.messaging.RateTrackTaskProducer;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@RecordApplicationEvents
@Import({NowPlayingFlowService.class, RateTrackTaskProducer.class,
        DjTagContextHolder.class, LibraryFlowIntegrationTest.TestConfig.class})
class LibraryFlowIntegrationTest {

    private static final long CHAT_ID = 99L;
    private static final ConversationContext CTX = ConversationContext.dm(CHAT_ID);

    @Autowired NowPlayingFlowService nowPlaying;
    @Autowired NavidromeClient navidromeClient;
    @Autowired TrackService trackService;
    @Autowired DjTagContextHolder djTagContextHolder;
    @Autowired ApplicationEvents events;

    @BeforeEach
    void reset() {
        djTagContextHolder.clearAllContexts();
    }

    @Test
    void nowPlaying_returns_rating_buttons_when_track_known_in_db() {
        when(navidromeClient.getCurrentlyPlayingTrackInfo())
                .thenReturn(new NavidromeClient.CurrentTrackInfo("nav-1", "Burial", "Untrue"));
        when(trackService.findByArtistAndTitleOptional("Burial", "Untrue"))
                .thenReturn(Optional.of(TrackDto.of(42L, "/lib/u.flac", "Untrue", "Burial",
                        null, null, null, null)));

        List<BotResponse> resp = nowPlaying.nowPlaying(CTX);

        assertThat(resp).hasSize(1);
        assertThat(resp.get(0).text()).contains("burial").contains("untrue");
        var rows = resp.get(0).buttonRows();
        assertThat(rows).isNotNull().isNotEmpty();
        var allLabels = rows.stream().flatMap(List::stream).map(BotResponse.ButtonDto::label).toList();
        assertThat(allLabels).contains("⭐ 1", "⭐ 5");
        assertThat(djTagContextHolder.getContext(CTX.conversationId())).isNotNull();
        assertThat(djTagContextHolder.getContext(CTX.conversationId()).trackId()).isEqualTo(42L);
    }

    @Test
    void nowPlaying_handles_missing_track() {
        when(navidromeClient.getCurrentlyPlayingTrackInfo()).thenReturn(null);

        List<BotResponse> resp = nowPlaying.nowPlaying(CTX);

        assertThat(resp).hasSize(1);
        assertThat(resp.get(0).text()).contains("нич не грає");
    }

    @Test
    void RATE_callback_publishes_event_and_sets_navidrome_rating() {
        when(navidromeClient.getCurrentlyPlayingTrackInfo())
                .thenReturn(new NavidromeClient.CurrentTrackInfo("nav-1", "Burial", "Untrue"));
        when(trackService.findByArtistAndTitleOptional("Burial", "Untrue"))
                .thenReturn(Optional.of(TrackDto.of(42L, "/lib/u.flac", "Untrue", "Burial",
                        null, null, null, null)));
        nowPlaying.nowPlaying(CTX);

        nowPlaying.handleRate(CTX, "RATE:42:5:nav-1");

        verify(navidromeClient).setRating(eq("nav-1"), eq(5));
        var published = events.stream(RateTrackTaskEvent.class).toList();
        assertThat(published).hasSize(1);
        var payload = published.get(0).payload();
        assertThat(payload.trackId()).isEqualTo(42L);
        assertThat(payload.rating()).isEqualTo(5);
        assertThat(payload.chatId()).isEqualTo(CHAT_ID);
    }

    @Configuration
    static class TestConfig {
        @Bean NavidromeClient navidromeClient() { return mock(NavidromeClient.class); }
        @Bean IcecastClient icecastClient() { return mock(IcecastClient.class); }
        @Bean IcecastConfig icecastConfig() {
            IcecastConfig c = new IcecastConfig();
            c.setEnabled(false);
            return c;
        }
        @Bean TrackService trackService() { return mock(TrackService.class); }
        @Bean ChatStateStore chatStateStore() { return new InMemoryChatStateStore(); }
    }
}

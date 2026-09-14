package com.sashkomusic.mainagent.library;

import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.api.service.TrackService;
import com.sashkomusic.events.RateTrackTaskEvent;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Tag;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.bot.state.InMemoryChatStateStore;
import com.sashkomusic.mainagent.library.client.IcecastClient;
import com.sashkomusic.libraryagent.client.NavidromeClient;
import com.sashkomusic.mainagent.library.config.IcecastConfig;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@RecordApplicationEvents
@Import({NowPlayingFlowService.class, NowPlayingResolver.class,
        DjTagContextHolder.class, LastReleaseContextHolder.class,
        LibraryFlowIntegrationTest.TestConfig.class})
class LibraryFlowIntegrationTest {

    private static final long CHAT_ID = 99L;
    private static final ConversationContext CTX = ConversationContext.dm(CHAT_ID);

    @Autowired NowPlayingFlowService nowPlaying;
    @Autowired NavidromeClient navidromeClient;
    @Autowired TrackService trackService;
    @Autowired TrackRepository trackRepository;
    @Autowired DjTagContextHolder djTagContextHolder;
    @Autowired LastReleaseContextHolder lastReleaseContextHolder;
    @Autowired ApplicationEvents events;

    @BeforeEach
    void reset() {
        djTagContextHolder.clearAllContexts();
        lastReleaseContextHolder.clearAll();
    }

    @Test
    void nowPlaying_returns_rating_buttons_when_track_known_in_db() {
        playing("Burial", "Untrue");

        List<BotResponse> resp = nowPlaying.nowPlaying(CTX).responses();

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
    void nowPlaying_makes_the_playing_release_the_this_referent() {
        playing("Burial", "Untrue");

        nowPlaying.nowPlaying(CTX);

        // "перенеси це у vault" / findSimilarInLibrary("this") right after /np must resolve
        // without the user naming the release again.
        assertThat(lastReleaseContextHolder.get(CTX.conversationId()))
                .get()
                .extracting(LastReleaseContextHolder.LastReleaseContext::releaseId,
                        LastReleaseContextHolder.LastReleaseContext::title)
                .containsExactly(7L, "Untrue");
    }

    @Test
    void nowPlaying_hands_the_agent_a_context_line_with_release_and_genres() {
        playing("Burial", "Untrue");

        String agentContext = nowPlaying.nowPlaying(CTX).agentContext();

        assertThat(agentContext)
                .contains("Burial — Untrue")
                .contains("реліз: Untrue")
                .contains("2007")
                .contains("dubstep");
    }

    @Test
    void nowPlaying_handles_missing_track() {
        when(navidromeClient.getCurrentlyPlayingTrackInfo()).thenReturn(null);

        var result = nowPlaying.nowPlaying(CTX);

        assertThat(result.responses()).hasSize(1);
        assertThat(result.responses().get(0).text()).contains("нич не грає");
        assertThat(result.agentContext()).isEmpty();
    }

    @Test
    void RATE_callback_publishes_event_and_sets_navidrome_rating() {
        playing("Burial", "Untrue");
        nowPlaying.nowPlaying(CTX);

        nowPlaying.handleRate(CTX, "RATE:42:5:nav-1");

        verify(navidromeClient).setRating(eq("nav-1"), eq(5));
        var published = events.stream(RateTrackTaskEvent.class).toList();
        assertThat(published).hasSize(1);
        var event = published.get(0);
        assertThat(event.trackId()).isEqualTo(42L);
        assertThat(event.rating()).isEqualTo(5);
        assertThat(event.chatId()).isEqualTo(CHAT_ID);
    }

    /** Player reports the track, the DB has it, and it belongs to a tagged release. */
    private void playing(String artist, String title) {
        when(navidromeClient.getCurrentlyPlayingTrackInfo())
                .thenReturn(new NavidromeClient.CurrentTrackInfo("nav-1", artist, title));
        when(trackService.findByArtistAndTitleOptional(artist, title))
                .thenReturn(Optional.of(TrackDto.of(42L, "/lib/u.flac", title, artist,
                        null, null, null, null)));

        Release release = new Release();
        release.setId(7L);
        release.setTitle(title);
        release.setInitialRelease(2007);
        release.setTags(Set.of(new Tag("dubstep")));
        Track track = new Track();
        track.setId(42L);
        track.setRelease(release);
        when(trackRepository.findById(42L)).thenReturn(Optional.of(track));
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
        @Bean TrackRepository trackRepository() { return mock(TrackRepository.class); }
        @Bean ChatStateStore chatStateStore() { return new InMemoryChatStateStore(); }
    }
}

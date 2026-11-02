package com.sashkomusic.mainagent.library;

import com.sashkomusic.events.AddMarkerTaskEvent;
import com.sashkomusic.events.RemoveMarkerTaskEvent;
import com.sashkomusic.libraryagent.domain.entity.Marker;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.MarkerRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.bot.state.InMemoryChatStateStore;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@RecordApplicationEvents
@Import({SmartlistLabelFlowService.class, SmartlistLabelContextHolder.class, MarkerRemovalFlowTest.TestConfig.class})
class MarkerRemovalFlowTest {

    private static final ConversationContext CTX = ConversationContext.dm(5L);
    private static final long RELEASE_ID = 160L;

    @Autowired SmartlistLabelFlowService flow;
    @Autowired SmartlistLabelContextHolder holder;
    @Autowired TrackRepository trackRepository;
    @Autowired MarkerRepository markerRepository;
    @Autowired ApplicationEvents events;

    @BeforeEach
    void setUp() {
        holder.clear(CTX.conversationId());
        reset(trackRepository, markerRepository);
    }

    @Test
    void album_lists_the_markers_of_all_its_tracks_and_removes_one_only_where_it_is() {
        Track a = track(1L, "tool; E3; (detroit techno)");
        Track b = track(2L, "banger; (detroit techno); (raw modern techno)");
        Track c = track(3L, "intro");
        when(trackRepository.findByReleaseIdOrderByTrackNumberAsc(RELEASE_ID)).thenReturn(List.of(a, b, c));

        BotResponse list = flow.showRemovable(CTX, "A", RELEASE_ID, 77).get(0);

        assertThat(list.text()).isEqualTo("➖ яку мітку зняти?\n\n1. detroit techno\n2. raw modern techno");
        assertThat(list.editMessageId()).isEqualTo(77);
        assertThat(callbacks(list)).containsExactly("LBL_RM_SEL:0", "LBL_RM_SEL:1", "LBL_CANCEL");

        BotResponse done = flow.removeSelected(CTX, 0).get(0);

        assertThat(done.text()).isEqualTo("➖ мітка (detroit techno) знімається з 2 треків альбому");
        assertThat(events.stream(RemoveMarkerTaskEvent.class))
                .containsExactly(new RemoveMarkerTaskEvent(1L, "detroit techno", CTX.conversationId()),
                        new RemoveMarkerTaskEvent(2L, "detroit techno", CTX.conversationId()));
        assertThat(holder.getRemoval(CTX.conversationId())).isEmpty();
    }

    @Test
    void a_track_without_markers_says_so() {
        Track a = track(1L, "tool; E3");
        when(trackRepository.findById(1L)).thenReturn(Optional.of(a));

        assertThat(flow.showRemovable(CTX, "T", 1L, null).get(0).text()).isEqualTo("на цьому треку міток нема");
        assertThat(holder.getRemoval(CTX.conversationId())).isEmpty();
    }

    @Test
    void the_add_picker_offers_removal_and_switching_modes_drops_the_other_one() {
        Marker marker = new Marker("detroit techno");
        marker.setId(49L);
        when(markerRepository.findAllByOrderByLastUsedAtDescNameAsc()).thenReturn(List.of(marker));
        when(trackRepository.findById(1L)).thenReturn(Optional.of(track(1L, "(detroit techno)")));

        BotResponse picker = flow.showList(CTX, "T", 1L).get(0);
        assertThat(callbacks(picker)).contains("LBL_RM_LIST:T:1");

        flow.showRemovable(CTX, "T", 1L, null);
        assertThat(holder.get(CTX.conversationId())).isEmpty();
        assertThat(flow.select(CTX, 0).get(0).text()).isEqualTo("контекст не знайдено — тисни 🏷 знову.");
        assertThat(events.stream(AddMarkerTaskEvent.class)).isEmpty();
    }

    private static Track track(long id, String comment) {
        Track track = new Track("t" + id, (int) id);
        track.setId(id);
        track.setTag("COMM", comment);
        return track;
    }

    private static List<String> callbacks(BotResponse response) {
        return response.buttonRows().stream().flatMap(List::stream).map(BotResponse.ButtonDto::callbackData).toList();
    }

    @Configuration
    static class TestConfig {
        @Bean ChatStateStore chatStateStore() { return new InMemoryChatStateStore(); }
        @Bean TrackRepository trackRepository() { return mock(TrackRepository.class); }
        @Bean MarkerRepository markerRepository() { return mock(MarkerRepository.class); }
        @Bean DjTagContextHolder djTagContextHolder() { return mock(DjTagContextHolder.class); }
        @Bean AlbumCommentContextHolder albumCommentContextHolder() { return mock(AlbumCommentContextHolder.class); }
    }
}

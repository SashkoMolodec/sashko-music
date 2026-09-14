package com.sashkomusic.mainagent.search;

import com.sashkomusic.agents.discovery.SearchRequestExtractor;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.bot.state.InMemoryChatStateStore;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.Language;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.shared.model.TrackMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@Import({ReleaseSearchFlowService.class, SearchContextService.class, AggregatedSearchService.class,
        SearchFlowIntegrationTest.TestConfig.class})
class SearchFlowIntegrationTest {

    private static final long CHAT_ID = 42L;
    private static final ConversationContext CTX = ConversationContext.dm(CHAT_ID);

    @Autowired ReleaseSearchFlowService searchFlow;
    @Autowired SearchContextService contextService;
    @Autowired SearchRequestExtractor extractor;
    @Autowired @Qualifier("musicBrainzMock") SearchEngineService musicBrainz;
    @Autowired @Qualifier("discogsMock") SearchEngineService discogs;
    @Autowired @Qualifier("bandcampMock") SearchEngineService bandcamp;

    @BeforeEach
    void reset() {
        contextService.clearAllCaches();
        lookingFor("Burial", "Untrue", "");
        when(musicBrainz.searchReleases(any())).thenReturn(List.of());
        when(discogs.searchReleases(any())).thenReturn(List.of());
        when(bandcamp.searchReleases(any())).thenReturn(List.of());
    }

    @Test
    void merges_matching_results_from_every_catalog_into_one_stack() {
        when(musicBrainz.searchReleases(any())).thenReturn(List.of(release("mb-1", "Burial", "Untrue", SearchEngine.MUSICBRAINZ)));
        when(discogs.searchReleases(any())).thenReturn(List.of(release("dg-1", "Burial", "Untrue Remastered", SearchEngine.DISCOGS)));
        when(bandcamp.searchReleases(any())).thenReturn(List.of(release("bc-1", "Burial", "Untrue", SearchEngine.BANDCAMP)));

        List<BotResponse> resp = searchFlow.searchDefault(CTX, "Burial Untrue");

        // Three results, but the two "Untrue" copies are the same record — Discogs' edition survives
        // alongside, so the stack is 2 cards.
        assertThat(resp).hasSize(1);
        assertThat(resp.getFirst().text()).contains("1/2");
        assertThat(contextService.getSearchResults(CTX.conversationId())).hasSize(2);
    }

    @Test
    void drops_results_that_are_not_the_requested_release() {
        lookingFor("Adjust (BE)", "Mist", "Mist");
        when(discogs.searchReleases(any())).thenReturn(List.of(
                release("dg-1", "The Magic Number", "Refraction", SearchEngine.DISCOGS),
                release("dg-2", "Lon Bender", "The Hollywood Edge", SearchEngine.DISCOGS)));

        List<BotResponse> resp = searchFlow.searchDefault(CTX, "Adjust (BE) - Mist");

        assertThat(resp).hasSize(1);
        assertThat(resp.getFirst().text()).contains("жоден не збігся");
    }

    @Test
    void confirms_a_compilation_by_loading_its_tracklist() {
        lookingFor("Adjust (BE)", "Mist", "Mist");
        var compilation = release("dg-1", "Various", "Radio Universe - Ultima", SearchEngine.DISCOGS);
        when(discogs.searchReleases(any())).thenReturn(List.of(compilation));
        when(discogs.getTracks(compilation)).thenReturn(List.of(
                new TrackMetadata(1, "Someone Else", "Opener"),
                new TrackMetadata(2, "Adjust (BE)", "Mist")));

        List<BotResponse> resp = searchFlow.searchDefault(CTX, "Adjust (BE) - Mist");

        assertThat(resp.getFirst().text()).contains("radio universe");
        assertThat(contextService.getSearchResults(CTX.conversationId())).hasSize(1);
    }

    @Test
    void reports_nothing_found_when_every_catalog_is_empty() {
        List<BotResponse> resp = searchFlow.searchDefault(CTX, "noexist");

        assertThat(resp).hasSize(1);
        assertThat(resp.getFirst().text()).contains("нич не знайшов");
    }

    @Test
    void dig_deeper_shows_the_results_validation_rejected() {
        when(discogs.searchReleases(any())).thenReturn(List.of(
                release("dg-1", "Someone Else", "Untrue Sessions", SearchEngine.DISCOGS)));
        searchFlow.searchDefault(CTX, "Burial Untrue");
        assertThat(contextService.getStacks(CTX.conversationId())).isEmpty();

        searchFlow.switchStrategyAndSearch(CTX);

        assertThat(contextService.getSearchResults(CTX.conversationId())).hasSize(1);
    }

    @Test
    void each_stack_pages_independently() {
        when(musicBrainz.searchReleases(any())).thenReturn(List.of(
                release("mb-1", "Burial", "Untrue", SearchEngine.MUSICBRAINZ),
                release("mb-2", "Burial", "Untrue Remastered", SearchEngine.MUSICBRAINZ)));
        searchFlow.searchDefault(CTX, "Burial Untrue");
        String firstStack = contextService.getStacks(CTX.conversationId()).getFirst().id();

        // A second search in the same turn adds its own stack rather than replacing the first.
        lookingFor("Four Tet", "Rounds", "");
        when(musicBrainz.searchReleases(any())).thenReturn(List.of(release("mb-3", "Four Tet", "Rounds", SearchEngine.MUSICBRAINZ)));
        String secondStack = contextService.openStack(CTX.conversationId(), "Four Tet Rounds", null, "Four Tet — Rounds",
                List.of(release("mb-3", "Four Tet", "Rounds", SearchEngine.MUSICBRAINZ)));

        var card = searchFlow.handleCardCallback(CTX, "CARD:" + firstStack + ":1", null).getFirst();

        assertThat(card.text()).contains("2/2").contains("untrue remastered");
        assertThat(contextService.findStack(CTX.conversationId(), secondStack)).isPresent();
    }

    private void lookingFor(String artist, String release, String recording) {
        when(extractor.extract(any())).thenReturn(MetadataSearchRequest.create(
                artist, release, recording, DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));
    }

    private static ReleaseMetadata release(String id, String artist, String title, SearchEngine source) {
        return new ReleaseMetadata(id, null, source, artist, title, 100,
                List.of("2007"), List.of("Album"), 13, 13, 1,
                List.of(), null, List.of(), null);
    }

    @Configuration
    static class TestConfig {
        @Bean ChatStateStore chatStateStore() { return new InMemoryChatStateStore(); }
        @Bean FileIdCacheService fileIdCacheService() { return mock(FileIdCacheService.class); }

        @Bean SearchRequestExtractor searchRequestExtractor() { return mock(SearchRequestExtractor.class); }

        /** Same thread — keeps the parallel engine fan-out deterministic under test. */
        @Bean("searchFanoutExecutor") Executor searchFanoutExecutor() { return Runnable::run; }

        @Bean SearchEngineService musicBrainzMock() {
            SearchEngineService m = mock(SearchEngineService.class);
            when(m.getSource()).thenReturn(SearchEngine.MUSICBRAINZ);
            return m;
        }
        @Bean SearchEngineService discogsMock() {
            SearchEngineService m = mock(SearchEngineService.class);
            when(m.getSource()).thenReturn(SearchEngine.DISCOGS);
            return m;
        }
        @Bean SearchEngineService bandcampMock() {
            SearchEngineService m = mock(SearchEngineService.class);
            when(m.getSource()).thenReturn(SearchEngine.BANDCAMP);
            return m;
        }

        @Bean MetadataUrlFetcher metadataUrlFetcher() { return mock(MetadataUrlFetcher.class); }

        @Bean
        Map<SearchEngine, SearchEngineService> searchEngines(List<SearchEngineService> services) {
            return services.stream().collect(Collectors.toMap(
                    SearchEngineService::getSource, s -> s));
        }
    }
}

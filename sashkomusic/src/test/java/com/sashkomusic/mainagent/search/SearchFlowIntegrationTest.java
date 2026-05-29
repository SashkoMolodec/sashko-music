package com.sashkomusic.mainagent.search;

import com.sashkomusic.agents.discovery.SearchRequestExtractor;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.shared.model.DateRange;
import com.sashkomusic.mainagent.shared.model.Language;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@Import({ReleaseSearchFlowService.class, SearchContextService.class,
        SearchFlowIntegrationTest.TestConfig.class})
class SearchFlowIntegrationTest {

    private static final long CHAT_ID = 42L;

    @Autowired ReleaseSearchFlowService searchFlow;
    @Autowired SearchContextService contextService;
    @Autowired SearchRequestExtractor extractor;
    @Autowired @Qualifier("musicBrainzMock") SearchEngineService musicBrainz;
    @Autowired @Qualifier("discogsMock") SearchEngineService discogs;
    @Autowired @Qualifier("bandcampMock") SearchEngineService bandcamp;

    @BeforeEach
    void reset() {
        contextService.clearAllCaches();
        when(extractor.extract(any())).thenReturn(MetadataSearchRequest.create(
                "Burial", "Untrue", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));
        when(musicBrainz.searchReleases(any())).thenReturn(List.of());
        when(discogs.searchReleases(any())).thenReturn(List.of());
        when(bandcamp.searchReleases(any())).thenReturn(List.of());
    }

    @Test
    void searchDefault_tries_musicBrainz_first_and_returns_cards_when_found() {
        when(musicBrainz.searchReleases(any())).thenReturn(List.of(
                release("mb-1", "Burial", "Untrue", SearchEngine.MUSICBRAINZ),
                release("mb-2", "Burial", "Burial",  SearchEngine.MUSICBRAINZ)
        ));

        List<BotResponse> resp = searchFlow.searchDefault(CHAT_ID, "Burial");

        assertThat(resp).isNotEmpty();
        assertThat(resp.get(0).text()).contains("знайдено релізів: 2");
        assertThat(contextService.getSearchResults(CHAT_ID)).hasSize(2);
        assertThat(contextService.getSource(CHAT_ID)).isEqualTo(SearchEngine.MUSICBRAINZ);
    }

    @Test
    void searchDefault_falls_back_to_discogs_when_musicBrainz_empty() {
        when(discogs.searchReleases(any())).thenReturn(List.of(
                release("dg-1", "Burial", "Untrue", SearchEngine.DISCOGS)
        ));

        searchFlow.searchDefault(CHAT_ID, "Burial");

        assertThat(contextService.getSource(CHAT_ID)).isEqualTo(SearchEngine.DISCOGS);
    }

    @Test
    void searchDefault_returns_empty_message_when_no_engine_has_results() {
        List<BotResponse> resp = searchFlow.searchDefault(CHAT_ID, "noexist");

        assertThat(resp).hasSize(1);
        assertThat(resp.get(0).text()).contains("нич не знайшов");
    }

    @Test
    void switchStrategy_moves_from_musicBrainz_to_discogs() {
        when(musicBrainz.searchReleases(any())).thenReturn(List.of(
                release("mb-1", "Burial", "Untrue", SearchEngine.MUSICBRAINZ)
        ));
        searchFlow.searchDefault(CHAT_ID, "Burial");

        when(discogs.searchReleases(any())).thenReturn(List.of(
                release("dg-1", "Burial", "Untrue", SearchEngine.DISCOGS)
        ));
        searchFlow.switchStrategyAndSearch(CHAT_ID);

        assertThat(contextService.getSource(CHAT_ID)).isEqualTo(SearchEngine.DISCOGS);
    }

    private static ReleaseMetadata release(String id, String artist, String title, SearchEngine source) {
        return new ReleaseMetadata(id, null, source, artist, title, 100,
                List.of("2007"), List.of("Album"), 13, 13, 1,
                List.of(), null, List.of(), null);
    }

    @Configuration
    static class TestConfig {
        @Bean SearchRequestExtractor searchRequestExtractor() { return mock(SearchRequestExtractor.class); }

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

        @Bean
        Map<SearchEngine, SearchEngineService> searchEngines(List<SearchEngineService> services) {
            return services.stream().collect(Collectors.toMap(
                    SearchEngineService::getSource, s -> s));
        }
    }
}

package com.sashkomusic.agents.discovery;

import com.sashkomusic.mainagent.search.AggregatedSearchService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzClient;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.Language;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscoveryAgentToolsTest {

    private static final String CONVERSATION = "42:d";

    private final AggregatedSearchService aggregatedSearch = mock(AggregatedSearchService.class);
    private final SearchContextService contextService = mock(SearchContextService.class);
    private final SearchRequestExtractor extractor = mock(SearchRequestExtractor.class);
    private final ReleaseRecommender recommender = mock(ReleaseRecommender.class);

    private final DiscoveryAgentTools tools = new DiscoveryAgentTools(
            aggregatedSearch, contextService, extractor, recommender,
            mock(MusicBrainzClient.class), mock(ListenBrainzClient.class));

    @BeforeEach
    void alwaysFindSomething() {
        when(aggregatedSearch.search(any())).thenAnswer(invocation -> new AggregatedSearchService.Aggregated(
                List.of(release("Model 500", "Classics")), 1, List.of(SearchEngine.DISCOGS), 0));
    }

    @Test
    void a_query_with_no_artist_or_title_is_answered_as_an_explore_ask() {
        // "пошукай detroit techno класичне" — nothing here to validate a result against.
        whenExtracted(MetadataSearchRequest.create("", "", "", DateRange.empty(),
                "", "", "", "", "detroit techno", "", "", Language.UA));
        when(recommender.recommend(anyString())).thenReturn("""
                Model 500 — Classics
                Derrick May — Innovator
                Juan Atkins — Deep Space
                """);

        String result = tools.search("detroit techno класичне", CONVERSATION);

        verify(recommender).recommend("detroit techno класичне");
        verify(contextService, times(3)).openStack(eq(CONVERSATION), anyString(), any(), anyString(), any());
        assertThat(result).contains("card stacks");
    }

    @Test
    void a_named_release_goes_straight_to_the_pinpoint_search() {
        whenExtracted(MetadataSearchRequest.create("Burial", "Untrue", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));

        String result = tools.search("Burial Untrue", CONVERSATION);

        verify(recommender, never()).recommend(anyString());
        verify(contextService).openStack(eq(CONVERSATION), eq("Burial Untrue"), any(), eq(null), any());
        assertThat(result).contains("found 1 matching releases");
    }

    @Test
    void recommendations_are_searched_one_by_one_so_each_gets_its_own_stack() {
        whenExtracted(MetadataSearchRequest.create("", "", "", DateRange.empty(),
                "", "", "", "", "ambient", "", "", Language.EN));
        when(recommender.recommend(anyString())).thenReturn("""
                Model 500 — Classics
                Derrick May — Innovator
                """);

        tools.exploreAndRecommend("ambient", CONVERSATION);

        ArgumentCaptor<MetadataSearchRequest> requests = ArgumentCaptor.forClass(MetadataSearchRequest.class);
        verify(aggregatedSearch, times(2)).search(requests.capture());
        assertThat(requests.getAllValues()).extracting(MetadataSearchRequest::artist)
                .containsExactly("Model 500", "Derrick May");
        assertThat(requests.getAllValues()).extracting(MetadataSearchRequest::release)
                .containsExactly("Classics", "Innovator");
    }

    @Test
    void prose_instead_of_release_names_yields_no_stacks() {
        whenExtracted(MetadataSearchRequest.create("", "", "", DateRange.empty(),
                "", "", "", "", "techno", "", "", Language.EN));
        when(recommender.recommend(anyString())).thenReturn("Detroit techno began in the mid-1980s.");

        String result = tools.exploreAndRecommend("techno", CONVERSATION);

        verify(aggregatedSearch, never()).search(any());
        assertThat(result).contains("no concrete releases");
    }

    private void whenExtracted(MetadataSearchRequest request) {
        when(extractor.extract(anyString())).thenReturn(request);
    }

    private static ReleaseMetadata release(String artist, String title) {
        return new ReleaseMetadata("discogs:release:1", null, SearchEngine.DISCOGS, artist, title, 100,
                List.of("1995"), List.of("Album"), 0, 0, 1, List.of(), null, List.of(), null);
    }
}

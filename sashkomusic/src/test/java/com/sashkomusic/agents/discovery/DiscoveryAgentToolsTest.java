package com.sashkomusic.agents.discovery;

import com.sashkomusic.mainagent.search.AggregatedSearchService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzClient;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzSimilarArtistsResponse;
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
import java.util.Optional;

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

    private final MusicBrainzClient musicBrainz = mock(MusicBrainzClient.class);
    private final ListenBrainzClient listenBrainz = mock(ListenBrainzClient.class);

    private final DiscoveryAgentTools tools = new DiscoveryAgentTools(
            aggregatedSearch, contextService, extractor, recommender, musicBrainz, listenBrainz);

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

    @Test
    void an_artist_musicbrainz_never_heard_of_falls_back_to_the_styles_on_its_own_release() {
        // The real miss: a 1993 white-label 12" that exists on Discogs (with styles) and nowhere else.
        // MusicBrainz can't resolve the artist, so ListenBrainz has nothing — that must not be the end.
        whenExtracted(MetadataSearchRequest.create("Symphony Of Love", "Spirit Of Love", "",
                DateRange.empty(), "", "", "", "", "", "", "", Language.EN));
        when(musicBrainz.findArtistMbid(anyString())).thenReturn(Optional.empty());
        when(aggregatedSearch.search(any())).thenReturn(new AggregatedSearchService.Aggregated(
                List.of(tagged("Symphony Of Love", "Spirit Of Love", "1993", "Acid", "Techno", "Trance", "Ambient")),
                1, List.of(SearchEngine.DISCOGS), 0));
        when(recommender.recommend(anyString())).thenReturn("""
                Hardfloor — TB Resuscitation
                Emmanuel Top — Acid Phase
                """);

        String result = tools.findSimilar("Symphony Of Love — Spirit Of Love", CONVERSATION);

        // Styles capped at 3, year turned into a decade — a usable recommendation topic.
        verify(recommender).recommend("Acid Techno Trance 1990s");
        verify(contextService, times(2)).openStack(eq(CONVERSATION), anyString(), any(), anyString(), any());
        assertThat(result).contains("card stacks");
    }

    @Test
    void listenbrainz_data_still_wins_when_it_exists() {
        whenExtracted(MetadataSearchRequest.create("Burial", "", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));
        when(musicBrainz.findArtistMbid("Burial")).thenReturn(Optional.of("mbid-1"));
        when(listenBrainz.findSimilarArtists("mbid-1"))
                .thenReturn(List.of(new ListenBrainzSimilarArtistsResponse("mbid-2", "Zomby", null, "Person", 90)));

        tools.findSimilar("Burial", CONVERSATION);

        verify(recommender, never()).recommend(anyString());
        verify(contextService).openStack(eq(CONVERSATION), eq("Zomby"), any(), eq("Zomby"), any());
    }

    @Test
    void a_seed_no_catalog_knows_at_all_asks_about_the_spelling() {
        whenExtracted(MetadataSearchRequest.create("Nonexistent", "Thing", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));
        when(musicBrainz.findArtistMbid(anyString())).thenReturn(Optional.empty());
        when(aggregatedSearch.search(any())).thenReturn(
                new AggregatedSearchService.Aggregated(List.of(), 0, List.of(), 0));

        String result = tools.findSimilar("Nonexistent Thing", CONVERSATION);

        verify(recommender, never()).recommend(anyString());
        assertThat(result).contains("spelling");
    }

    private void whenExtracted(MetadataSearchRequest request) {
        when(extractor.extract(anyString())).thenReturn(request);
    }

    private static ReleaseMetadata release(String artist, String title) {
        return new ReleaseMetadata("discogs:release:1", null, SearchEngine.DISCOGS, artist, title, 100,
                List.of("1995"), List.of("Album"), 0, 0, 1, List.of(), null, List.of(), null);
    }

    private static ReleaseMetadata tagged(String artist, String title, String year, String... tags) {
        return new ReleaseMetadata("discogs:release:65958", null, SearchEngine.DISCOGS, artist, title, 100,
                List.of(year), List.of("Single"), 0, 0, 1, List.of(), null, List.of(tags), null);
    }
}

package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.discovery.RelatedArtistsCollector.Connection;
import com.sashkomusic.agents.discovery.RelatedArtistsCollector.RelatedArtist;
import com.sashkomusic.mainagent.search.AggregatedSearchService;
import com.sashkomusic.mainagent.search.SearchContextService;
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
import java.util.stream.IntStream;

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
    private final RelatedArtistsCollector relatedArtists = mock(RelatedArtistsCollector.class);

    private final DiscoveryAgentTools tools = new DiscoveryAgentTools(
            aggregatedSearch, contextService, extractor, recommender, musicBrainz, relatedArtists);

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
    void each_related_artist_shows_its_main_release_and_why_it_is_here() {
        whenSeedIs("Drexciya", "mbid-1");
        when(relatedArtists.collect("mbid-1")).thenReturn(List.of(
                new RelatedArtist("mbid-2", "Arpanet", "псевдонім Gerald Donald", Connection.DIRECT)));
        when(relatedArtists.mainRelease("mbid-2")).thenReturn(Optional.of("Wireless Internet"));

        String result = tools.findSimilar("Drexciya", CONVERSATION);

        String label = "Arpanet — Wireless Internet · псевдонім Gerald Donald";
        ArgumentCaptor<MetadataSearchRequest> request = ArgumentCaptor.forClass(MetadataSearchRequest.class);
        verify(aggregatedSearch).search(request.capture());
        assertThat(request.getValue().artist()).isEqualTo("Arpanet");
        assertThat(request.getValue().release()).isEqualTo("Wireless Internet");
        verify(contextService).openStack(eq(CONVERSATION), eq(label), any(), eq(label),
                eq(Connection.DIRECT.describe("Drexciya")), any());
        verify(recommender, never()).recommend(anyString());
        assertThat(result).contains("card stacks");
    }

    @Test
    void ten_stacks_is_a_ceiling_and_the_ranking_decides_who_makes_it() {
        whenSeedIs("Drexciya", "mbid-1");
        when(relatedArtists.collect("mbid-1")).thenReturn(IntStream.range(0, 30)
                .mapToObj(i -> new RelatedArtist("id-" + i, "Artist " + i, "reason", Connection.DIRECT))
                .toList());
        when(relatedArtists.mainRelease(anyString())).thenReturn(Optional.of("Title"));

        tools.findSimilar("Drexciya", CONVERSATION);

        verify(aggregatedSearch, times(12)).search(any());
        ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
        verify(contextService, times(10)).openStack(eq(CONVERSATION), labels.capture(), any(), anyString(), anyString(), any());
        assertThat(labels.getAllValues()).first().asString().startsWith("Artist 0 — ");
        assertThat(labels.getAllValues()).last().asString().startsWith("Artist 9 — ");
    }

    @Test
    void the_ranking_picks_the_ten_and_the_cards_then_go_by_kind_of_connection() {
        whenSeedIs("Jeff Mills", "mbid-1");
        when(relatedArtists.collect("mbid-1")).thenReturn(List.of(
                new RelatedArtist("a", "Millsart", "reason", Connection.DIRECT),
                new RelatedArtist("b", "Surgeon", "reason", Connection.CO_LISTEN),
                new RelatedArtist("c", "Robert Hood", "reason", Connection.BRIDGE),
                new RelatedArtist("d", "X-102", "reason", Connection.DIRECT)));
        when(relatedArtists.mainRelease(anyString())).thenReturn(Optional.of("Title"));

        tools.findSimilar("Jeff Mills", CONVERSATION);

        ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
        verify(contextService, times(4)).openStack(eq(CONVERSATION), labels.capture(), any(), anyString(), anyString(), any());
        assertThat(labels.getAllValues()).extracting(label -> label.split(" — ")[0])
                .containsExactly("Millsart", "X-102", "Robert Hood", "Surgeon");
    }

    @Test
    void fewer_confirmed_releases_means_fewer_stacks_with_no_padding() {
        whenSeedIs("Drexciya", "mbid-1");
        when(relatedArtists.collect("mbid-1")).thenReturn(List.of(
                new RelatedArtist("no-releases", "Ghost", "reason", Connection.DIRECT),
                new RelatedArtist("unconfirmed", "Unknown", "reason", Connection.DIRECT),
                new RelatedArtist("real", "Arpanet", "reason", Connection.DIRECT)));
        when(relatedArtists.mainRelease("no-releases")).thenReturn(Optional.empty());
        when(relatedArtists.mainRelease("unconfirmed")).thenReturn(Optional.of("Nothing"));
        when(relatedArtists.mainRelease("real")).thenReturn(Optional.of("Wireless Internet"));
        when(aggregatedSearch.search(any())).thenAnswer(invocation -> {
            MetadataSearchRequest request = invocation.getArgument(0);
            return new AggregatedSearchService.Aggregated(
                    request.artist().equals("Arpanet") ? List.of(release("Arpanet", "Wireless Internet")) : List.of(),
                    0, List.of(), 0);
        });

        tools.findSimilar("Drexciya", CONVERSATION);

        verify(contextService, times(1)).openStack(eq(CONVERSATION), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void one_failed_search_does_not_cost_the_rest_of_the_answer() {
        whenSeedIs("Drexciya", "mbid-1");
        when(relatedArtists.collect("mbid-1")).thenReturn(List.of(
                new RelatedArtist("boom", "Boom", "reason", Connection.DIRECT),
                new RelatedArtist("real", "Arpanet", "reason", Connection.DIRECT)));
        when(relatedArtists.mainRelease("boom")).thenThrow(new IllegalStateException("catalog down"));
        when(relatedArtists.mainRelease("real")).thenReturn(Optional.of("Wireless Internet"));

        tools.findSimilar("Drexciya", CONVERSATION);

        verify(contextService, times(1)).openStack(eq(CONVERSATION), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void related_artists_that_all_fail_fall_back_to_the_styles_on_the_seed() {
        whenSeedIs("Symphony Of Love", "mbid-1");
        when(relatedArtists.collect("mbid-1")).thenReturn(List.of(new RelatedArtist("ghost", "Ghost", "reason", Connection.DIRECT)));
        when(relatedArtists.mainRelease("ghost")).thenReturn(Optional.empty());
        when(aggregatedSearch.search(any())).thenReturn(new AggregatedSearchService.Aggregated(
                List.of(tagged("Symphony Of Love", "Spirit Of Love", "1993", "Acid")),
                1, List.of(SearchEngine.DISCOGS), 0));
        when(recommender.recommend(anyString())).thenReturn("Hardfloor — TB Resuscitation");

        tools.findSimilar("Symphony Of Love", CONVERSATION);

        verify(recommender).recommend("Acid 1990s");
    }

    @Test
    void a_label_is_a_lookup_not_an_explore_ask_and_shows_one_release_per_artist() {
        whenExtracted(label("Tresor"));
        when(aggregatedSearch.search(any())).thenReturn(new AggregatedSearchService.Aggregated(
                List.of(release("Jeff Mills", "Waveform Transmission Vol. 1"),
                        release("JEFF MILLS", "Waveform Transmission Vol. 3"),
                        release("X-102", "Discovers the Rings of Saturn")),
                3, List.of(SearchEngine.DISCOGS), 0));

        String result = tools.search("лейбл Tresor", CONVERSATION);

        verify(recommender, never()).recommend(anyString());
        verify(contextService, times(2)).openStack(eq(CONVERSATION), eq("лейбл Tresor"), any(), eq("Tresor"), any());
        assertThat(result).contains("Waveform Transmission Vol. 1", "Discovers the Rings of Saturn")
                .doesNotContain("Vol. 3");
    }

    @Test
    void a_label_shows_at_most_ten_stacks() {
        whenExtracted(label("Warp"));
        when(aggregatedSearch.search(any())).thenReturn(new AggregatedSearchService.Aggregated(
                IntStream.range(0, 20).mapToObj(i -> release("Artist " + i, "Title")).toList(),
                20, List.of(SearchEngine.DISCOGS), 0));

        tools.search("лейбл Warp", CONVERSATION);

        verify(contextService, times(10)).openStack(eq(CONVERSATION), anyString(), any(), anyString(), any());
    }

    @Test
    void an_unconfirmed_label_keeps_the_query_for_dig_deeper() {
        whenExtracted(label("Nonexistent"));
        when(aggregatedSearch.search(any())).thenReturn(
                new AggregatedSearchService.Aggregated(List.of(), 7, List.of(), 0));

        String result = tools.search("лейбл Nonexistent", CONVERSATION);

        verify(contextService).rememberQuery(eq(CONVERSATION), eq("лейбл Nonexistent"), any());
        verify(contextService, never()).openStack(anyString(), anyString(), any(), anyString(), any());
        assertThat(result).contains("none were the requested");
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

    private void whenSeedIs(String artist, String mbid) {
        whenExtracted(MetadataSearchRequest.create(artist, "", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));
        when(musicBrainz.findArtistMbid(artist)).thenReturn(Optional.of(mbid));
    }

    private static MetadataSearchRequest label(String name) {
        return MetadataSearchRequest.create("", "", "", DateRange.empty(),
                "", "", "", "", "", name, "", Language.UA);
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

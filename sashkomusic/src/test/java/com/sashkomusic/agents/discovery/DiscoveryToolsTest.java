package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.discovery.RelatedArtistsCollector.Connection;
import com.sashkomusic.agents.discovery.RelatedArtistsCollector.RelatedArtist;
import com.sashkomusic.agents.turn.AgentTurn;
import com.sashkomusic.libraryagent.domain.model.OwnedRelease;
import com.sashkomusic.libraryagent.domain.service.LibrarySearchService;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.search.AggregatedSearchService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchRequestExtractor;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.Language;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscoveryToolsTest {

    private static final String CONVERSATION = "42";

    private final AggregatedSearchService aggregatedSearch = mock(AggregatedSearchService.class);
    private final SearchContextService contextService = mock(SearchContextService.class);
    private final SearchRequestExtractor extractor = mock(SearchRequestExtractor.class);
    private final ReleaseRecommender recommender = mock(ReleaseRecommender.class);

    private final MusicBrainzClient musicBrainz = mock(MusicBrainzClient.class);
    private final RelatedArtistsCollector relatedArtists = mock(RelatedArtistsCollector.class);
    private final MusicResearcher researcher = mock(MusicResearcher.class);
    private final LibrarySearchService library = mock(LibrarySearchService.class);

    private final DiscoveryTools tools = new DiscoveryTools(
            aggregatedSearch, contextService, extractor, recommender, musicBrainz, relatedArtists, researcher, library);

    private final List<String> progress = new ArrayList<>();
    private final AgentTurn turn = new AgentTurn(ConversationContext.dm(42L), progress::add);

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
        when(recommender.recommend(anyString(), anyString())).thenReturn("""
                Model 500 — Classics
                Derrick May — Innovator
                Juan Atkins — Deep Space
                """);

        String result = tools.search("detroit techno класичне", turn.parameters());

        verify(recommender).recommend(eq("detroit techno класичне"), anyString());
        verify(contextService, times(3)).openStack(eq(CONVERSATION), anyString(), any(), anyString(), any(), any());
        assertThat(result).contains("card stacks");
    }

    @Test
    void a_named_release_goes_straight_to_the_pinpoint_search() {
        whenExtracted(MetadataSearchRequest.create("Burial", "Untrue", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));

        String result = tools.search("Burial Untrue", turn.parameters());

        verify(recommender, never()).recommend(anyString(), anyString());
        verify(contextService).openStack(eq(CONVERSATION), eq("Burial Untrue"), any(), eq(null), any(), any());
        assertThat(result).startsWith("showed 1 card stack for 'Burial Untrue'");
    }

    @Test
    void each_recommendation_is_searched_on_its_own_so_each_gets_its_own_stack() {
        whenExtracted(MetadataSearchRequest.create("", "", "", DateRange.empty(),
                "", "", "", "", "ambient", "", "", Language.EN));
        when(recommender.recommend(anyString(), anyString())).thenReturn("""
                Model 500 — Classics
                Derrick May — Innovator
                """);

        tools.exploreAndRecommend("ambient", turn.parameters());

        ArgumentCaptor<MetadataSearchRequest> requests = ArgumentCaptor.forClass(MetadataSearchRequest.class);
        verify(aggregatedSearch, times(2)).search(requests.capture());
        // The searches run side by side, so the call order is not the stack order.
        assertThat(requests.getAllValues()).extracting(MetadataSearchRequest::artist)
                .containsExactlyInAnyOrder("Model 500", "Derrick May");
        assertThat(requests.getAllValues()).extracting(MetadataSearchRequest::release)
                .containsExactlyInAnyOrder("Classics", "Innovator");
    }

    @Test
    void a_pick_the_catalogs_lack_is_replaced_by_a_spare_and_the_stacks_keep_the_recommenders_order() {
        when(recommender.recommend(anyString(), anyString())).thenReturn("""
                Model 500 — Classics
                Nobody — Unknown Tape
                Derrick May — Innovator
                Juan Atkins — Deep Space
                Cybotron — Enter
                """);
        when(aggregatedSearch.search(any())).thenAnswer(invocation -> {
            MetadataSearchRequest request = invocation.getArgument(0);
            List<ReleaseMetadata> found = request.artist().equals("Nobody")
                    ? List.of() : List.of(release(request.artist(), request.release()));
            return new AggregatedSearchService.Aggregated(found, found.size(), List.of(SearchEngine.DISCOGS), 0);
        });

        tools.exploreAndRecommend("detroit techno", turn.parameters());

        ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
        verify(contextService, times(3)).openStack(eq(CONVERSATION), labels.capture(), any(), anyString(), any(), any());
        assertThat(labels.getAllValues()).containsExactly(
                "Model 500 — Classics", "Derrick May — Innovator", "Juan Atkins — Deep Space");
    }

    @Test
    void picks_the_user_already_owns_are_never_searched_and_the_agent_hears_why() {
        when(library.ownedReleases(anyInt())).thenReturn(List.of(
                new OwnedRelease("Cybotron", "Enter (Deluxe Edition)"),
                new OwnedRelease("Model 500", "Deep Space")));
        when(recommender.recommend(anyString(), anyString())).thenReturn("""
                Cybotron — Enter
                Model 500 — Deep Space
                Blake Baxter — The Prince of Techno
                """);

        String result = tools.exploreAndRecommend("ранній детройт", turn.parameters());

        verify(recommender).recommend("ранній детройт", "Cybotron — Enter (Deluxe Edition)\nModel 500 — Deep Space");
        ArgumentCaptor<MetadataSearchRequest> requests = ArgumentCaptor.forClass(MetadataSearchRequest.class);
        verify(aggregatedSearch).search(requests.capture());
        assertThat(requests.getValue().artist()).isEqualTo("Blake Baxter");
        assertThat(result).contains("Skipped, already in the user's library: Cybotron — Enter; Model 500 — Deep Space.");
    }

    @Test
    void a_sentence_with_a_dash_in_it_is_not_a_pick() {
        when(recommender.recommend(anyString(), anyString())).thenReturn("""
                Мені потрібні конкретні інші артисти та релізи поза Belleville Three.
                Blake Baxter — The Prince of Techno
                Вибачте — не можу надати повну відповідь з достатньою впевненістю в точності каталогів без додаткового пошуку.
                """);

        tools.exploreAndRecommend("ранній детройт", turn.parameters());

        verify(aggregatedSearch, times(1)).search(any());
        verify(contextService, times(1)).openStack(eq(CONVERSATION), eq("Blake Baxter — The Prince of Techno"),
                any(), anyString(), any(), any());
    }

    @Test
    void spares_are_searched_in_waves_only_while_three_are_not_yet_confirmed() {
        when(recommender.recommend(anyString(), anyString())).thenReturn("""
                Real One — A
                Ghost One — B
                Ghost Two — C
                Ghost Three — D
                Ghost Four — E
                Real Two — F
                Real Three — G
                Never Searched — H
                Real Four — I
                Real Five — J
                """);
        when(aggregatedSearch.search(any())).thenAnswer(invocation -> {
            MetadataSearchRequest request = invocation.getArgument(0);
            List<ReleaseMetadata> found = request.artist().startsWith("Real")
                    ? List.of(release(request.artist(), request.release())) : List.of();
            return new AggregatedSearchService.Aggregated(found, found.size(), List.of(SearchEngine.DISCOGS), 0);
        });

        tools.exploreAndRecommend("anything", turn.parameters());

        ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
        verify(contextService, times(3)).openStack(eq(CONVERSATION), labels.capture(), any(), anyString(), any(), any());
        assertThat(labels.getAllValues()).containsExactly("Real One — A", "Real Two — F", "Real Three — G");
        verify(aggregatedSearch, times(10)).search(any());
    }

    @Test
    void a_first_wave_that_confirms_three_leaves_the_spares_unsearched() {
        when(recommender.recommend(anyString(), anyString())).thenReturn("""
                A1 — T1
                A2 — T2
                A3 — T3
                A4 — T4
                A5 — T5
                A6 — T6
                A7 — T7
                """);

        tools.exploreAndRecommend("anything", turn.parameters());

        verify(aggregatedSearch, times(5)).search(any());
        verify(contextService, times(3)).openStack(eq(CONVERSATION), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void a_related_release_the_user_owns_is_skipped() {
        whenSeedIs("Cybotron", "mbid-1");
        when(library.ownedReleases(anyInt())).thenReturn(List.of(new OwnedRelease("Model 500", "Deep Space")));
        when(relatedArtists.collect("mbid-1")).thenReturn(List.of(
                new RelatedArtist("m500", "Model 500", "псевдонім Juan Atkins", Connection.DIRECT),
                new RelatedArtist("mbid-2", "Arpanet", "co-listen", Connection.CO_LISTEN)));
        when(relatedArtists.mainRelease("m500")).thenReturn(Optional.of("Deep Space"));
        when(relatedArtists.mainRelease("mbid-2")).thenReturn(Optional.of("Wireless Internet"));

        String result = tools.findSimilar("Cybotron", turn.parameters());

        ArgumentCaptor<MetadataSearchRequest> request = ArgumentCaptor.forClass(MetadataSearchRequest.class);
        verify(aggregatedSearch).search(request.capture());
        assertThat(request.getValue().artist()).isEqualTo("Arpanet");
        assertThat(result).contains("Skipped, already in the user's library: Model 500 — Deep Space.");
    }

    @Test
    void prose_instead_of_release_names_yields_no_stacks() {
        whenExtracted(MetadataSearchRequest.create("", "", "", DateRange.empty(),
                "", "", "", "", "techno", "", "", Language.EN));
        when(recommender.recommend(anyString(), anyString())).thenReturn("Detroit techno began in the mid-1980s.");

        String result = tools.exploreAndRecommend("techno", turn.parameters());

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
        when(recommender.recommend(anyString(), anyString())).thenReturn("""
                Hardfloor — TB Resuscitation
                Emmanuel Top — Acid Phase
                """);

        String result = tools.findSimilar("Symphony Of Love — Spirit Of Love", turn.parameters());

        // Styles capped at 3, year turned into a decade — a usable recommendation topic.
        verify(recommender).recommend(eq("Acid Techno Trance 1990s"), anyString());
        verify(contextService, times(2)).openStack(eq(CONVERSATION), anyString(), any(), anyString(), any(), any());
        assertThat(result).contains("card stacks");
    }

    @Test
    void each_related_artist_shows_its_main_release_and_why_it_is_here() {
        whenSeedIs("Drexciya", "mbid-1");
        when(relatedArtists.collect("mbid-1")).thenReturn(List.of(
                new RelatedArtist("mbid-2", "Arpanet", "псевдонім Gerald Donald", Connection.DIRECT)));
        when(relatedArtists.mainRelease("mbid-2")).thenReturn(Optional.of("Wireless Internet"));

        String result = tools.findSimilar("Drexciya", turn.parameters());

        String label = "Arpanet — Wireless Internet · псевдонім Gerald Donald";
        ArgumentCaptor<MetadataSearchRequest> request = ArgumentCaptor.forClass(MetadataSearchRequest.class);
        verify(aggregatedSearch).search(request.capture());
        assertThat(request.getValue().artist()).isEqualTo("Arpanet");
        assertThat(request.getValue().release()).isEqualTo("Wireless Internet");
        verify(contextService).openStack(eq(CONVERSATION), eq(label), any(), eq(label),
                eq(Connection.DIRECT.describe("Drexciya")), any());
        verify(recommender, never()).recommend(anyString(), anyString());
        assertThat(result).startsWith("showed 1 card stack — " + label)
                .contains("Source: artists connected to Drexciya");
        assertThat(turn.openedStacks()).extracting(stack -> stack.group())
                .containsExactly(Connection.DIRECT.describe("Drexciya"));
    }

    @Test
    void ten_stacks_is_a_ceiling_and_the_ranking_decides_who_makes_it() {
        whenSeedIs("Drexciya", "mbid-1");
        when(relatedArtists.collect("mbid-1")).thenReturn(IntStream.range(0, 30)
                .mapToObj(i -> new RelatedArtist("id-" + i, "Artist " + i, "reason", Connection.DIRECT))
                .toList());
        when(relatedArtists.mainRelease(anyString())).thenReturn(Optional.of("Title"));

        tools.findSimilar("Drexciya", turn.parameters());

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

        tools.findSimilar("Jeff Mills", turn.parameters());

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

        tools.findSimilar("Drexciya", turn.parameters());

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

        tools.findSimilar("Drexciya", turn.parameters());

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
        when(recommender.recommend(anyString(), anyString())).thenReturn("Hardfloor — TB Resuscitation");

        tools.findSimilar("Symphony Of Love", turn.parameters());

        verify(recommender).recommend(eq("Acid 1990s"), anyString());
    }

    @Test
    void a_label_is_a_lookup_not_an_explore_ask_and_shows_one_release_per_artist() {
        whenExtracted(label("Tresor"));
        when(aggregatedSearch.search(any())).thenReturn(new AggregatedSearchService.Aggregated(
                List.of(release("Jeff Mills", "Waveform Transmission Vol. 1"),
                        release("JEFF MILLS", "Waveform Transmission Vol. 3"),
                        release("X-102", "Discovers the Rings of Saturn")),
                3, List.of(SearchEngine.DISCOGS), 0));

        String result = tools.search("лейбл Tresor", turn.parameters());

        verify(recommender, never()).recommend(anyString(), anyString());
        verify(contextService, times(2)).openStack(eq(CONVERSATION), eq("лейбл Tresor"), any(), eq("Tresor"), any(), any());
        assertThat(result).contains("Waveform Transmission Vol. 1", "Discovers the Rings of Saturn")
                .doesNotContain("Vol. 3");
    }

    @Test
    void a_label_shows_at_most_ten_stacks() {
        whenExtracted(label("Warp"));
        when(aggregatedSearch.search(any())).thenReturn(new AggregatedSearchService.Aggregated(
                IntStream.range(0, 20).mapToObj(i -> release("Artist " + i, "Title")).toList(),
                20, List.of(SearchEngine.DISCOGS), 0));

        tools.search("лейбл Warp", turn.parameters());

        verify(contextService, times(10)).openStack(eq(CONVERSATION), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void an_unconfirmed_label_keeps_the_query_for_dig_deeper() {
        whenExtracted(label("Nonexistent"));
        when(aggregatedSearch.search(any())).thenReturn(
                new AggregatedSearchService.Aggregated(List.of(), 7, List.of(), 0));

        String result = tools.search("лейбл Nonexistent", turn.parameters());

        verify(contextService).rememberQuery(eq(CONVERSATION), eq("лейбл Nonexistent"), any());
        verify(contextService, never()).openStack(any(), any(), any(), any(), any(), any());
        verify(contextService, never()).beginStacks(any());
        assertThat(result).contains("none were the requested");
    }

    @Test
    void a_seed_no_catalog_knows_at_all_asks_about_the_spelling() {
        whenExtracted(MetadataSearchRequest.create("Nonexistent", "Thing", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));
        when(musicBrainz.findArtistMbid(anyString())).thenReturn(Optional.empty());
        when(aggregatedSearch.search(any())).thenReturn(
                new AggregatedSearchService.Aggregated(List.of(), 0, List.of(), 0));

        String result = tools.findSimilar("Nonexistent Thing", turn.parameters());

        verify(recommender, never()).recommend(anyString(), anyString());
        assertThat(result).contains("spelling");
    }

    @Test
    void tools_only_add_stacks_and_leave_the_turn_boundary_to_the_service() {
        when(recommender.recommend(anyString(), anyString())).thenReturn("""
                Model 500 — Classics
                Derrick May — Innovator
                Juan Atkins — Deep Space
                """);

        tools.exploreAndRecommend("detroit techno", turn.parameters());
        whenExtracted(MetadataSearchRequest.create("Burial", "Untrue", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));
        tools.search("Burial Untrue", turn.parameters());

        verify(contextService, never()).beginStacks(any());
        verify(contextService, never()).retainStacks(any(), any());
        assertThat(turn.openedStacks()).extracting(stack -> stack.label())
                .containsExactly("Model 500 — Classics", "Derrick May — Innovator", "Juan Atkins — Deep Space", null);
    }

    @Test
    void a_turn_that_finds_nothing_leaves_the_stacks_already_in_the_chat_alone() {
        whenExtracted(MetadataSearchRequest.create("Nobody", "Nothing", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));
        when(aggregatedSearch.search(any())).thenReturn(
                new AggregatedSearchService.Aggregated(List.of(), 0, List.of(), 0));

        String result = tools.search("Nobody Nothing", turn.parameters());

        verify(contextService, never()).beginStacks(any());
        assertThat(turn.openedStacks()).isEmpty();
        assertThat(result).contains("nothing found", "artist=Nobody");
    }

    @Test
    void several_searches_in_one_turn_ping_the_chat_once() {
        whenExtracted(MetadataSearchRequest.create("Burial", "Untrue", "", DateRange.empty(),
                "", "", "", "", "", "", "", Language.EN));

        tools.search("Burial Untrue", turn.parameters());
        tools.search("Burial Rival Dealer", turn.parameters());

        assertThat(progress).containsExactly("🔍 шукаю...");
    }

    @Test
    void research_relays_the_sourced_answer_and_opens_no_stacks() {
        when(researcher.research("history of the Tresor label")).thenReturn("tresor заснував дімітрі гегеманн у 1991.");

        String result = tools.researchMusic("history of the Tresor label", turn.parameters());

        assertThat(result).isEqualTo("tresor заснував дімітрі гегеманн у 1991.");
        assertThat(progress).containsExactly("🌐 шукаю в мережі...");
        assertThat(turn.openedStacks()).isEmpty();
    }

    @Test
    void failed_research_says_so_instead_of_inviting_an_answer_from_memory() {
        when(researcher.research(anyString())).thenThrow(new IllegalStateException("529 overloaded"));

        assertThat(tools.researchMusic("хто такий Gerald Donald", turn.parameters()))
                .contains("could not check");
    }

    @Test
    void the_tracklist_comes_from_the_release_on_screen_in_the_main_conversation() {
        ReleaseMetadata first = release("Burial", "Untrue");
        ReleaseMetadata second = new ReleaseMetadata("discogs:release:2", null, SearchEngine.DISCOGS, "Burial",
                "Rival Dealer", 100, List.of("2013"), List.of("EP"), 0, 0, 1, List.of(), null, List.of(), null);
        when(contextService.getSearchResults(CONVERSATION)).thenReturn(List.of(first, second));
        when(contextService.getCurrentPage(CONVERSATION)).thenReturn(1);
        when(contextService.getMetadataWithTracks("discogs:release:2", CONVERSATION)).thenReturn(second.withTracks(
                List.of(new com.sashkomusic.shared.model.TrackMetadata(1, "Burial", "Rival Dealer"))));

        assertThat(tools.getTrackList(turn.parameters())).isEqualTo("Burial — Rival Dealer (2013)\n1. Rival Dealer");
    }

    @Test
    void a_tool_outside_a_turn_is_a_wiring_bug() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.getTrackList(null))
                .isInstanceOf(IllegalStateException.class);
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

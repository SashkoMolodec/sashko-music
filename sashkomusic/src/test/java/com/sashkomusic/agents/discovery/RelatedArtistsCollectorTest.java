package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.discovery.RelatedArtistsCollector.Connection;
import com.sashkomusic.agents.discovery.RelatedArtistsCollector.RelatedArtist;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzClient;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzSimilarArtistsResponse;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzArtistRelationsResponse;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzArtistRelationsResponse.Artist;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzArtistRelationsResponse.Relation;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzReleaseGroupSearchResponse.ReleaseGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelatedArtistsCollectorTest {

    private static final String DREXCIYA = "drexciya";
    private static final String STINSON = "stinson";
    private static final String DONALD = "donald";
    private static final String UR = "ur";

    private final MusicBrainzClient musicBrainz = mock(MusicBrainzClient.class);
    private final ListenBrainzClient listenBrainz = mock(ListenBrainzClient.class);

    private final RelatedArtistsCollector collector = new RelatedArtistsCollector(musicBrainz, listenBrainz);

    @BeforeEach
    void nothingIsKnownByDefault() {
        when(musicBrainz.getArtistRelations(anyString())).thenReturn(Optional.empty());
        when(listenBrainz.findSimilarArtists(anyString())).thenReturn(List.of());
    }

    @Test
    void a_group_seed_is_walked_through_the_people_behind_it() {
        stubDrexciya();

        List<RelatedArtist> related = collector.collect(DREXCIYA);

        assertThat(related).extracting(RelatedArtist::name)
                .contains("James Stinson", "Transllusion", "Elecktroids", "Arpanet", "Dopplereffekt")
                .doesNotContain("Drexciya");
        assertThat(related).filteredOn(artist -> artist.name().equals("Transllusion"))
                .extracting(RelatedArtist::reason).containsExactly("псевдонім James Stinson");
        assertThat(related).filteredOn(artist -> artist.name().equals("Elecktroids"))
                .extracting(RelatedArtist::reason).containsExactly("James Stinson у складі");
    }

    @Test
    void a_band_two_people_share_comes_first_and_names_both() {
        stubDrexciya();

        RelatedArtist first = collector.collect(DREXCIYA).getFirst();

        assertThat(first.name()).isEqualTo("Underground Resistance");
        assertThat(first.reason()).isEqualTo("James Stinson і Gerald Donald у складі");
    }

    @Test
    void shared_bands_bridge_to_their_other_members() {
        stubDrexciya();

        assertThat(collector.collect(DREXCIYA)).filteredOn(artist -> artist.name().equals("Jeff Mills"))
                .extracting(RelatedArtist::reason).containsExactly("разом у Underground Resistance");
    }

    @Test
    void the_sources_are_woven_together_so_co_listen_gets_a_say() {
        when(listenBrainz.findSimilarArtists(DREXCIYA)).thenReturn(List.of(
                new ListenBrainzSimilarArtistsResponse("low", "Low Score", null, "Group", 10),
                new ListenBrainzSimilarArtistsResponse("high", "High Score", null, "Group", 90)));
        stubDrexciya();

        List<String> names = collector.collect(DREXCIYA).stream().map(RelatedArtist::name).toList();

        // Pattern: direct, bridge, direct, co-listen. The sharper co-listen score goes first.
        assertThat(names.get(1)).isEqualTo("Jeff Mills");
        assertThat(names.get(3)).isEqualTo("High Score");
        assertThat(names.indexOf("High Score")).isLessThan(names.indexOf("Low Score"));
    }

    @Test
    void a_real_connection_beats_co_listen_as_the_reason() {
        when(listenBrainz.findSimilarArtists(DREXCIYA)).thenReturn(List.of(
                new ListenBrainzSimilarArtistsResponse("doppler", "Dopplereffekt", null, "Group", 90)));
        stubDrexciya();

        assertThat(collector.collect(DREXCIYA)).filteredOn(artist -> artist.name().equals("Dopplereffekt"))
                .extracting(RelatedArtist::reason).containsExactly("Gerald Donald у складі");
    }

    @Test
    void every_artist_carries_the_kind_of_tie_that_brought_it_in() {
        when(listenBrainz.findSimilarArtists(DREXCIYA)).thenReturn(List.of(
                new ListenBrainzSimilarArtistsResponse("aphex", "Aphex Twin", null, "Person", 90),
                new ListenBrainzSimilarArtistsResponse("doppler", "Dopplereffekt", null, "Group", 80)));
        stubDrexciya();

        Map<String, Connection> connections = collector.collect(DREXCIYA).stream()
                .collect(Collectors.toMap(RelatedArtist::name, RelatedArtist::connection));

        assertThat(connections)
                .containsEntry("James Stinson", Connection.DIRECT)
                .containsEntry("Arpanet", Connection.DIRECT)
                .containsEntry("Jeff Mills", Connection.BRIDGE)
                .containsEntry("Aphex Twin", Connection.CO_LISTEN)
                // Also co-listened, but a real connection is the better explanation.
                .containsEntry("Dopplereffekt", Connection.DIRECT);
    }

    @Test
    void the_reason_names_two_sources_at_most() {
        when(musicBrainz.getArtistRelations("mills")).thenReturn(Optional.of(artist("mills", "Jeff Mills", "Person",
                forward("member of band", "a", "Band A", "Group"),
                forward("member of band", "b", "Band B", "Group"),
                forward("member of band", "c", "Band C", "Group"))));
        for (String band : List.of("a", "b", "c")) {
            when(musicBrainz.getArtistRelations(band)).thenReturn(Optional.of(artist(band, "Band " + band.toUpperCase(), "Group",
                    backward("member of band", "hood", "Robert Hood", "Person"))));
        }

        assertThat(collector.collect("mills")).filteredOn(artist -> artist.name().equals("Robert Hood"))
                .extracting(RelatedArtist::reason).containsExactly("разом у Band A і Band B");
    }

    @Test
    void one_member_does_not_take_every_direct_slot() {
        stubDrexciya();

        List<String> names = collector.collect(DREXCIYA).stream().map(RelatedArtist::name).toList();

        assertThat(names.indexOf("Arpanet")).isLessThan(names.indexOf("Elecktroids"));
    }

    @Test
    void a_person_seed_uses_its_own_relations_without_a_second_lookup() {
        when(musicBrainz.getArtistRelations("mills")).thenReturn(Optional.of(artist("mills", "Jeff Mills", "Person",
                forward("is person", "millsart", "Millsart", "Person"))));

        List<RelatedArtist> related = collector.collect("mills");

        assertThat(related).extracting(RelatedArtist::name).containsExactly("Millsart");
        verify(musicBrainz, times(1)).getArtistRelations("mills");
    }

    @Test
    void an_alias_seed_reaches_the_person_behind_it() {
        when(musicBrainz.getArtistRelations("arpanet")).thenReturn(Optional.of(artist("arpanet", "Arpanet", "Person",
                backward("is person", DONALD, "Gerald Donald", "Person"))));
        when(musicBrainz.getArtistRelations(DONALD)).thenReturn(Optional.of(donald()));

        assertThat(collector.collect("arpanet")).extracting(RelatedArtist::name)
                .contains("Gerald Donald", "Dopplereffekt")
                .doesNotContain("Arpanet");
    }

    @Test
    void a_big_group_only_costs_the_first_few_members() {
        Relation[] members = IntStream.range(0, 17)
                .mapToObj(i -> backward("member of band", "m" + i, "Member " + i, "Person"))
                .toArray(Relation[]::new);
        when(musicBrainz.getArtistRelations(UR)).thenReturn(
                Optional.of(artist(UR, "Underground Resistance", "Group", members)));

        collector.collect(UR);

        verify(musicBrainz, times(4)).getArtistRelations(org.mockito.ArgumentMatchers.startsWith("m"));
    }

    @Test
    void an_artist_musicbrainz_links_to_nothing_still_gets_co_listen() {
        when(listenBrainz.findSimilarArtists("burial")).thenReturn(List.of(
                new ListenBrainzSimilarArtistsResponse("zomby", "Zomby", null, "Person", 90)));

        assertThat(collector.collect("burial")).extracting(RelatedArtist::reason)
                .containsExactly("часто слухають разом");
    }

    @Test
    void co_listen_rows_without_an_mbid_are_dropped() {
        when(listenBrainz.findSimilarArtists("burial")).thenReturn(List.of(
                new ListenBrainzSimilarArtistsResponse(null, "Nameless", null, "Person", 90)));

        assertThat(collector.collect("burial")).isEmpty();
    }

    @Test
    void the_main_release_is_the_earliest_plain_album() {
        when(musicBrainz.browseReleaseGroups("mills")).thenReturn(List.of(
                group("Later Album", "Album", "2000"),
                group("Early EP", "EP", "1990"),
                group("Early Album", "Album", "1992"),
                group("The Best Of", "Album", "1985", "Compilation")));

        assertThat(collector.mainRelease("mills")).contains("Early Album");
    }

    @Test
    void the_main_release_falls_back_through_eps_singles_and_compilations() {
        when(musicBrainz.browseReleaseGroups("a")).thenReturn(List.of(
                group("Single", "Single", "1990"), group("EP", "EP", "1999")));
        when(musicBrainz.browseReleaseGroups("b")).thenReturn(List.of(
                group("Comp", "Album", "1990", "Compilation")));

        assertThat(collector.mainRelease("a")).contains("EP");
        assertThat(collector.mainRelease("b")).contains("Comp");
    }

    @Test
    void an_artist_with_no_releases_has_no_main_release() {
        when(musicBrainz.browseReleaseGroups("ghost")).thenReturn(List.of());

        assertThat(collector.mainRelease("ghost")).isEmpty();
    }

    /** The real shape: two members, a band they share, and the bridge from it to Jeff Mills. */
    private void stubDrexciya() {
        when(musicBrainz.getArtistRelations(DREXCIYA)).thenReturn(Optional.of(artist(DREXCIYA, "Drexciya", "Group",
                backward("member of band", STINSON, "James Stinson", "Person"),
                backward("member of band", DONALD, "Gerald Donald", "Person"))));
        when(musicBrainz.getArtistRelations(STINSON)).thenReturn(Optional.of(artist(STINSON, "James Stinson", "Person",
                forward("is person", "transllusion", "Transllusion", "Person"),
                forward("is person", "topp", "The Other People Place", "Person"),
                forward("member of band", "elecktroids", "Elecktroids", "Group"),
                forward("member of band", UR, "Underground Resistance", "Group"),
                forward("member of band", DREXCIYA, "Drexciya", "Group"),
                forward("married", "spouse", "Spouse", "Person"))));
        when(musicBrainz.getArtistRelations(DONALD)).thenReturn(Optional.of(donald()));
        when(musicBrainz.getArtistRelations(UR)).thenReturn(Optional.of(artist(UR, "Underground Resistance", "Group",
                backward("member of band", STINSON, "James Stinson", "Person"),
                backward("member of band", DONALD, "Gerald Donald", "Person"),
                backward("member of band", "mills", "Jeff Mills", "Person"))));
    }

    private static MusicBrainzArtistRelationsResponse donald() {
        return artist(DONALD, "Gerald Donald", "Person",
                forward("is person", "arpanet", "Arpanet", "Person"),
                forward("member of band", "doppler", "Dopplereffekt", "Group"),
                forward("member of band", UR, "Underground Resistance", "Group"),
                forward("member of band", DREXCIYA, "Drexciya", "Group"));
    }

    private static MusicBrainzArtistRelationsResponse artist(String id, String name, String type, Relation... relations) {
        return new MusicBrainzArtistRelationsResponse(id, name, type, Arrays.asList(relations));
    }

    private static Relation forward(String type, String id, String name, String artistType) {
        return new Relation(type, "forward", new Artist(id, name, artistType));
    }

    private static Relation backward(String type, String id, String name, String artistType) {
        return new Relation(type, "backward", new Artist(id, name, artistType));
    }

    private static ReleaseGroup group(String title, String primaryType, String date, String... secondaryTypes) {
        return new ReleaseGroup("id-" + title, 100, primaryType, Arrays.asList(secondaryTypes), title, date,
                List.of(), List.of(), List.of());
    }
}

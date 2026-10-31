package com.sashkomusic.agents.discovery;

import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzClient;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzSimilarArtistsResponse;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzArtistRelationsResponse;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzReleaseGroupSearchResponse.ReleaseGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Works out who a seed artist is really connected to. Co-listen data alone drifts toward whatever
 * many people play, and for a small scene the better signal is the actual people: their aliases,
 * their bands and whoever they shared those bands with. These are facts from MusicBrainz, so no LLM
 * is involved and nothing here can be invented.
 */
@Component
@RequiredArgsConstructor
public class RelatedArtistsCollector {

    /** A seed like Underground Resistance has 17 members, and each one costs a rate-limited call. */
    private static final int MAX_PEOPLE = 4;
    private static final int MAX_BRIDGE_GROUPS = 4;

    private static final String IS_PERSON = "is person";
    private static final String MEMBER_OF_BAND = "member of band";
    private static final String FORWARD = "forward";
    private static final String BACKWARD = "backward";
    private static final String PERSON_TYPE = "Person";

    /** Primary types worth opening with, best first. Anything else ranks after them. */
    private static final List<String> RELEASE_TYPE_PRIORITY = List.of("Album", "EP", "Single");
    /** Keeps a compilation or a live record behind every plain release, whatever their primary types. */
    private static final int SECONDARY_TYPE_PENALTY = RELEASE_TYPE_PRIORITY.size() + 1;

    /**
     * Direct link, bridge, direct link, co-listen, round and round. Bridges go early because the
     * interesting ones are few, and co-listen gets the fewest turns because it drifts to the famous.
     * Indexes {@link #merge}'s sources.
     */
    private static final int[] SOURCE_PATTERN = {0, 1, 0, 2};

    /** A shared band or two says why; a Jeff Mills-sized list of them only stretches the card. */
    private static final int MAX_SOURCES_NAMED = 2;

    private final MusicBrainzClient musicBrainzClient;
    private final ListenBrainzClient listenBrainzClient;

    /** One related artist, with the reason it is related in words the card can show. */
    public record RelatedArtist(String mbid, String name, String reason) {}

    /** How a node is linked to its sources. Each format takes the sources' names. */
    private enum Link {
        ALIAS("псевдонім %s"),
        BAND("%s у складі"),
        MEMBER("учасник %s"),
        BANDMATE("разом у %s");

        private final String format;

        Link(String format) {
            this.format = format;
        }
    }

    private static final class Node {
        private final String mbid;
        private final String name;
        private final Link link;
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();

        private Node(String mbid, String name, Link link) {
            this.mbid = mbid;
            this.name = name;
            this.link = link;
        }

        private RelatedArtist toRelatedArtist() {
            String named = sources.stream().limit(MAX_SOURCES_NAMED).collect(Collectors.joining(" і "));
            return new RelatedArtist(mbid, name, link.format.formatted(named));
        }
    }

    private record Person(String mbid, String name, List<MusicBrainzArtistRelationsResponse.Relation> relations) {}

    /** Everyone connected to the seed, best first. Never contains the seed itself. */
    public List<RelatedArtist> collect(String seedMbid) {
        List<ListenBrainzSimilarArtistsResponse> coListened = listenBrainzClient.findSimilarArtists(seedMbid).stream()
                .filter(artist -> artist.artistMbid() != null && !artist.artistMbid().isBlank())
                .sorted(Comparator.comparingInt(ListenBrainzSimilarArtistsResponse::score).reversed())
                .toList();
        Set<String> coListenedIds = coListened.stream()
                .map(ListenBrainzSimilarArtistsResponse::artistMbid)
                .collect(Collectors.toSet());

        List<Node> direct = new ArrayList<>();
        List<Node> bridges = new ArrayList<>();
        musicBrainzClient.getArtistRelations(seedMbid).ifPresent(seed -> {
            List<Person> people = peopleBehind(seed);
            direct.addAll(directLinks(seed, people));
            bridges.addAll(bandmates(seedMbid, people, direct, coListenedIds));
        });

        // A real connection says more than a co-listen, so an artist with both shows the connection.
        Set<String> connected = Stream.concat(direct.stream(), bridges.stream())
                .map(node -> node.mbid)
                .collect(Collectors.toSet());
        List<RelatedArtist> fromCoListen = coListened.stream()
                .filter(artist -> !connected.contains(artist.artistMbid()))
                .map(artist -> new RelatedArtist(artist.artistMbid(), artist.name(), "часто слухають разом"))
                .toList();
        return merge(seedMbid, List.of(ranked(direct, coListenedIds), ranked(bridges, coListenedIds), fromCoListen));
    }

    /** The release to show for an artist: the earliest of its plain albums, else EPs, else singles. */
    public Optional<String> mainRelease(String artistMbid) {
        return musicBrainzClient.browseReleaseGroups(artistMbid).stream()
                .filter(group -> group.title() != null && !group.title().isBlank())
                .min(Comparator.comparingInt(RelatedArtistsCollector::releaseRank)
                        .thenComparing(RelatedArtistsCollector::releaseDate))
                .map(ReleaseGroup::title);
    }

    private static int releaseRank(ReleaseGroup group) {
        int primary = RELEASE_TYPE_PRIORITY.indexOf(group.primaryType());
        int rank = primary < 0 ? RELEASE_TYPE_PRIORITY.size() : primary;
        boolean plain = group.secondaryTypes() == null || group.secondaryTypes().isEmpty();
        return plain ? rank : rank + SECONDARY_TYPE_PENALTY;
    }

    private static String releaseDate(ReleaseGroup group) {
        String date = group.firstReleaseDate();
        return date == null || date.isBlank() ? "9999" : date;
    }

    /** The humans behind the seed, whatever form it takes: a person, a group, or one person's alias. */
    private List<Person> peopleBehind(MusicBrainzArtistRelationsResponse seed) {
        List<Person> people = new ArrayList<>();
        if (PERSON_TYPE.equals(seed.type())) {
            people.add(new Person(seed.id(), seed.name(), relationsOf(seed)));
        }
        relationsOf(seed).stream()
                .filter(relation -> BACKWARD.equals(relation.direction()) && isPersonLink(relation))
                .filter(relation -> relation.artist() != null)
                .limit(MAX_PEOPLE)
                .forEach(relation -> musicBrainzClient.getArtistRelations(relation.artist().id()).ifPresent(person ->
                        people.add(new Person(person.id(), person.name(), relationsOf(person)))));
        return people;
    }

    private static boolean isPersonLink(MusicBrainzArtistRelationsResponse.Relation relation) {
        return MEMBER_OF_BAND.equals(relation.type()) || IS_PERSON.equals(relation.type());
    }

    private static List<MusicBrainzArtistRelationsResponse.Relation> relationsOf(MusicBrainzArtistRelationsResponse artist) {
        return artist.relations() == null ? List.of() : artist.relations();
    }

    /** The people themselves plus every alias and band they have. A band two people share is one node. */
    private static List<Node> directLinks(MusicBrainzArtistRelationsResponse seed, List<Person> people) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Person person : people) {
            if (!person.mbid().equals(seed.id())) {
                addNode(nodes, person.mbid(), person.name(), Link.MEMBER, seed.name());
            }
            person.relations().stream()
                    .filter(relation -> FORWARD.equals(relation.direction()) && relation.artist() != null)
                    .filter(RelatedArtistsCollector::isPersonLink)
                    .forEach(relation -> {
                        Link link = IS_PERSON.equals(relation.type()) ? Link.ALIAS : Link.BAND;
                        addNode(nodes, relation.artist().id(), relation.artist().name(), link, person.name());
                    });
        }
        nodes.remove(seed.id());
        return new ArrayList<>(nodes.values());
    }

    private static void addNode(Map<String, Node> nodes, String mbid, String name, Link link, String source) {
        nodes.computeIfAbsent(mbid, id -> new Node(id, name, link)).sources.add(source);
    }

    /**
     * Other members of the seed's bands — how Drexciya reaches Jeff Mills through Underground
     * Resistance. Bands the seed's people share come first, since those are the strongest ties.
     */
    private List<Node> bandmates(String seedMbid, List<Person> people, List<Node> direct, Set<String> coListened) {
        Set<String> known = people.stream().map(Person::mbid).collect(Collectors.toCollection(HashSet::new));
        known.add(seedMbid);

        Map<String, Node> nodes = new LinkedHashMap<>();
        rankedNodes(direct, coListened).stream()
                .filter(node -> node.link == Link.BAND)
                .limit(MAX_BRIDGE_GROUPS)
                .forEach(band -> musicBrainzClient.getArtistRelations(band.mbid).ifPresent(group ->
                        relationsOf(group).stream()
                                .filter(relation -> MEMBER_OF_BAND.equals(relation.type())
                                        && BACKWARD.equals(relation.direction()) && relation.artist() != null)
                                .filter(relation -> !known.contains(relation.artist().id()))
                                .forEach(relation -> addNode(nodes, relation.artist().id(), relation.artist().name(),
                                        Link.BANDMATE, group.name()))));
        return new ArrayList<>(nodes.values());
    }

    /** Strongest ties first, then one from each source in turn so one prolific source can't fill every slot. */
    private static List<RelatedArtist> ranked(Collection<Node> nodes, Set<String> coListened) {
        return rankedNodes(nodes, coListened).stream().map(Node::toRelatedArtist).toList();
    }

    private static List<Node> rankedNodes(Collection<Node> nodes, Set<String> coListened) {
        List<Node> ranked = new ArrayList<>();
        Map<String, Deque<Node>> bySource = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (node.sources.size() > 1 || coListened.contains(node.mbid)) {
                ranked.add(node);
            } else {
                bySource.computeIfAbsent(node.sources.getFirst(), source -> new ArrayDeque<>()).add(node);
            }
        }
        while (!bySource.isEmpty()) {
            bySource.values().removeIf(queue -> {
                ranked.add(queue.removeFirst());
                return queue.isEmpty();
            });
        }
        return ranked;
    }

    /** Weaves the sources together by {@link #SOURCE_PATTERN}. An exhausted source just loses its turns. */
    private static List<RelatedArtist> merge(String seedMbid, List<List<RelatedArtist>> sources) {
        List<Deque<RelatedArtist>> queues = sources.stream().<Deque<RelatedArtist>>map(ArrayDeque::new).toList();
        Map<String, RelatedArtist> merged = new LinkedHashMap<>();
        while (queues.stream().anyMatch(queue -> !queue.isEmpty())) {
            for (int source : SOURCE_PATTERN) {
                RelatedArtist next = queues.get(source).pollFirst();
                if (next != null && !next.mbid().equals(seedMbid)) {
                    merged.putIfAbsent(next.mbid(), next);
                }
            }
        }
        return List.copyOf(merged.values());
    }
}

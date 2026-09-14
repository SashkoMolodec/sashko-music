package com.sashkomusic.agents.discovery;

import com.sashkomusic.mainagent.search.AggregatedSearchService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzClient;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzSimilarArtistsResponse;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiscoveryAgentTools {

    /** Three stacks is what still reads as an answer in a Telegram chat rather than as a dump. */
    private static final int MAX_RECOMMENDATIONS = 3;
    private static final int MAX_SIMILAR_ARTISTS_TRIED = 6;

    private final AggregatedSearchService aggregatedSearch;
    private final SearchContextService searchContextService;
    private final SearchRequestExtractor searchRequestExtractor;
    private final ReleaseRecommender releaseRecommender;
    private final MusicBrainzClient musicBrainzClient;
    private final ListenBrainzClient listenBrainzClient;

    @Tool("""
            Pinpoint search for ONE specific thing — a release, a track, or an artist's records.
            Queries MusicBrainz, Discogs and Bandcamp simultaneously and keeps only results that are
            actually the artist/title asked for; vague matches are dropped rather than shown.
            Each call builds its own card stack, so call it once per release you want to show
            (at most 3 per answer). Resolve the query from the conversation first: if the user says
            "того самого артиста, але 90-ті", pass "<that artist> 1990-1999", never a pronoun.
            If it returns nothing, call it again with fewer filters — drop year/label/style first.
            """)
    public String search(
            @P("resolved query: artist + title (+ optional year/label), e.g. 'Adjust (BE) Mist'") String query,
            @ToolMemoryId String conversationId) {
        MetadataSearchRequest request = extractRequest(query);
        return runStack(conversationId, request, query, null);
    }

    @Tool("""
            Recommend NEW music for an open-ended ask — "порадь щось", "що послухати з детройт-техно",
            "хочу дарк ембієнт 90-х". Researches concrete releases on the web first, then runs the
            pinpoint search for each one, so the user gets up to 3 separate card stacks.
            Do NOT recommend releases from your own memory instead of calling this.
            """)
    public String exploreAndRecommend(
            @P("what to recommend, e.g. 'класичне detroit techno, витоки'") String topic,
            @ToolMemoryId String conversationId) {
        List<Candidate> candidates = researchCandidates(topic);
        if (candidates.isEmpty()) {
            return "research returned no concrete releases for '" + topic + "' — ask the user to narrow the topic";
        }
        return buildStacks(conversationId, candidates, "researched picks for '" + topic + "'");
    }

    @Tool("""
            Find releases similar to a given artist/release, or to the release currently in view — for
            "хочу схоже на X", "рекомендуй щось подібне", "similar to X". Uses ListenBrainz co-listen
            data (real similarity, never invented), then runs the pinpoint search for the top related
            artists — the user gets one card stack per artist. Never answer such asks from memory.
            """)
    public String findSimilar(
            @P("artist or release name to find similar music to, or empty to use the release currently in view") String seedQuery,
            @ToolMemoryId String conversationId) {
        String seedArtist;
        if (seedQuery == null || seedQuery.isBlank()) {
            ReleaseMetadata current = currentRelease(conversationId);
            if (current == null) {
                return "no release in view and no seed given — search for something first or name an artist";
            }
            seedArtist = current.artist();
        } else {
            seedArtist = seedQuery.trim();
        }

        Optional<String> mbid = musicBrainzClient.findArtistMbid(seedArtist);
        if (mbid.isEmpty()) {
            return "could not resolve artist '" + seedArtist + "' on MusicBrainz — ask the user to clarify the name";
        }

        List<String> similarArtists = listenBrainzClient.findSimilarArtists(mbid.get()).stream()
                .sorted(Comparator.comparingInt(ListenBrainzSimilarArtistsResponse::score).reversed())
                .limit(MAX_SIMILAR_ARTISTS_TRIED)
                .map(ListenBrainzSimilarArtistsResponse::name)
                .toList();
        if (similarArtists.isEmpty()) {
            return "ListenBrainz has no similar-artist data for '" + seedArtist + "'";
        }

        List<Candidate> candidates = similarArtists.stream()
                .map(artist -> new Candidate(artist, "", artist))
                .toList();
        return buildStacks(conversationId, candidates, "artists related to " + seedArtist + " by ListenBrainz co-listen data");
    }

    @Tool("""
            Widen the last search — same query, validation switched off, so everything the catalogs
            returned is shown. Use when the user says "копай", "ще копай", "покажи все", "dig deeper",
            i.e. the pinpoint answer was too narrow or missed a differently-spelled pressing.
            """)
    public String digDeeper(@ToolMemoryId String conversationId) {
        MetadataSearchRequest request;
        String lastQuery;
        try {
            lastQuery = searchContextService.getRawInput(conversationId);
            request = searchContextService.getSearchRequest(conversationId);
        } catch (Exception e) {
            return "нема попереднього пошуку — спочатку знайди щось";
        }
        if (request == null) {
            request = extractRequest(lastQuery);
        }

        var aggregated = aggregatedSearch.searchLoose(request);
        if (aggregated.isEmpty()) {
            return "nothing at all on any source for '" + lastQuery + "'";
        }
        searchContextService.openStack(conversationId, lastQuery, request, "усе підряд", aggregated.releases());
        return "found %d unfiltered results for '%s' — card stack shown".formatted(aggregated.releases().size(), lastQuery);
    }

    @Tool("Get the track list of the release the user is currently viewing. Use when the user asks about tracks, tracklist, or song names of the current release.")
    public String getTrackList(@ToolMemoryId String conversationId) {
        List<ReleaseMetadata> results;
        try {
            results = searchContextService.getSearchResults(conversationId);
        } catch (Exception e) {
            return "no release context — search for a release first";
        }
        if (results.isEmpty()) return "no release context — search for a release first";

        // currentPage is saved by ReleaseSearchFlowService under the main conversationId (without ":d")
        String mainId = conversationId.endsWith(":d") ? conversationId.substring(0, conversationId.length() - 2) : conversationId;
        int page = searchContextService.getCurrentPage(mainId);
        ReleaseMetadata r = results.get(Math.min(page, results.size() - 1));
        ReleaseMetadata withTracks = searchContextService.getMetadataWithTracks(r.id(), conversationId);
        if (withTracks != null) r = withTracks;

        if (r.tracks() == null || r.tracks().isEmpty()) {
            return "track list not available for this release";
        }
        String tracks = r.tracks().stream()
                .map(t -> t.number() + ". " + t.title())
                .collect(Collectors.joining("\n"));
        return "%s — %s (%s)\n%s".formatted(r.artist(), r.title(), r.getYearsDisplay(), tracks);
    }

    // Research/factual questions ("розкажи про X", "хто такий X", "що за лейбл Y") are no longer a
    // local @Tool — DiscoveryAgent's model bean (discoveryChatModel, see AgentModelsConfig) carries
    // Anthropic's server-side web_search tool, which the model calls directly with results returned
    // in the same API response. No client-side execution, no jsoup/DuckDuckGo scraping needed.

    /** One recommended thing to look up: the pinpoint search's input plus the label its stack wears. */
    private record Candidate(String artist, String title, String label) {}

    private List<Candidate> researchCandidates(String topic) {
        String answer;
        try {
            answer = releaseRecommender.recommend(topic);
        } catch (Exception e) {
            log.warn("Release recommender failed for topic '{}': {}", topic, e.getMessage());
            return List.of();
        }
        if (answer == null || answer.isBlank()) return List.of();

        List<Candidate> candidates = new ArrayList<>();
        for (String line : answer.split("\\R")) {
            // The model is asked for "Artist — Title" lines; anything else on the line is not a pick.
            String[] parts = line.trim().split("\\s*[—–]\\s*|\\s+-\\s+", 2);
            if (parts.length != 2) continue;
            String artist = parts[0].replaceFirst("^\\d+[.)]\\s*", "").trim();
            String title = parts[1].trim();
            if (artist.isBlank() || title.isBlank()) continue;
            candidates.add(new Candidate(artist, title, artist + " — " + title));
        }
        return candidates;
    }

    /**
     * Turns researched picks into one card stack each. Candidates that nothing in the catalogs backs
     * up are skipped silently — a recommendation the user cannot open is worse than one fewer stack.
     */
    private String buildStacks(String conversationId, List<Candidate> candidates, String provenance) {
        List<String> found = new ArrayList<>();
        int attempts = 0;
        for (Candidate candidate : candidates) {
            // Each attempt is a full three-catalog search; one spare covers a pick the catalogs
            // don't have, and stops "6 similar artists" from turning into a half-minute wait.
            if (found.size() >= MAX_RECOMMENDATIONS || attempts++ > MAX_RECOMMENDATIONS) break;
            MetadataSearchRequest request = new MetadataSearchRequest(
                    null, candidate.artist(), candidate.title(), "", DateRange.empty(),
                    "", "", "", "", "", "", "");
            var aggregated = aggregatedSearch.search(request);
            if (aggregated.isEmpty()) {
                log.info("Recommendation '{}' had no catalog match — skipped", candidate.label());
                continue;
            }
            searchContextService.openStack(conversationId, candidate.label(), request,
                    candidate.label(), aggregated.releases());
            found.add("%s (%d releases)".formatted(candidate.label(), aggregated.releases().size()));
        }

        if (found.isEmpty()) {
            return "none of the researched picks could be confirmed in the catalogs — say so and ask for a narrower ask";
        }
        return "showed %d card stacks — %s. Source: %s. Write a 2-4 sentence intro explaining the picks; do NOT list them again."
                .formatted(found.size(), String.join("; ", found), provenance);
    }

    private String runStack(String conversationId, MetadataSearchRequest request, String rawInput, String label) {
        var aggregated = aggregatedSearch.search(request);
        if (aggregated.isEmpty()) {
            // Keep the query around even though it confirmed nothing — digDeeper widens THIS query.
            searchContextService.rememberQuery(conversationId, rawInput, request);
            String filters = describeFilters(request);
            if (aggregated.rawCount() > 0) {
                return ("catalogs returned %d results for '%s' but none were the requested artist/title "
                        + "(filters: %s). Retry search() with fewer filters, or confirm the spelling with the user.")
                        .formatted(aggregated.rawCount(), rawInput, filters);
            }
            return "nothing found for '%s' (filters: %s). Retry search() with fewer filters — drop year/label/style first."
                    .formatted(rawInput, filters);
        }
        searchContextService.openStack(conversationId, rawInput, request, label, aggregated.releases());
        String sources = aggregated.sources().stream().map(SearchEngine::getName).collect(Collectors.joining(", "));
        return "found %d matching releases for '%s' on %s — card stack shown to the user"
                .formatted(aggregated.releases().size(), rawInput, sources);
    }

    private static String describeFilters(MetadataSearchRequest request) {
        List<String> parts = new ArrayList<>();
        if (!request.artist().isEmpty()) parts.add("artist=" + request.artist());
        if (!request.release().isEmpty()) parts.add("release=" + request.release());
        if (!request.recording().isEmpty()) parts.add("track=" + request.recording());
        if (request.dateRange() != null && !request.dateRange().isEmpty()) parts.add("year=" + request.dateRange().toDiscogsParam());
        if (!request.style().isEmpty()) parts.add("style=" + request.style());
        if (!request.label().isEmpty()) parts.add("label=" + request.label());
        if (!request.country().isEmpty()) parts.add("country=" + request.country());
        if (!request.format().isEmpty()) parts.add("format=" + request.format());
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    /** The release the user is looking at right now — the active stack's current page. */
    private ReleaseMetadata currentRelease(String conversationId) {
        try {
            List<ReleaseMetadata> results = searchContextService.getSearchResults(conversationId);
            if (results.isEmpty()) return null;
            String mainId = conversationId.endsWith(":d") ? conversationId.substring(0, conversationId.length() - 2) : conversationId;
            int page = searchContextService.getCurrentPage(mainId);
            return results.get(Math.min(page, results.size() - 1));
        } catch (Exception e) {
            return null;
        }
    }

    private MetadataSearchRequest extractRequest(String query) {
        try {
            return searchRequestExtractor.extract(query);
        } catch (Exception e) {
            log.warn("SearchRequestExtractor failed for query '{}': {} — falling back to raw query", query, e.getMessage());
            return new MetadataSearchRequest(null, query, "", "", DateRange.empty(), "", "", "", "", "", "", "");
        }
    }

    /** Direct entry point for the no-LLM path — same aggregated search, no engine choice to make. */
    String runSearch(String query, String conversationId) {
        log.info("Discovery tool: direct search query='{}' conversationId={}", query, conversationId);
        return runStack(conversationId, extractRequest(query), query, null);
    }
}

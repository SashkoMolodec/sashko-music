package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzClient;
import com.sashkomusic.mainagent.search.client.listenbrainz.ListenBrainzSimilarArtistsResponse;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import com.sashkomusic.mainagent.search.WebSearchService;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiscoveryAgentTools {

    private static final int MAX_SIMILAR_ARTISTS_TRIED = 6;
    private static final int MAX_SIMILAR_RELEASES = 12;

    private final Map<SearchEngine, SearchEngineService> engines;
    private final SearchContextService searchContextService;
    private final SearchRequestExtractor searchRequestExtractor;
    private final WebSearchService webSearchService;
    private final ChatResponseAccumulator accumulator;
    private final MusicBrainzClient musicBrainzClient;
    private final ListenBrainzClient listenBrainzClient;

    @Tool("""
            Search for music releases. Tries MusicBrainz → Discogs → Bandcamp in order (Discogs first for
            style/year browse queries with no specific title, since it carries label data), stops at first hit.
            Use this for ANY music search request. Do NOT call this multiple times for the same query.
            Returns found releases count and source, or asks for clarification if nothing found anywhere.
            """)
    public String search(
            @P("The user's search query as-is, e.g. 'паліндром 2019', 'Aphex Twin vinyl 90s'") String query,
            @ToolMemoryId String conversationId) {
        MetadataSearchRequest request = extractRequest(query);
        for (SearchEngine engine : engineOrder(request)) {
            try {
                String result = runSearchWithRequest(engine, request, query, conversationId);
                if (isSuccess(result)) {
                    log.info("Found results on {}", engine);
                    return result;
                }
                log.info("Nothing on {} — trying next engine", engine);
            } catch (Exception e) {
                log.warn("Search on {} failed with exception: {} — skipping to next engine", engine, e.getMessage());
            }
        }
        return "not found on any source. ask the user to provide more context: year, label, genre, or country.";
    }

    /**
     * Discogs carries per-release label data that MusicBrainz release-group search doesn't
     * (label is a /release-level field on MB, not /release-group), so browse-style queries
     * ("trance 1994", no specific title) go to Discogs first — LOOKUP queries (artist/title known)
     * keep MusicBrainz first since it dedupes across pressings better.
     */
    private List<SearchEngine> engineOrder(MetadataSearchRequest request) {
        if (request.isBrowseQuery()) {
            return List.of(SearchEngine.DISCOGS, SearchEngine.MUSICBRAINZ, SearchEngine.BANDCAMP);
        }
        return List.of(SearchEngine.values());
    }

    private boolean isSuccess(String result) {
        return result != null && result.startsWith("found ");
    }

    @Tool("Dig deeper — search the same query on the next source in the chain. Use when user says 'копай', 'ще копай', 'try another source', 'dig deeper', or any 'look further' intent.")
    public String digDeeper(@ToolMemoryId String conversationId) {
        String lastQuery;
        SearchEngine lastEngine;
        try {
            lastQuery = searchContextService.getRawInput(conversationId);
            lastEngine = searchContextService.getSource(conversationId);
        } catch (Exception e) {
            return "нема попереднього пошуку — спочатку знайди щось";
        }
        SearchEngine[] values = SearchEngine.values();
        SearchEngine nextEngine = values[(lastEngine.ordinal() + 1) % values.length];
        log.info("Digging deeper: query='{}' previous={} next={}", lastQuery, lastEngine, nextEngine);
        return runSearch(nextEngine, lastQuery, conversationId);
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

    @Tool("""
            Find releases similar to a given artist/release, or to the release currently in view — for
            "хочу схоже на X", "рекомендуй щось подібне", "similar to X", "recommend something like this".
            Uses ListenBrainz co-listen data (real similarity, never invented) to find related artists, then
            looks up their releases on MusicBrainz. Do NOT answer "recommend similar" questions from your own
            knowledge — always call this tool; if it finds nothing, say so instead of guessing artists.
            """)
    public String findSimilar(
            @P("artist or release name to find similar music to, or empty to use the release currently in view") String seedQuery,
            @ToolMemoryId String conversationId) {
        String seedArtist;
        String seedLabel;
        if (seedQuery == null || seedQuery.isBlank()) {
            ReleaseMetadata current = currentRelease(conversationId);
            if (current == null) {
                return "no release in view and no seed given — search for something first or name an artist";
            }
            seedArtist = current.artist();
            seedLabel = current.artist() + " — " + current.title();
        } else {
            seedArtist = seedQuery.trim();
            seedLabel = seedArtist;
        }

        Optional<String> mbid = musicBrainzClient.findArtistMbid(seedArtist);
        if (mbid.isEmpty()) {
            return "could not resolve artist '" + seedArtist + "' on MusicBrainz — ask the user to clarify the name";
        }

        List<ListenBrainzSimilarArtistsResponse> similarArtists = listenBrainzClient.findSimilarArtists(mbid.get()).stream()
                .sorted(Comparator.comparingInt(ListenBrainzSimilarArtistsResponse::score).reversed())
                .limit(MAX_SIMILAR_ARTISTS_TRIED)
                .toList();
        if (similarArtists.isEmpty()) {
            return "ListenBrainz has no similar-artist data for '" + seedArtist + "'";
        }

        List<ReleaseMetadata> combined = new ArrayList<>();
        List<String> matchedArtists = new ArrayList<>();
        var mbEngine = engines.get(SearchEngine.MUSICBRAINZ);
        for (var similar : similarArtists) {
            if (combined.size() >= MAX_SIMILAR_RELEASES) break;
            MetadataSearchRequest request = new MetadataSearchRequest(
                    null, similar.name(), "", "", DateRange.empty(), "", "", "", "", "", "", "");
            List<ReleaseMetadata> releases = mbEngine.searchReleases(request);
            // Pick the highest-scoring match, not the first — an unconstrained artist-name-only
            // query can return same-name collisions from totally unrelated artists.
            releases.stream()
                    .max(Comparator.comparingInt(ReleaseMetadata::score))
                    .ifPresent(best -> {
                        combined.add(best);
                        matchedArtists.add(similar.name());
                    });
        }

        if (combined.isEmpty()) {
            String names = similarArtists.stream().map(ListenBrainzSimilarArtistsResponse::name).collect(Collectors.joining(", "));
            return "found similar artists (" + names + ") but none have a catalog match — ask the user to try a specific one";
        }

        searchContextService.saveSearchContext(conversationId, SearchEngine.MUSICBRAINZ,
                "схоже на " + seedLabel, null, combined);
        return "found %d releases similar to %s, via related artists: %s"
                .formatted(combined.size(), seedLabel, String.join(", ", matchedArtists));
    }

    /** Same "release the user is currently viewing" resolution as {@link #getTrackList}. */
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

    @Tool("""
            Search the web for artist biography, discography, label history, or any factual music info.
            Use for: "розкажи про X", "хто такий X", "що за лейбл Y", "коли заснований Z", "дискографія X",
            "який жанр у X", "що відомо про реліз Y", or any research/info question that catalog search can't answer.
            Do NOT use for finding releases to download — use search() for that.
            """)
    public String webSearch(
            @P("search query, e.g. 'Miles Davis biography', 'Warp Records history', 'Burial discography'") String query,
            @ToolMemoryId String conversationId) {
        String mainId = conversationId.endsWith(":d")
                ? conversationId.substring(0, conversationId.length() - 2)
                : conversationId;
        accumulator.push(mainId, BotResponse.text("🌐 виходимо у світ божий…"));
        log.info("Web search: query='{}'", query);
        return webSearchService.search(query);
    }

    private MetadataSearchRequest extractRequest(String query) {
        try {
            return searchRequestExtractor.extract(query);
        } catch (Exception e) {
            log.warn("SearchRequestExtractor failed for query '{}': {} — falling back to raw query", query, e.getMessage());
            return new MetadataSearchRequest(null, query, "", "", DateRange.empty(), "", "", "", "", "", "", "");
        }
    }

    String runSearch(SearchEngine engine, String query, String conversationId) {
        log.info("Discovery tool: searching {} query='{}' conversationId={}", engine, query, conversationId);
        MetadataSearchRequest request = extractRequest(query);
        return runSearchWithRequest(engine, request, query, conversationId);
    }

    /**
     * Runs a single engine against an already-extracted request. Split out from {@link #runSearch}
     * so {@link #search} can extract the request ONCE per user query and reuse it across all engines
     * in the fallback chain, instead of re-invoking the Haiku extractor on every engine attempt.
     */
    private String runSearchWithRequest(SearchEngine engine, MetadataSearchRequest request, String rawQuery, String conversationId) {
        log.info("Extracted: artist='{}' release='{}' recording='{}' dateRange={} country={} format={}",
                request.artist(), request.release(), request.recording(),
                request.dateRange(), request.country(), request.format());

        var engineService = engines.get(engine);
        if (engineService == null) {
            return "engine " + engine + " is not configured";
        }
        List<ReleaseMetadata> releases = engineService.searchReleases(request);
        if (releases.isEmpty()) {
            return "no results on " + engine.getName();
        }
        searchContextService.saveSearchContext(conversationId, engine, rawQuery, request, releases);
        return "found %d releases on %s".formatted(releases.size(), engine.getName());
    }
}

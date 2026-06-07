package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import com.sashkomusic.mainagent.search.WebSearchService;
import com.sashkomusic.mainagent.shared.model.DateRange;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiscoveryAgentTools {

    private final Map<SearchEngine, SearchEngineService> engines;
    private final SearchContextService searchContextService;
    private final SearchRequestExtractor searchRequestExtractor;
    private final WebSearchService webSearchService;
    private final ChatResponseAccumulator accumulator;

    @Tool("""
            Search for music releases. Tries MusicBrainz → Discogs → Bandcamp in order, stops at first hit.
            Use this for ANY music search request. Do NOT call this multiple times for the same query.
            Returns found releases count and source, or asks for clarification if nothing found anywhere.
            """)
    public String search(
            @P("The user's search query as-is, e.g. 'паліндром 2019', 'Aphex Twin vinyl 90s'") String query,
            @ToolMemoryId String conversationId) {
        for (SearchEngine engine : SearchEngine.values()) {
            try {
                String result = runSearch(engine, query, conversationId);
                if (!result.startsWith("no results")) {
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
        searchContextService.saveSearchContext(conversationId, engine, query, request, releases);
        return "found %d releases on %s".formatted(releases.size(), engine.getName());
    }
}

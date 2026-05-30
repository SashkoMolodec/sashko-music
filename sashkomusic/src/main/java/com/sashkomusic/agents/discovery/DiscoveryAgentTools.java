package com.sashkomusic.agents.discovery;

import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.sashkomusic.mainagent.shared.model.DateRange;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiscoveryAgentTools {

    private final Map<SearchEngine, SearchEngineService> engines;
    private final SearchContextService searchContextService;
    private final SearchRequestExtractor searchRequestExtractor;

    @Tool("""
            Search for music releases. Tries MusicBrainz → Discogs → Bandcamp in order, stops at first hit.
            Use this for ANY music search request. Do NOT call this multiple times for the same query.
            Returns found releases count and source, or asks for clarification if nothing found anywhere.
            """)
    public String search(
            @P("The user's search query as-is, e.g. 'паліндром 2019', 'Aphex Twin vinyl 90s'") String query,
            @ToolMemoryId long chatId) {
        for (SearchEngine engine : SearchEngine.values()) {
            try {
                String result = runSearch(engine, query, chatId);
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

    @Tool("Returns a short summary of the user's last search in this chat — useful when the user says 'show me more like before'.")
    public String getPreviousSearches(@ToolMemoryId long chatId) {
        try {
            var request = searchContextService.getSearchRequest(chatId);
            var source = searchContextService.getSource(chatId);
            int count = searchContextService.getSearchResults(chatId).size();
            return "last search on %s for artist='%s' release='%s' returned %d releases"
                    .formatted(source, request.artist(), request.release(), count);
        } catch (Exception e) {
            return "no previous search in this chat";
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

    private String runSearch(SearchEngine engine, String query, long chatId) {
        log.info("Discovery tool: searching {} query='{}' chatId={}", engine, query, chatId);
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
        searchContextService.saveSearchContext(chatId, engine, query, request, releases);
        return "found %d releases on %s".formatted(releases.size(), engine.getName());
    }
}

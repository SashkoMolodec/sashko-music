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

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiscoveryAgentTools {

    private final Map<SearchEngine, SearchEngineService> engines;
    private final SearchContextService searchContextService;
    private final SearchRequestExtractor searchRequestExtractor;

    @Tool("Search MusicBrainz. Best for canonical metadata, releases, recordings.")
    public String searchMusicBrainz(
            @P("The user's search query as-is, e.g. 'паліндром 2019', 'Aphex Twin vinyl 90s', 'лінч мій дідо трек'") String query,
            @ToolMemoryId long chatId) {
        return runSearch(SearchEngine.MUSICBRAINZ, query, chatId);
    }

    @Tool("Search Discogs. Best for vinyl, labels, catalog numbers, pressings.")
    public String searchDiscogs(
            @P("The user's search query as-is, e.g. 'паліндром 2019', 'Jeff Mills Axis Records vinyl'") String query,
            @ToolMemoryId long chatId) {
        return runSearch(SearchEngine.DISCOGS, query, chatId);
    }

    @Tool("Search Bandcamp. Best for independent and electronic music.")
    public String searchBandcamp(
            @P("The user's search query as-is, e.g. 'паліндром', 'dark ambient ukraine'") String query,
            @ToolMemoryId long chatId) {
        return runSearch(SearchEngine.BANDCAMP, query, chatId);
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

    private String runSearch(SearchEngine engine, String query, long chatId) {
        log.info("Discovery tool: searching {} query='{}' chatId={}", engine, query, chatId);
        MetadataSearchRequest request = searchRequestExtractor.extract(query);
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

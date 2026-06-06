package com.sashkomusic.mainagent.search;

import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.shared.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SearchContextService {

    private static final String FLOW_KEY = "search";

    // In-memory cache for O(1) release lookups by ID.
    // Rebuilt lazily from ChatStateStore after JVM restart.
    private final Map<String, ReleaseMetadata> releaseMetadataCache = new ConcurrentHashMap<>();

    private final ChatStateStore stateStore;
    private final Map<SearchEngine, SearchEngineService> searchEngines;

    public SearchContextService(ChatStateStore stateStore,
                                Map<SearchEngine, SearchEngineService> searchEngines) {
        this.stateStore = stateStore;
        this.searchEngines = searchEngines;
    }

    public ReleaseMetadata getReleaseMetadata(String releaseId) {
        return releaseMetadataCache.get(releaseId);
    }

    public ReleaseMetadata getReleaseMetadata(String releaseId, String conversationId) {
        ReleaseMetadata cached = releaseMetadataCache.get(releaseId);
        if (cached != null) return cached;
        loadContext(conversationId);
        return releaseMetadataCache.get(releaseId);
    }

    public void saveReleaseMetadata(ReleaseMetadata metadata) {
        releaseMetadataCache.put(metadata.id(), metadata);
    }

    public void saveSearchContext(String conversationId, SearchEngine source, String rawInput,
                                  MetadataSearchRequest request, List<ReleaseMetadata> results) {
        LinkedHashMap<String, ReleaseMetadata> merged = new LinkedHashMap<>();
        results.forEach(r -> merged.put(r.id(), r));

        merged.values().forEach(r -> releaseMetadataCache.put(r.id(), r));
        List<ReleaseMetadata> mergedReleases = new ArrayList<>(merged.values());
        List<String> releaseIds = mergedReleases.stream().map(ReleaseMetadata::id).toList();
        SearchContext context = new SearchContext(source, request, rawInput, releaseIds);
        stateStore.put(conversationId, FLOW_KEY, new SearchState(context, mergedReleases));
    }

    public void validateSession(String conversationId) {
        if (loadContext(conversationId).isEmpty()) {
            throw new SearchSessionExpiredException("Search session not found for conversation: " + conversationId);
        }
    }

    public List<ReleaseMetadata> getSearchResults(String conversationId) {
        SearchContext context = loadContext(conversationId)
                .orElseThrow(() -> new SearchSessionExpiredException("Search session not found for conversation: " + conversationId));
        return context.releaseIds().stream()
                .map(releaseMetadataCache::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public MetadataSearchRequest getSearchRequest(String conversationId) {
        return loadContext(conversationId)
                .orElseThrow(() -> new SearchSessionExpiredException("Search session not found for conversation: " + conversationId))
                .request();
    }

    public SearchEngine getSource(String conversationId) {
        return loadContext(conversationId)
                .orElseThrow(() -> new SearchSessionExpiredException("Search session not found for conversation: " + conversationId))
                .source();
    }

    public String getRawInput(String conversationId) {
        return loadContext(conversationId)
                .orElseThrow(() -> new SearchSessionExpiredException("Search session not found for conversation: " + conversationId))
                .rawInput();
    }

    public ReleaseMetadata getMetadataWithTracks(String releaseId, String conversationId) {
        ReleaseMetadata metadata = getReleaseMetadata(releaseId);
        if (metadata == null) {
            log.warn("No metadata found for releaseId={}", releaseId);
            return null;
        }

        if (metadata.trackTitles() != null && !metadata.trackTitles().isEmpty()) {
            log.debug("Tracks already loaded for releaseId={}", releaseId);
            return metadata;
        }

        log.info("Fetching tracks for releaseId={}, source={}", releaseId, metadata.source());

        try {
            SearchEngineService engine = searchEngines.get(metadata.source());

            List<TrackMetadata> tracks = engine.getTracks(releaseId);

            if (tracks != null && !tracks.isEmpty()) {
                ReleaseMetadata enriched = metadata.withTracks(tracks);
                saveReleaseMetadata(enriched);
                log.info("Successfully fetched {} tracks for releaseId={}", tracks.size(), releaseId);
                return enriched;
            } else {
                log.warn("No tracks returned from {} for releaseId={}", metadata.source(), releaseId);
                return metadata;
            }
        } catch (Exception e) {
            log.error("Failed to fetch tracks for releaseId={}: {}", releaseId, e.getMessage(), e);
            return metadata;
        }
    }

    public void copySearchContext(String fromId, String toId) {
        stateStore.get(fromId, FLOW_KEY, SearchState.class).ifPresent(state ->
                saveSearchContext(toId, state.context().source(), state.context().rawInput(),
                        state.context().request(), state.releases())
        );
    }

    public void clearSearch(String conversationId) {
        stateStore.remove(conversationId, FLOW_KEY);
    }

    public void clearAllCaches() {
        int releasesCount = releaseMetadataCache.size();
        releaseMetadataCache.clear();
        int cleared = stateStore.clearAll(FLOW_KEY);
        log.info("Cleared search state: {} releases cached, {} conversations in store", releasesCount, cleared);
    }

    // Loads SearchContext from store, rebuilds release cache as a side-effect.
    private Optional<SearchContext> loadContext(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, SearchState.class)
                .map(state -> {
                    state.releases().forEach(r -> releaseMetadataCache.putIfAbsent(r.id(), r));
                    return state.context();
                });
    }
}

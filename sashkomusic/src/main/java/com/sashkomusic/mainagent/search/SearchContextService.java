package com.sashkomusic.mainagent.search;

import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.shared.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class SearchContextService {

    private static final String FLOW_KEY = "search";

    private final ChatStateStore stateStore;
    private final Map<SearchEngine, SearchEngineService> searchEngines;

    public SearchContextService(ChatStateStore stateStore,
                                Map<SearchEngine, SearchEngineService> searchEngines) {
        this.stateStore = stateStore;
        this.searchEngines = searchEngines;
    }

    public ReleaseMetadata getReleaseMetadata(String releaseId, String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, SearchState.class)
                .stream()
                .flatMap(state -> state.releases().stream())
                .filter(r -> releaseId.equals(r.id()))
                .findFirst()
                .orElse(null);
    }

    public void saveSearchContext(String conversationId, SearchEngine source, String rawInput,
                                  MetadataSearchRequest request, List<ReleaseMetadata> results) {
        LinkedHashMap<String, ReleaseMetadata> merged = new LinkedHashMap<>();
        results.forEach(r -> merged.put(r.id(), r));
        List<ReleaseMetadata> mergedReleases = new ArrayList<>(merged.values());
        List<String> releaseIds = mergedReleases.stream().map(ReleaseMetadata::id).toList();
        SearchContext context = new SearchContext(source, request, rawInput, releaseIds, 0);
        stateStore.put(conversationId, FLOW_KEY, new SearchState(context, mergedReleases));
    }

    public void validateSession(String conversationId) {
        if (loadContext(conversationId).isEmpty()) {
            throw new SearchSessionExpiredException("Search session not found for conversation: " + conversationId);
        }
    }

    public List<ReleaseMetadata> getSearchResults(String conversationId) {
        return loadState(conversationId).releases();
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
        ReleaseMetadata metadata = getReleaseMetadata(releaseId, conversationId);
        if (metadata == null) {
            log.warn("No metadata found for releaseId={} in conversation={}", releaseId, conversationId);
            return null;
        }

        if (metadata.trackTitles() != null && !metadata.trackTitles().isEmpty()) {
            return metadata;
        }

        log.info("Fetching tracks for releaseId={}, source={}", releaseId, metadata.source());
        try {
            List<TrackMetadata> tracks = searchEngines.get(metadata.source()).getTracks(metadata);
            if (tracks == null || tracks.isEmpty()) {
                log.warn("No tracks returned from {} for releaseId={}", metadata.source(), releaseId);
                return metadata;
            }
            ReleaseMetadata enriched = metadata.withTracks(tracks);
            replaceReleaseInState(conversationId, enriched);
            log.info("Fetched {} tracks for releaseId={}", tracks.size(), releaseId);
            return enriched;
        } catch (Exception e) {
            log.error("Failed to fetch tracks for releaseId={}: {}", releaseId, e.getMessage(), e);
            return metadata;
        }
    }

    public void updateCurrentPage(String conversationId, int page) {
        stateStore.get(conversationId, FLOW_KEY, SearchState.class).ifPresent(state -> {
            SearchContext old = state.context();
            SearchContext updated = new SearchContext(
                    old.source(), old.request(), old.rawInput(), old.releaseIds(), page);
            stateStore.put(conversationId, FLOW_KEY, new SearchState(updated, state.releases()));
        });
    }

    public int getCurrentPage(String conversationId) {
        return loadContext(conversationId).map(SearchContext::currentPage).orElse(0);
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
        int cleared = stateStore.clearAll(FLOW_KEY);
        log.info("Cleared search state: {} conversations", cleared);
    }

    private Optional<SearchContext> loadContext(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, SearchState.class).map(SearchState::context);
    }

    private SearchState loadState(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, SearchState.class)
                .orElseThrow(() -> new SearchSessionExpiredException("Search session not found for conversation: " + conversationId));
    }

    private void replaceReleaseInState(String conversationId, ReleaseMetadata replacement) {
        stateStore.get(conversationId, FLOW_KEY, SearchState.class).ifPresent(state -> {
            List<ReleaseMetadata> updated = state.releases().stream()
                    .map(r -> r.id().equals(replacement.id()) ? replacement : r)
                    .toList();
            stateStore.put(conversationId, FLOW_KEY, new SearchState(state.context(), updated));
        });
    }
}

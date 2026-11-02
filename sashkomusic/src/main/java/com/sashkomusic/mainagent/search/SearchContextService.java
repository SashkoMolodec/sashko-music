package com.sashkomusic.mainagent.search;

import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.shared.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
@Service
public class SearchContextService {

    private static final String FLOW_KEY = "search";

    private final ChatStateStore stateStore;
    private final Map<SearchEngine, SearchEngineService> searchEngines;
    /**
     * One lock per conversation around every read-modify-write of its state. An agent turn (opening
     * stacks) and a button press (paging a stack) of the same chat run in parallel lanes, and
     * ChatStateStore's get-then-put would otherwise let one silently overwrite the other. A
     * ReentrantLock rather than a monitor: no thread pinning, and it is held across a JDBC call.
     */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SearchContextService(ChatStateStore stateStore,
                                Map<SearchEngine, SearchEngineService> searchEngines) {
        this.stateStore = stateStore;
        this.searchEngines = searchEngines;
    }

    /**
     * Looks through every stack, not just the active one: with several stacks on screen the user can
     * hit ⬇️ / 🎧 on a card from any of them, in any order.
     */
    public ReleaseMetadata getReleaseMetadata(String releaseId, String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, SearchState.class)
                .stream()
                .flatMap(state -> Stream.concat(state.releases().stream(),
                        state.stacks().stream().flatMap(stack -> stack.releases().stream())))
                .filter(r -> releaseId.equals(r.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Mirrors one already-resolved release into a different conversation's search-state slot.
     * Needed when the download subflow is routed into a dedicated topic: suitability scoring and
     * "search on another source" re-lookup fetch the release by the conversationId messages are now
     * routed through, which is no longer the conversation the original release search populated.
     */
    public void mirrorReleaseForDownload(String conversationId, ReleaseMetadata metadata) {
        saveSearchContext(conversationId, metadata.source(), "", null, List.of(metadata));
    }

    public void saveSearchContext(String conversationId, SearchEngine source, String rawInput,
                                  MetadataSearchRequest request, List<ReleaseMetadata> results) {
        List<ReleaseMetadata> releases = distinctById(results);
        locked(conversationId, () -> stateStore.put(conversationId, FLOW_KEY,
                new SearchState(contextFor(source, rawInput, request, releases), releases, List.of())));
    }

    /**
     * Starts a fresh turn: the stacks a previous turn left behind stop being part of "what was just
     * found", so the next answer shows only its own. The active stack is kept so `DL:` / `🎧` on
     * already-sent cards keep resolving.
     */
    public void beginStacks(String conversationId) {
        locked(conversationId, () -> stateStore.get(conversationId, FLOW_KEY, SearchState.class).ifPresent(state ->
                stateStore.put(conversationId, FLOW_KEY,
                        new SearchState(state.context(), state.releases(), List.of()))));
    }

    /**
     * Appends one browsable pile of results and makes it the active one.
     *
     * @param label what this stack is an answer to — shown on its cards
     * @return the stack id that {@code CARD:} callbacks carry
     */
    public String openStack(String conversationId, String rawInput, MetadataSearchRequest request,
                            String label, List<ReleaseMetadata> results) {
        return openStack(conversationId, rawInput, request, label, null, results);
    }

    public String openStack(String conversationId, String rawInput, MetadataSearchRequest request,
                            String label, String group, List<ReleaseMetadata> results) {
        List<ReleaseMetadata> releases = distinctById(results);
        SearchEngine source = releases.isEmpty() ? SearchEngine.MUSICBRAINZ : releases.getFirst().source();

        return locked(conversationId, () -> {
            List<SearchStack> stacks = new ArrayList<>(
                    stateStore.get(conversationId, FLOW_KEY, SearchState.class).map(SearchState::stacks).orElse(List.of()));
            String stackId = nextStackId(stacks);
            stacks.add(new SearchStack(stackId, label, group, releases, 0));

            stateStore.put(conversationId, FLOW_KEY,
                    new SearchState(contextFor(source, rawInput, request, releases), releases, stacks));
            return stackId;
        });
    }

    /**
     * Closes a successful agent turn: only the stacks it opened stay "what was just found". Done after
     * the turn rather than at its first stack, so a turn that fails half-way never takes the stacks of
     * cards already in the chat down with it.
     */
    public void retainStacks(String conversationId, Collection<String> stackIds) {
        locked(conversationId, () -> stateStore.get(conversationId, FLOW_KEY, SearchState.class).ifPresent(state ->
                stateStore.put(conversationId, FLOW_KEY, new SearchState(state.context(), state.releases(),
                        state.stacks().stream().filter(stack -> stackIds.contains(stack.id())).toList()))));
    }

    /**
     * Rolls back the stacks of a failed agent turn — nobody saw their cards. The last stack left becomes
     * the active one again; the query stays the last one tried, so ⛏️ widens what was asked.
     */
    public void discardStacks(String conversationId, Collection<String> stackIds) {
        locked(conversationId, () -> stateStore.get(conversationId, FLOW_KEY, SearchState.class).ifPresent(state -> {
            List<SearchStack> kept = state.stacks().stream()
                    .filter(stack -> !stackIds.contains(stack.id()))
                    .toList();
            if (kept.isEmpty()) {
                stateStore.put(conversationId, FLOW_KEY, new SearchState(state.context(), state.releases(), kept));
                return;
            }
            SearchStack active = kept.getLast();
            SearchContext old = state.context();
            SearchContext context = new SearchContext(
                    active.releases().isEmpty() ? old.source() : active.releases().getFirst().source(),
                    old.request(), old.rawInput(),
                    active.releases().stream().map(ReleaseMetadata::id).toList(),
                    active.currentPage());
            stateStore.put(conversationId, FLOW_KEY, new SearchState(context, active.releases(), kept));
        }));
    }

    /** One past the highest live id, not the count: after retainStacks keeps s3 and s4, the next is s5. */
    private static String nextStackId(List<SearchStack> stacks) {
        int max = stacks.stream()
                .map(SearchStack::id)
                .filter(id -> id != null && id.matches("s\\d+"))
                .mapToInt(id -> Integer.parseInt(id.substring(1)))
                .max()
                .orElse(0);
        return "s" + (max + 1);
    }

    /**
     * Records what was asked without touching what is on screen. A pinpoint search that confirmed
     * nothing still has to leave the query behind, otherwise ⛏️ ("покажи все") has nothing to widen —
     * while the cards from the previous search keep working.
     */
    public void rememberQuery(String conversationId, String rawInput, MetadataSearchRequest request) {
        locked(conversationId, () -> rememberQueryLocked(conversationId, rawInput, request));
    }

    private void rememberQueryLocked(String conversationId, String rawInput, MetadataSearchRequest request) {
        SearchState state = stateStore.get(conversationId, FLOW_KEY, SearchState.class)
                .orElseGet(() -> new SearchState(null, List.of(), List.of()));
        SearchContext old = state.context();
        SearchContext context = new SearchContext(
                old != null ? old.source() : SearchEngine.MUSICBRAINZ,
                request,
                rawInput,
                old != null ? old.releaseIds() : List.of(),
                old != null ? old.currentPage() : 0);
        stateStore.put(conversationId, FLOW_KEY, new SearchState(context, state.releases(), state.stacks()));
    }

    public List<SearchStack> getStacks(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, SearchState.class)
                .map(SearchState::stacks)
                .orElse(List.of());
    }

    public Optional<SearchStack> findStack(String conversationId, String stackId) {
        return getStacks(conversationId).stream().filter(s -> s.id().equals(stackId)).findFirst();
    }

    public void updateStackPage(String conversationId, String stackId, int page) {
        locked(conversationId, () -> stateStore.get(conversationId, FLOW_KEY, SearchState.class).ifPresent(state -> {
            List<SearchStack> updated = state.stacks().stream()
                    .map(stack -> stack.id().equals(stackId) ? stack.withPage(page) : stack)
                    .toList();
            stateStore.put(conversationId, FLOW_KEY, new SearchState(state.context(), state.releases(), updated));
        }));
    }

    private static SearchContext contextFor(SearchEngine source, String rawInput,
                                            MetadataSearchRequest request, List<ReleaseMetadata> releases) {
        return new SearchContext(source, request, rawInput, releases.stream().map(ReleaseMetadata::id).toList(), 0);
    }

    private static List<ReleaseMetadata> distinctById(List<ReleaseMetadata> results) {
        LinkedHashMap<String, ReleaseMetadata> merged = new LinkedHashMap<>();
        results.forEach(r -> merged.put(r.id(), r));
        return new ArrayList<>(merged.values());
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
        locked(conversationId, () -> stateStore.get(conversationId, FLOW_KEY, SearchState.class).ifPresent(state -> {
            SearchContext old = state.context();
            SearchContext updated = new SearchContext(
                    old.source(), old.request(), old.rawInput(), old.releaseIds(), page);
            stateStore.put(conversationId, FLOW_KEY, new SearchState(updated, state.releases(), state.stacks()));
        }));
    }

    public int getCurrentPage(String conversationId) {
        return loadContext(conversationId).map(SearchContext::currentPage).orElse(0);
    }

    public void clearSearch(String conversationId) {
        locked(conversationId, () -> stateStore.remove(conversationId, FLOW_KEY));
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
        locked(conversationId, () -> replaceReleaseLocked(conversationId, replacement));
    }

    private void replaceReleaseLocked(String conversationId, ReleaseMetadata replacement) {
        stateStore.get(conversationId, FLOW_KEY, SearchState.class).ifPresent(state -> {
            List<ReleaseMetadata> updated = state.releases().stream()
                    .map(r -> r.id().equals(replacement.id()) ? replacement : r)
                    .toList();
            // Same release can sit in an older stack too — refresh it there so its tracklist is
            // fetched once no matter which stack the user browses it from.
            List<SearchStack> stacks = state.stacks().stream()
                    .map(stack -> new SearchStack(stack.id(), stack.label(), stack.group(),
                            stack.releases().stream()
                                    .map(r -> r.id().equals(replacement.id()) ? replacement : r)
                                    .toList(),
                            stack.currentPage()))
                    .toList();
            stateStore.put(conversationId, FLOW_KEY, new SearchState(state.context(), updated, stacks));
        });
    }

    private void locked(String conversationId, Runnable body) {
        locked(conversationId, () -> {
            body.run();
            return null;
        });
    }

    private <T> T locked(String conversationId, Supplier<T> body) {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, id -> new ReentrantLock());
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }
}

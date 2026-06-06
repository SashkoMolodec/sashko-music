package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.agents.contract.DiscoverRequest;
import com.sashkomusic.agents.contract.DiscoverResult;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoveryAgentService {

    private final DiscoveryAgent discoveryAgent;
    private final DiscoveryAgentTools discoveryAgentTools;
    private final SearchContextService searchContextService;
    private final ReleaseSearchFlowService releaseSearchFlowService;
    private final ChatResponseAccumulator accumulator;

    public DiscoverResult handle(DiscoverRequest request) {
        log.info("Discovery agent handling request: conversationId={}, query='{}', preferred={}",
                request.conversationId(), request.query(), request.preferredEngine());

        if (request.preferredEngine() != null) {
            return handleDirect(request);
        }
        return handleViaLlm(request);
    }

    private DiscoverResult handleViaLlm(DiscoverRequest request) {
        String discoveryMemoryId = request.conversationId() + ":d";
        String rawInputBefore = safeGetRawInput(discoveryMemoryId);
        String summary;
        try {
            summary = discoveryAgent.chat(discoveryMemoryId, request.query());
        } catch (Exception ex) {
            log.error("Discovery agent failure: {}", ex.getMessage(), ex);
            return DiscoverResult.empty("вибач, шось накрилось");
        }
        return buildResult(request.conversationId(), discoveryMemoryId, summary, rawInputBefore);
    }

    private DiscoverResult handleDirect(DiscoverRequest request) {
        String discoveryMemoryId = request.conversationId() + ":d";
        String toolResult = discoveryAgentTools.runSearch(request.preferredEngine(), request.query(), discoveryMemoryId);
        log.info("Direct search on {}: {}", request.preferredEngine(), toolResult);
        return buildResult(request.conversationId(), discoveryMemoryId, toolResult, null);
    }

    private DiscoverResult buildResult(String conversationId, String discoveryMemoryId, String summary, String rawInputBefore) {
        try {
            var releases = searchContextService.getSearchResults(discoveryMemoryId);
            var engine = searchContextService.getSource(discoveryMemoryId);
            if (releases.isEmpty()) {
                return DiscoverResult.empty(summary);
            }
            String rawInputAfter = safeGetRawInput(discoveryMemoryId);
            boolean newSearch = !Objects.equals(rawInputBefore, rawInputAfter);
            if (newSearch || rawInputBefore == null) {
                searchContextService.copySearchContext(discoveryMemoryId, conversationId);
                accumulator.replaceAll(conversationId,
                        releaseSearchFlowService.buildPageResponse(ConversationContext.from(conversationId), 0));
                return DiscoverResult.found(formatForMainAgent(releases, engine), releases, engine);
            } else {
                // No new search (e.g. getTrackList call) — use DiscoveryAgent's summary directly
                return DiscoverResult.found(summary != null ? summary : formatForMainAgent(releases, engine), releases, engine);
            }
        } catch (Exception ex) {
            log.debug("No search context: {}", ex.getMessage());
            return DiscoverResult.empty(summary != null ? summary : "нич не знайшов");
        }
    }

    private String safeGetRawInput(String discoveryMemoryId) {
        try {
            return searchContextService.getRawInput(discoveryMemoryId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatForMainAgent(List<ReleaseMetadata> releases, SearchEngine engine) {
        String engineName = engine != null ? engine.getName() : "unknown";
        String lines = releases.stream().map(r -> {
            String line = "- ";
            if (r.artist() != null && !r.artist().isBlank()) line += r.artist() + " — ";
            line += r.title() != null ? r.title() : "?";
            if (r.years() != null && !r.years().isEmpty()) line += " (" + r.getYearsDisplay() + ")";
            if (r.label() != null && !r.label().isBlank()) line += ", " + r.label();
            if (r.types() != null && !r.types().isEmpty()) line += " [" + r.getTypesDisplay() + "]";
            if (r.tags() != null && !r.tags().isEmpty())
                line += " #" + String.join(" #", r.tags().stream().limit(3).toList());
            return line;
        }).collect(Collectors.joining("\n"));
        return "Found %d releases on %s:\n%s".formatted(releases.size(), engineName, lines);
    }
}

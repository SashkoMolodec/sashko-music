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
import java.util.Map;
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

        // year range
        var allYears = releases.stream()
                .filter(r -> r.years() != null)
                .flatMap(r -> r.years().stream())
                .filter(y -> y != null && y.matches("\\d{4}"))
                .map(Integer::parseInt)
                .sorted()
                .toList();
        String yearsStr = allYears.isEmpty() ? "" :
                allYears.size() == 1 ? allYears.getFirst().toString() :
                        allYears.getFirst() + "–" + allYears.getLast();

        // type breakdown
        var typeCounts = releases.stream()
                .filter(r -> r.types() != null)
                .flatMap(r -> r.types().stream())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t.toLowerCase().trim(), Collectors.counting()));
        String typesStr = typeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));

        // top labels
        String labelsStr = releases.stream()
                .map(ReleaseMetadata::label)
                .filter(l -> l != null && !l.isBlank())
                .collect(Collectors.groupingBy(l -> l, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));

        // top tags
        String tagsStr = releases.stream()
                .filter(r -> r.tags() != null)
                .flatMap(r -> r.tags().stream())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t.toLowerCase().trim(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));

        var sb = new StringBuilder();
        sb.append("Found ").append(releases.size()).append(" releases on ").append(engineName).append(".");
        if (!yearsStr.isEmpty()) sb.append(" Years: ").append(yearsStr).append(".");
        if (!typesStr.isEmpty()) sb.append(" Types: ").append(typesStr).append(".");
        if (!labelsStr.isEmpty()) sb.append(" Labels: ").append(labelsStr).append(".");
        if (!tagsStr.isEmpty()) sb.append(" Tags: ").append(tagsStr).append(".");
        return sb.toString();
    }
}

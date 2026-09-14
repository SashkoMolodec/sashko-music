package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.agents.contract.DiscoverRequest;
import com.sashkomusic.agents.contract.DiscoverResult;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchStack;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
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
    private final SearchContextService searchContextService;
    private final ReleaseSearchFlowService releaseSearchFlowService;
    private final ChatResponseAccumulator accumulator;

    public DiscoverResult handle(DiscoverRequest request) {
        log.info("Discovery agent handling request: conversationId={}, query='{}'",
                request.conversationId(), request.query());

        String discoveryMemoryId = request.conversationId() + ":d";
        // Turn boundary: stacks left over from the previous answer stop counting as "just found", so
        // this turn shows exactly the stacks it built — however many tool calls that took.
        searchContextService.beginStacks(discoveryMemoryId);

        return handleViaLlm(request, discoveryMemoryId);
    }

    private DiscoverResult handleViaLlm(DiscoverRequest request, String discoveryMemoryId) {
        String summary;
        try {
            summary = discoveryAgent.chat(discoveryMemoryId, request.query());
        } catch (Exception ex) {
            log.error("Discovery agent failure: {}", ex.getMessage(), ex);
            return DiscoverResult.empty("вибач, шось накрилось");
        }
        return buildResult(request.conversationId(), discoveryMemoryId, summary);
    }

    private DiscoverResult buildResult(String conversationId, String discoveryMemoryId, String summary) {
        List<SearchStack> stacks = searchContextService.getStacks(discoveryMemoryId).stream()
                .filter(stack -> !stack.releases().isEmpty())
                .toList();

        if (stacks.isEmpty()) {
            // Nothing was searched this turn (tracklist question, research answer, or a miss) —
            // DiscoveryAgent's own words are the answer and the cards on screen stay untouched.
            return DiscoverResult.empty(summary != null ? summary : "нич не знайшов");
        }

        searchContextService.copySearchContext(discoveryMemoryId, conversationId);
        ConversationContext ctx = ConversationContext.from(conversationId);
        List<BotResponse> cards = stacks.stream()
                .flatMap(stack -> releaseSearchFlowService.buildStackResponse(ctx, stack.id(), 0).stream())
                .toList();
        accumulator.replaceAll(conversationId, cards);

        List<ReleaseMetadata> all = stacks.stream().flatMap(stack -> stack.releases().stream()).toList();
        SearchEngine engine = all.isEmpty() ? null : all.getFirst().source();
        return DiscoverResult.found(formatForMainAgent(stacks), all, engine);
    }

    /**
     * What MainAgent reads to write its intro. One stack collapses to the old aggregate line; several
     * are listed separately, because with three stacks on screen the intro has to address all three.
     */
    private static String formatForMainAgent(List<SearchStack> stacks) {
        if (stacks.size() == 1) {
            return describe(stacks.getFirst().releases());
        }
        String perStack = stacks.stream()
                .map(stack -> "• %s — %s".formatted(
                        stack.label() == null || stack.label().isBlank() ? "стос " + stack.id() : stack.label(),
                        describe(stack.releases())))
                .collect(Collectors.joining("\n"));
        return "Showed %d separate card stacks:\n%s\nIntroduce all of them in 2-4 sentences; do not list releases."
                .formatted(stacks.size(), perStack);
    }

    private static String describe(List<ReleaseMetadata> releases) {
        String sources = releases.stream()
                .map(ReleaseMetadata::source)
                .filter(Objects::nonNull)
                .map(SearchEngine::getName)
                .distinct()
                .collect(Collectors.joining(", "));

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

        var typeCounts = releases.stream()
                .filter(r -> r.types() != null)
                .flatMap(r -> r.types().stream())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t.toLowerCase().trim(), Collectors.counting()));
        String typesStr = typeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));

        String labelsStr = releases.stream()
                .map(ReleaseMetadata::label)
                .filter(l -> l != null && !l.isBlank())
                .collect(Collectors.groupingBy(l -> l, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));

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
        sb.append(releases.size()).append(" releases");
        if (!sources.isEmpty()) sb.append(" (").append(sources).append(")");
        sb.append(".");
        if (!yearsStr.isEmpty()) sb.append(" Years: ").append(yearsStr).append(".");
        if (!typesStr.isEmpty()) sb.append(" Types: ").append(typesStr).append(".");
        if (!labelsStr.isEmpty()) sb.append(" Labels: ").append(labelsStr).append(".");
        if (!tagsStr.isEmpty()) sb.append(" Tags: ").append(tagsStr).append(".");
        return sb.toString();
    }
}

package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.agents.contract.DiscoverRequest;
import com.sashkomusic.agents.contract.DiscoverResult;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        searchContextService.clearSearch(discoveryMemoryId);

        String summary;
        try {
            summary = discoveryAgent.chat(discoveryMemoryId, request.query());
        } catch (Exception ex) {
            log.error("Discovery agent failure: {}", ex.getMessage(), ex);
            return DiscoverResult.empty("вибач, шось накрилось");
        }

        return buildResult(request.conversationId(), discoveryMemoryId, summary);
    }

    private DiscoverResult handleDirect(DiscoverRequest request) {
        String discoveryMemoryId = request.conversationId() + ":d";
        searchContextService.clearSearch(discoveryMemoryId);

        String toolResult = discoveryAgentTools.runSearch(request.preferredEngine(), request.query(), discoveryMemoryId);
        log.info("Direct search on {}: {}", request.preferredEngine(), toolResult);

        return buildResult(request.conversationId(), discoveryMemoryId, toolResult);
    }

    private DiscoverResult buildResult(String conversationId, String discoveryMemoryId, String summary) {
        try {
            var releases = searchContextService.getSearchResults(discoveryMemoryId);
            var engine = searchContextService.getSource(discoveryMemoryId);
            if (releases.isEmpty()) {
                return DiscoverResult.empty(summary);
            }
            searchContextService.copySearchContext(discoveryMemoryId, conversationId);
            accumulator.replaceAll(conversationId,
                    releaseSearchFlowService.buildPageResponse(ConversationContext.from(conversationId), 0));
            return DiscoverResult.found(summary, releases, engine);
        } catch (Exception ex) {
            log.debug("No search context: {}", ex.getMessage());
            return DiscoverResult.empty(summary != null ? summary : "нич не знайшов");
        }
    }
}

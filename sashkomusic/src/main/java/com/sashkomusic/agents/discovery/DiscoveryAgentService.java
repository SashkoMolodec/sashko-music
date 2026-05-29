package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.agents.contract.DiscoverRequest;
import com.sashkomusic.agents.contract.DiscoverResult;
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
    private final SearchContextService searchContextService;
    private final ReleaseSearchFlowService releaseSearchFlowService;
    private final ChatResponseAccumulator accumulator;

    public DiscoverResult handle(DiscoverRequest request) {
        log.info("Discovery agent handling request: chatId={}, query='{}', preferred={}",
                request.chatId(), request.query(), request.preferredEngine());

        searchContextService.clearSearch(request.chatId());

        String userMessage = buildUserMessage(request);
        String summary;
        try {
            summary = discoveryAgent.chat(request.chatId(), userMessage);
        } catch (Exception ex) {
            log.error("Discovery agent failure: {}", ex.getMessage(), ex);
            return DiscoverResult.empty("вибач, шось накрилось");
        }

        try {
            var releases = searchContextService.getSearchResults(request.chatId());
            var engine = searchContextService.getSource(request.chatId());
            if (releases.isEmpty()) {
                return DiscoverResult.empty(summary);
            }
            accumulator.pushAll(request.chatId(),
                    releaseSearchFlowService.buildPageResponse(request.chatId(), 0));
            return DiscoverResult.found(summary, releases, engine);
        } catch (Exception ex) {
            log.debug("No search context after discovery: {}", ex.getMessage());
            return DiscoverResult.empty(summary != null ? summary : "нич не знайшов");
        }
    }

    private String buildUserMessage(DiscoverRequest request) {
        if (request.preferredEngine() == null) {
            return request.query();
        }
        return "%s (prefer: %s)".formatted(request.query(), request.preferredEngine().getName());
    }
}

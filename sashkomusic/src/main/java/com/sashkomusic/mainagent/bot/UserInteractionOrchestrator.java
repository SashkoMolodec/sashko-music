package com.sashkomusic.mainagent.bot;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.agents.config.AgentTraceListener;
import com.sashkomusic.agents.main.MainAgent;
import com.sashkomusic.mainagent.download.DownloadContextHolder;
import com.sashkomusic.mainagent.library.DjTagContextHolder;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import com.sashkomusic.mainagent.process.ProcessFolderContextHolder;
import com.sashkomusic.mainagent.bot.newtopic.NewTopicFlowService;
import com.sashkomusic.mainagent.process.ProcessFolderFlowService;
import com.sashkomusic.mainagent.process.ReprocessReleasesFlowService;
import com.sashkomusic.mainagent.search.FileIdCacheService;
import com.sashkomusic.mainagent.search.SearchContextService;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserInteractionOrchestrator {

    private final MainAgent mainAgent;
    private final ChatResponseAccumulator responseAccumulator;
    private final CallbackDispatcher callbackDispatcher;
    private final List<OngoingFlow> ongoingFlows;
    private final ProcessFolderFlowService processFolderFlowService;
    private final NowPlayingFlowService nowPlayingFlowService;
    private final ReprocessReleasesFlowService reprocessReleasesFlowService;
    private final NewTopicFlowService newTopicFlowService;
    private final SearchContextService searchContextService;
    private final FileIdCacheService fileIdCacheService;
    private final DownloadContextHolder downloadContextHolder;
    private final ProcessFolderContextHolder processFolderContextHolder;
    private final DjTagContextHolder djTagContextHolder;
    private final ChatMemoryStore chatMemoryStore;

    public List<BotResponse> handleUserRequest(ConversationContext ctx, String rawInput) {
        if (rawInput.trim().equalsIgnoreCase("стоп")) {
            return clearAllCaches(ctx);
        }

        var res = processOngoingFlow(ctx, rawInput);
        if (!res.isEmpty()) return res;

        res = processUserCommands(ctx, rawInput);
        if (!res.isEmpty()) return res;

        res = runMainAgent(ctx, rawInput);
        logFlowCost();
        return res;
    }

    private void logFlowCost() {
        String flowId = MDC.get("flowId");
        double total = AgentTraceListener.drainFlowCost(flowId);
        if (total > 0) {
            log.info("[flow={}] TOTAL cost=${}", flowId, String.format("%.4f", total));
        }
    }

    public List<BotResponse> handleCallback(ConversationContext ctx, String data, Integer messageId) {
        return callbackDispatcher.dispatch(ctx, data, messageId);
    }

    private List<BotResponse> runMainAgent(ConversationContext ctx, String rawInput) {
        responseAccumulator.begin(ctx.conversationId());
        String summary;
        try {
            summary = mainAgent.chat(ctx.conversationId(), rawInput);
        } catch (Exception ex) {
            log.error("Main agent failure for conversation {}: {}", ctx.conversationId(), ex.getMessage(), ex);
            return List.of(BotResponse.text("шось не то, попробуй ще раз"));
        }

        List<BotResponse> drained = responseAccumulator.drain(ctx.conversationId());
        List<BotResponse> all = new ArrayList<>(drained);
        if (summary != null && !summary.isBlank()) {
            all.add(BotResponse.aiText(summary));
        }
        return all;
    }

    private List<BotResponse> processOngoingFlow(ConversationContext ctx, String rawInput) {
        for (OngoingFlow flow : ongoingFlows) {
            if (flow.appliesTo(ctx)) {
                return flow.handle(ctx, rawInput);
            }
        }
        return Collections.emptyList();
    }

    private List<BotResponse> processUserCommands(ConversationContext ctx, String rawInput) {
        if (rawInput.startsWith("/newtopic")) {
            return newTopicFlowService.handle(ctx, rawInput.substring("/newtopic".length()).trim());
        }
        if (rawInput.startsWith("/np")) {
            return nowPlayingFlowService.nowPlaying(ctx);
        }
        if (rawInput.startsWith("/process")) {
            return processFolderFlowService.handleProcessCommand(ctx, rawInput);
        }
        if (rawInput.startsWith("/reprocess")) {
            ReprocessReleasesFlowService.ReprocessResult result = reprocessReleasesFlowService.handle(ctx, rawInput);
            return List.of(BotResponse.text(result.message()));
        }
        return Collections.emptyList();
    }

    private List<BotResponse> clearAllCaches(ConversationContext ctx) {
        log.info("Clearing all caches for conversation {}", ctx.conversationId());
        fileIdCacheService.clearForConversation(ctx.conversationId());
        downloadContextHolder.clearAllSessions();
        processFolderContextHolder.clearAllContexts();
        djTagContextHolder.clearAllContexts();
        chatMemoryStore.deleteMessages(ctx.conversationId());
        chatMemoryStore.deleteMessages(ctx.conversationId() + ":d");
        return List.of(BotResponse.text("🧹 усі кеші очищено"));
    }
}

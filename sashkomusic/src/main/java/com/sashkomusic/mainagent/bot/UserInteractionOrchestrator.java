package com.sashkomusic.mainagent.bot;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.agents.config.AgentTraceListener;
import com.sashkomusic.agents.main.MainAgent;
import com.sashkomusic.mainagent.download.DownloadContextHolder;
import com.sashkomusic.mainagent.library.DjTagContextHolder;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import com.sashkomusic.mainagent.process.ProcessFolderContextHolder;
import com.sashkomusic.mainagent.process.ProcessFolderFlowService;
import com.sashkomusic.mainagent.process.ReprocessReleasesFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
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
    private final SearchContextService searchContextService;
    private final DownloadContextHolder downloadContextHolder;
    private final ProcessFolderContextHolder processFolderContextHolder;
    private final DjTagContextHolder djTagContextHolder;

    public List<BotResponse> handleUserRequest(long chatId, String rawInput) {
        if (rawInput.trim().equalsIgnoreCase("стоп")) {
            return clearAllCaches();
        }

        var res = processOngoingFlow(chatId, rawInput);
        if (!res.isEmpty()) return res;

        res = processUserCommands(chatId, rawInput);
        if (!res.isEmpty()) return res;

        res = runMainAgent(chatId, rawInput);
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

    public List<BotResponse> handleCallback(long chatId, String data) {
        return callbackDispatcher.dispatch(chatId, data);
    }

    private List<BotResponse> runMainAgent(long chatId, String rawInput) {
        responseAccumulator.begin(chatId);
        String summary;
        try {
            summary = mainAgent.chat(chatId, rawInput);
        } catch (Exception ex) {
            log.error("Maifn agent failure for chat {}: {}", chatId, ex.getMessage(), ex);
            return List.of(BotResponse.text("шось не то, попробуй ще раз"));
        }

        List<BotResponse> drained = responseAccumulator.drain(chatId);
        List<BotResponse> all = new ArrayList<>(drained);
        if (summary != null && !summary.isBlank()) {
            all.add(BotResponse.aiText(summary));
        }
        return all;
    }

    private List<BotResponse> processOngoingFlow(long chatId, String rawInput) {
        for (OngoingFlow flow : ongoingFlows) {
            if (flow.appliesTo(chatId)) {
                return flow.handle(chatId, rawInput);
            }
        }
        return Collections.emptyList();
    }

    private List<BotResponse> processUserCommands(long chatId, String rawInput) {
        if (rawInput.startsWith("/np")) {
            return nowPlayingFlowService.nowPlaying(chatId);
        }
        if (rawInput.startsWith("/process")) {
            return processFolderFlowService.handleProcessCommand(chatId, rawInput);
        }
        if (rawInput.startsWith("/reprocess")) {
            ReprocessReleasesFlowService.ReprocessResult result = reprocessReleasesFlowService.handle(chatId, rawInput);
            return List.of(BotResponse.text(result.message()));
        }
        return Collections.emptyList();
    }

    private List<BotResponse> clearAllCaches() {
        log.info("Clearing all in-memory caches");
        searchContextService.clearAllCaches();
        downloadContextHolder.clearAllSessions();
        processFolderContextHolder.clearAllContexts();
        djTagContextHolder.clearAllContexts();
        return List.of(BotResponse.text("🧹 усі кеші очищено"));
    }
}

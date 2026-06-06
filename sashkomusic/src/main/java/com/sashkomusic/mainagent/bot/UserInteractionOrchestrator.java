package com.sashkomusic.mainagent.bot;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.agents.config.AgentTraceListener;
import com.sashkomusic.agents.config.ChatLogMemoryProvider;
import com.sashkomusic.agents.contract.DiscoverRequest;
import com.sashkomusic.agents.contract.LibraryRequest;
import com.sashkomusic.agents.discovery.DiscoveryAgentService;
import com.sashkomusic.agents.library.LibraryAgentService;
import com.sashkomusic.agents.main.MainAgent;
import com.sashkomusic.libraryagent.config.SublibraryMigrationRunner;
import com.sashkomusic.mainagent.bot.state.ChatLogService;
import com.sashkomusic.mainagent.download.DownloadContextHolder;
import com.sashkomusic.mainagent.library.DjTagContextHolder;
import com.sashkomusic.mainagent.library.LastReleaseContextHolder;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import com.sashkomusic.mainagent.library.RemoveReleaseFlowService;
import com.sashkomusic.mainagent.bot.newtopic.NewTopicFlowService;
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
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserInteractionOrchestrator {

    private final MainAgent mainAgent;
    private final ChatResponseAccumulator responseAccumulator;
    private final CallbackDispatcher callbackDispatcher;
    private final ChatLogService chatLogService;
    private final DiscoveryAgentService discoveryAgentService;
    private final LibraryAgentService libraryAgentService;
    private final List<OngoingFlow> ongoingFlows;
    private final NowPlayingFlowService nowPlayingFlowService;
    private final RemoveReleaseFlowService removeReleaseFlowService;
    private final NewTopicFlowService newTopicFlowService;
    private final SearchContextService searchContextService;
    private final FileIdCacheService fileIdCacheService;
    private final DownloadContextHolder downloadContextHolder;
    private final DjTagContextHolder djTagContextHolder;
    private final LastReleaseContextHolder lastReleaseContextHolder;
    private final SublibraryMigrationRunner sublibraryMigrationRunner;
    private final ChatMemoryStore chatMemoryStore;
    private final ChatLogMemoryProvider chatLogMemoryProvider;

    public List<BotResponse> handleUserRequest(ConversationContext ctx, String rawInput) {
        chatLogService.log(ctx.conversationId(), "user", rawInput, "telegram");

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
        // Strip Telegram's @botname suffix from slash commands (e.g. /newtopic@sashkomusic_test_bot)
        if (rawInput.startsWith("/")) {
            rawInput = rawInput.replaceFirst("^(/\\S+?)@\\S+", "$1");
        }
        if (rawInput.startsWith("/library ")) {
            return handleLibrarySlash(ctx, rawInput.substring("/library ".length()).trim());
        }
        if (rawInput.startsWith("/discovery ")) {
            return handleDiscoverySlash(ctx, rawInput.substring("/discovery ".length()).trim());
        }
        if (rawInput.startsWith("/clearctx")) {
            return clearContext(ctx);
        }
        if (rawInput.startsWith("/newtopic")) {
            return newTopicFlowService.handle(ctx, rawInput.substring("/newtopic".length()).trim());
        }
        if (rawInput.startsWith("/np") || rawInput.trim().equalsIgnoreCase("шо грає 🎵")) {
            return nowPlayingFlowService.nowPlaying(ctx);
        }
        if (rawInput.startsWith("/remove-release")) {
            return removeReleaseFlowService.handleCommand(ctx, rawInput);
        }
        if (rawInput.startsWith("/migrate-sublibs")) {
            SublibraryMigrationRunner.MigrationStats stats = sublibraryMigrationRunner.migrate();
            return List.of(BotResponse.text("🗂 sublibrary migration: " + stats.summary()));
        }
        return Collections.emptyList();
    }

    private List<BotResponse> handleLibrarySlash(ConversationContext ctx, String query) {
        if (query.isEmpty()) return List.of(BotResponse.text("вкажи команду: /library <запит>"));
        responseAccumulator.begin(ctx.conversationId());
        var result = libraryAgentService.handle(new LibraryRequest(ctx.conversationId(), query));
        List<BotResponse> drained = responseAccumulator.drain(ctx.conversationId());
        List<BotResponse> all = new ArrayList<>(drained);
        boolean hasCard = drained.stream().anyMatch(r -> r.buttons() != null || r.buttonRows() != null);
        if (!hasCard && !result.summary().isBlank()) all.add(BotResponse.text(result.summary()));
        String logText = all.stream().map(BotResponse::text).filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        if (!logText.isBlank()) chatLogService.log(ctx.conversationId(), "assistant", logText, "library");
        return all;
    }

    private List<BotResponse> handleDiscoverySlash(ConversationContext ctx, String query) {
        if (query.isEmpty()) return List.of(BotResponse.text("вкажи запит: /discovery <запит>"));
        responseAccumulator.begin(ctx.conversationId());
        var result = discoveryAgentService.handle(DiscoverRequest.of(ctx.conversationId(), query));
        List<BotResponse> drained = responseAccumulator.drain(ctx.conversationId());
        List<BotResponse> all = new ArrayList<>(drained);
        if (result.summary() != null && !result.summary().isBlank()) all.add(BotResponse.aiText(result.summary()));
        String summary = result.summary() != null ? result.summary() : "";
        if (!summary.isBlank()) chatLogService.log(ctx.conversationId(), "assistant", summary, "discovery");
        return all;
    }

    private List<BotResponse> clearContext(ConversationContext ctx) {
        log.info("Clearing context for conversation {}", ctx.conversationId());
        chatLogService.deleteConversation(ctx.conversationId());
        chatLogMemoryProvider.clear(ctx.conversationId());
        chatMemoryStore.deleteMessages(ctx.conversationId());
        chatMemoryStore.deleteMessages(ctx.conversationId() + ":d");
        chatMemoryStore.deleteMessages(ctx.conversationId() + ":lib");
        return List.of(BotResponse.text("🧹 контекст чату очищено"));
    }

    private List<BotResponse> clearAllCaches(ConversationContext ctx) {
        log.info("Clearing all caches for conversation {}", ctx.conversationId());
        fileIdCacheService.clearForConversation(ctx.conversationId());
        downloadContextHolder.clearAllSessions();
        djTagContextHolder.clearAllContexts();
        lastReleaseContextHolder.clear(ctx.conversationId());
        chatLogMemoryProvider.clear(ctx.conversationId());
        chatMemoryStore.deleteMessages(ctx.conversationId());
        chatMemoryStore.deleteMessages(ctx.conversationId() + ":d");
        chatMemoryStore.deleteMessages(ctx.conversationId() + ":lib");
        return List.of(BotResponse.text("🧹 усі кеші очищено"));
    }
}

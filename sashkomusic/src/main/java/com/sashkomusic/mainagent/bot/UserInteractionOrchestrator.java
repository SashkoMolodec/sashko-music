package com.sashkomusic.mainagent.bot;

import com.sashkomusic.agents.config.AgentTraceListener;
import com.sashkomusic.agents.config.MainChatMemoryProvider;
import com.sashkomusic.agents.library.LibraryAgentService;
import com.sashkomusic.agents.main.MainAgentService;
import com.sashkomusic.agents.turn.AgentTurn;
import com.sashkomusic.events.ChatContextClearedEvent;
import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.mainagent.library.MarkersFlowService;
import com.sashkomusic.mainagent.library.NowPlayingAlbumFlowService;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import com.sashkomusic.mainagent.library.SmartlistsFlowService;
import com.sashkomusic.mainagent.bot.newtopic.NewTopicFlowService;
import com.sashkomusic.mainagent.bot.photo.PhotoSearchFlowService;
import com.sashkomusic.mainagent.download.DirectSoulseekSearchFlowService;
import com.sashkomusic.mainagent.process.ProcessFolderFlowService;
import com.sashkomusic.mainagent.process.ReprocessReleasesFlowService;
import com.sashkomusic.mainagent.search.MetadataUrlFetcher;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;

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

    private final MainAgentService mainAgentService;
    private final CallbackDispatcher callbackDispatcher;
    private final LibraryAgentService libraryAgentService;
    private final List<OngoingFlow> ongoingFlows;
    private final NowPlayingFlowService nowPlayingFlowService;
    private final NowPlayingAlbumFlowService nowPlayingAlbumFlowService;
    private final MarkersFlowService markersFlowService;
    private final SmartlistsFlowService smartlistsFlowService;
    private final NewTopicFlowService newTopicFlowService;
    private final DirectSoulseekSearchFlowService directSoulseekSearchFlowService;
    private final MainChatMemoryProvider mainMemoryProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final TelegramLogsChannel logsChannel;
    private final MetadataUrlFetcher metadataUrlFetcher;
    private final ReleaseSearchFlowService releaseSearchFlowService;
    private final ProcessFolderFlowService processFolderFlowService;
    private final ReprocessReleasesFlowService reprocessReleasesFlowService;
    private final PhotoSearchFlowService photoSearchFlowService;

    public List<BotResponse> handleUserRequest(ConversationContext ctx, String rawInput) {
        try {
            if (rawInput.trim().equalsIgnoreCase("стоп")) {
                return clearAllCaches(ctx);
            }

            // Slash commands (/clearctx, /np, /library, …) must always reach the dispatcher
            // even when an OngoingFlow is pending — otherwise the user has no way out.
            if (!rawInput.startsWith("/")) {
                var res = processOngoingFlow(ctx, rawInput);
                if (!res.isEmpty()) return res;
            }

            List<BotResponse> res = processUserCommands(ctx, rawInput);
            if (!res.isEmpty()) return res;

            return runMainAgent(ctx, rawInput);
        } finally {
            logFlowCost();
        }
    }

    private void logFlowCost() {
        String flowId = MDC.get("flowId");
        var calls = AgentTraceListener.drainFlowCalls(flowId);
        if (calls.isEmpty()) return;
        double total = calls.stream().mapToDouble(AgentTraceListener.CallEntry::cost).sum();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[flow=%s] TOTAL $%.4f (%d calls)\n", flowId, total, calls.size()));
        for (int i = 0; i < calls.size(); i++) {
            var c = calls.get(i);
            String tools = c.toolNames().isEmpty() ? "text" : String.join(",", c.toolNames());
            sb.append(String.format("  %d. %s → %s  (in=%d out=%d cR=%d cW=%d) $%.4f\n",
                    i + 1, c.agent(), tools,
                    c.tokensIn(), c.tokensOut(), c.cacheRead(), c.cacheWrite(),
                    c.cost()));
        }
        String msg = sb.toString().stripTrailing();
        log.info(msg);
        logsChannel.send(msg);
    }

    public List<BotResponse> handleCallback(ConversationContext ctx, String data, Integer messageId) {
        return callbackDispatcher.dispatch(ctx, data, messageId);
    }

    public List<BotResponse> handleUserPhoto(ConversationContext ctx, List<PhotoSize> photoSizes, String caption) {
        try {
            return photoSearchFlowService.handlePhoto(ctx, photoSizes, caption);
        } finally {
            logFlowCost();
        }
    }

    private List<BotResponse> runMainAgent(ConversationContext ctx, String rawInput) {
        try {
            return mainAgentService.chat(ctx, rawInput);
        } catch (Exception ex) {
            log.error("Main agent failure for conversation {}: {}", ctx.conversationId(), ex.getMessage(), ex);
            return List.of(BotResponse.text("шось не то, попробуй ще раз"));
        }
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
        if (metadataUrlFetcher.isUrl(rawInput.trim())) {
            return releaseSearchFlowService.showByUrl(ctx, rawInput.trim());
        }

        // Strip Telegram's @botname suffix from slash commands (e.g. /newtopic@sashkomusic_test_bot)
        if (rawInput.startsWith("/")) {
            rawInput = rawInput.replaceFirst("^(/\\S+?)@\\S+", "$1");
        }
        if (rawInput.startsWith("/library ")) {
            return handleLibrarySlash(ctx, rawInput.substring("/library ".length()).trim());
        }
        if (rawInput.startsWith("/discovery ")) {
            // Discovery is MainAgent's own toolset now, so the slash is just free text without the prefix.
            String query = rawInput.substring("/discovery ".length()).trim();
            return query.isEmpty() ? List.of(BotResponse.text("вкажи запит: /discovery <запит>")) : runMainAgent(ctx, query);
        }
        if (rawInput.startsWith("/clearctx")) {
            return clearContext(ctx);
        }
        if (rawInput.startsWith("/newtopic")) {
            return newTopicFlowService.handle(ctx, rawInput.substring("/newtopic".length()).trim());
        }
        if (rawInput.startsWith("/np-album") || rawInput.startsWith("/npalbum")) {
            return withNowPlayingContext(ctx, "/npalbum", nowPlayingAlbumFlowService.nowPlayingAlbum(ctx));
        }
        if (rawInput.startsWith("/markers")) {
            return markersFlowService.showMarkers(ctx);
        }
        if (rawInput.startsWith("/reprocess")) {
            var result = reprocessReleasesFlowService.handle(ctx, rawInput);
            return List.of(BotResponse.text(result.message()));
        }
        if (rawInput.startsWith("/process")) {
            return processFolderFlowService.handleProcessCommand(ctx, rawInput);
        }
        if (rawInput.startsWith("/smartlists")) {
            return smartlistsFlowService.showList(ctx);
        }
        if (rawInput.startsWith("/np") || rawInput.trim().equalsIgnoreCase("шо грає 🎵")) {
            return withNowPlayingContext(ctx, "/np", nowPlayingFlowService.nowPlaying(ctx));
        }
        if (rawInput.toLowerCase().startsWith("копай ")) {
            return directSoulseekSearchFlowService.search(ctx, rawInput.substring("копай ".length()));
        }
        return Collections.emptyList();
    }

    /**
     * The player is the one piece of state MainAgent's memory window can never contain. Writing what
     * `/np` just showed into that memory is what makes the next free-text message ("схоже до того що
     * зараз грає?") answerable — same mechanism `/library` and `/discovery` already use.
     */
    private List<BotResponse> withNowPlayingContext(ConversationContext ctx, String command,
                                                    NowPlayingFlowService.NowPlayingResult result) {
        if (result.agentContext() != null && !result.agentContext().isBlank()) {
            mainMemoryProvider.appendUserAndAiIfNew(ctx.conversationId(), command, result.agentContext());
        }
        return result.responses();
    }

    private List<BotResponse> handleLibrarySlash(ConversationContext ctx, String query) {
        if (query.isEmpty()) return List.of(BotResponse.text("вкажи команду: /library <запит>"));
        AgentTurn turn = AgentTurn.withoutProgress(ctx);
        String summary = libraryAgentService.handle(turn, query);
        List<BotResponse> shown = turn.shown();
        List<BotResponse> all = new ArrayList<>(shown);
        boolean hasCard = shown.stream().anyMatch(r -> r.buttons() != null || r.buttonRows() != null);
        if (!hasCard) all.add(BotResponse.text(summary));
        mainMemoryProvider.appendUserAndAi(ctx.conversationId(), "/library " + query, summary);
        return all;
    }

    private List<BotResponse> clearContext(ConversationContext ctx) {
        log.info("Clearing context for conversation {}", ctx.conversationId());
        eventPublisher.publishEvent(new ChatContextClearedEvent(ctx.conversationId()));
        return List.of(BotResponse.text("🧹 контекст чату очищено"));
    }

    private List<BotResponse> clearAllCaches(ConversationContext ctx) {
        log.info("Clearing all caches for conversation {}", ctx.conversationId());
        eventPublisher.publishEvent(new ChatContextClearedEvent(ctx.conversationId()));
        eventPublisher.publishEvent(new ChatHardResetEvent(ctx.conversationId()));
        return List.of(BotResponse.text("🧹 усі кеші очищено"));
    }
}

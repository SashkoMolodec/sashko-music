package com.sashkomusic.agents.main;

import com.sashkomusic.agents.config.MainChatMemoryProvider;
import com.sashkomusic.agents.turn.AgentTurn;
import com.sashkomusic.agents.turn.ProgressNotifier;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchStack;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one free-text turn: compacts the memory, opens the {@link AgentTurn}, lets MainAgent and its
 * tools work, and lays the answer out — the agent's words first, as an intro, then what the tools
 * showed, then one message per card stack the turn opened. Model failures propagate; the caller
 * decides what the user sees.
 */
@Service
@RequiredArgsConstructor
public class MainAgentService {

    private final MainAgent mainAgent;
    private final MainChatMemoryProvider mainMemoryProvider;
    private final ProgressNotifier progressNotifier;
    private final ReleaseSearchFlowService releaseSearchFlowService;
    private final SearchContextService searchContextService;

    public List<BotResponse> chat(ConversationContext ctx, String message) {
        // LangChain4j asks the provider for a conversation's memory once per JVM, so summarising there
        // would almost never run — the turn boundary is where the window gets compacted.
        mainMemoryProvider.compact(ctx.conversationId());

        AgentTurn turn = new AgentTurn(ctx, text -> progressNotifier.notify(ctx, text));
        String reply;
        try {
            reply = mainAgent.chat(ctx.conversationId(), message, turn.parameters());
        } catch (RuntimeException e) {
            // Nobody will see this turn's cards; the stacks of the cards already in the chat stay live.
            List<String> opened = stackIds(turn);
            if (!opened.isEmpty()) searchContextService.discardStacks(ctx.conversationId(), opened);
            throw e;
        }

        List<String> opened = stackIds(turn);
        if (!opened.isEmpty()) {
            searchContextService.retainStacks(ctx.conversationId(), opened);
        }

        List<BotResponse> out = new ArrayList<>();
        if (reply != null && !reply.isBlank()) {
            out.add(BotResponse.aiText(reply));
        }
        out.addAll(turn.shown());
        for (SearchStack stack : turn.openedStacks()) {
            out.addAll(releaseSearchFlowService.buildStackResponse(ctx, stack.id(), 0));
        }
        return out;
    }

    private static List<String> stackIds(AgentTurn turn) {
        return turn.openedStacks().stream().map(SearchStack::id).toList();
    }
}

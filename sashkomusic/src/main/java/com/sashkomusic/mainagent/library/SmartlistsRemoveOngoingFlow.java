package com.sashkomusic.mainagent.library;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SmartlistsRemoveOngoingFlow implements OngoingFlow {

    private final SmartlistsFlowService smartlistsFlowService;

    @Override
    public boolean appliesTo(ConversationContext ctx) {
        return smartlistsFlowService.isWaitingForRemove(ctx);
    }

    @Override
    public List<BotResponse> handle(ConversationContext ctx, String input) {
        return smartlistsFlowService.handleRemoveInput(ctx, input);
    }
}

package com.sashkomusic.mainagent.library;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SmartlistCreationOngoingFlow implements OngoingFlow {

    private final SmartlistCreationFlowService smartlistCreationFlowService;

    @Override
    public boolean appliesTo(ConversationContext ctx) {
        return smartlistCreationFlowService.hasDraft(ctx);
    }

    @Override
    public List<BotResponse> handle(ConversationContext ctx, String input) {
        return smartlistCreationFlowService.refine(ctx, input);
    }
}

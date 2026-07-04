package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SoulseekCustomSearchOngoingFlow implements OngoingFlow {

    private final SoulseekCustomSearchFlowService flowService;

    @Override
    public boolean appliesTo(ConversationContext ctx) {
        return flowService.isWaitingForQuery(ctx);
    }

    @Override
    public List<BotResponse> handle(ConversationContext ctx, String input) {
        return flowService.handleQueryInput(ctx, input);
    }
}

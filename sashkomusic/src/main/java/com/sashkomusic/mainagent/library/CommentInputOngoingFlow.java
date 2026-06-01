package com.sashkomusic.mainagent.library;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CommentInputOngoingFlow implements OngoingFlow {

    private final DjTagFlowService djTagFlowService;

    @Override
    public boolean appliesTo(ConversationContext ctx) {
        return djTagFlowService.isWaitingForComment(ctx);
    }

    @Override
    public List<BotResponse> handle(ConversationContext ctx, String input) {
        return djTagFlowService.handleCommentInput(ctx, input);
    }
}

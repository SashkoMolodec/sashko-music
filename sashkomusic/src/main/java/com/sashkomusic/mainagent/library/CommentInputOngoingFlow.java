package com.sashkomusic.mainagent.library;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CommentInputOngoingFlow implements OngoingFlow {

    private final DjTagFlowService djTagFlowService;

    @Override
    public boolean appliesTo(long chatId) {
        return djTagFlowService.isWaitingForComment(chatId);
    }

    @Override
    public List<BotResponse> handle(long chatId, String input) {
        return djTagFlowService.handleCommentInput(chatId, input);
    }
}

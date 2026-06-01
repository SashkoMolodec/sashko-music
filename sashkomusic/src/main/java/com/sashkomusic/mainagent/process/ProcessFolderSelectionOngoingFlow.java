package com.sashkomusic.mainagent.process;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ProcessFolderSelectionOngoingFlow implements OngoingFlow {

    private final ProcessFolderFlowService processFolderFlowService;

    @Override
    public boolean appliesTo(ConversationContext ctx) {
        return processFolderFlowService.hasActiveContext(ctx);
    }

    @Override
    public List<BotResponse> handle(ConversationContext ctx, String input) {
        return processFolderFlowService.handleMetadataSelection(ctx, input);
    }
}

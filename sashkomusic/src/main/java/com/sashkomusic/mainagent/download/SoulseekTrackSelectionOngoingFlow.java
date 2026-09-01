package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SoulseekTrackSelectionOngoingFlow implements OngoingFlow {

    private final SoulseekDirectoryPreviewFlowService service;

    @Override
    public boolean appliesTo(ConversationContext ctx) {
        return service.isSelecting(ctx);
    }

    @Override
    public List<BotResponse> handle(ConversationContext ctx, String input) {
        return service.handleSelection(ctx, input);
    }
}

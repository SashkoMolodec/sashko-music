package com.sashkomusic.mainagent.process;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ProcessFolderSelectionOngoingFlow implements OngoingFlow {

    private final ProcessFolderFlowService processFolderFlowService;

    @Override
    public boolean appliesTo(long chatId) {
        return processFolderFlowService.hasActiveContext(chatId);
    }

    @Override
    public List<BotResponse> handle(long chatId, String input) {
        return processFolderFlowService.handleMetadataSelection(chatId, input);
    }
}

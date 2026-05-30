package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DownloadOptionSelectionOngoingFlow implements OngoingFlow {

    private final DownloadContextHolder downloadContextHolder;
    private final MusicDownloadFlowService musicDownloadFlowService;

    @Override
    public boolean appliesTo(long chatId) {
        return !downloadContextHolder.getDownloadOptions(chatId).isEmpty();
    }

    @Override
    public List<BotResponse> handle(long chatId, String input) {
        return musicDownloadFlowService.handleDownloadOption(chatId, input);
    }
}

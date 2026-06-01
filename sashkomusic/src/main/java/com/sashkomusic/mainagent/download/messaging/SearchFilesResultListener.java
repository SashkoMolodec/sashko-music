package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.FileSearchResultEvent;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
import com.sashkomusic.mainagent.download.MusicDownloadFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SearchFilesResultListener {

    private final MusicDownloadFlowService musicDownloadFlowService;
    private final TelegramChatBot telegramBot;

    @EventListener
    @Async
    public void handleSearchResults(FileSearchResultEvent event) {
        var dto = event.payload();
        var response = musicDownloadFlowService.handleSearchResults(dto);
        response.forEach(res -> telegramBot.sendResponse(ConversationContext.from(dto.conversationId()), res));
    }
}

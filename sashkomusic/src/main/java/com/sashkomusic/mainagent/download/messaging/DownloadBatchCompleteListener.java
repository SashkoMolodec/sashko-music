package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.DownloadBatchCompleteEvent;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadBatchCompleteDto;
import com.sashkomusic.mainagent.process.ProcessFolderFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadBatchCompleteListener {

    private final ProcessFolderFlowService processFolderFlowService;
    private final TelegramChatBot telegramBot;

    @EventListener
    @Async
    public void handleBatchComplete(DownloadBatchCompleteEvent event) {
        DownloadBatchCompleteDto batchComplete = event.payload();
        log.info("Received download batch complete for chatId={}, releaseId={}, files={}",
                batchComplete.chatId(), batchComplete.releaseId(), batchComplete.totalFiles());

        processFolderFlowService.process(batchComplete.chatId(), batchComplete.directoryPath())
                .forEach(msg -> telegramBot.sendResponse(batchComplete.chatId(), msg));
    }
}

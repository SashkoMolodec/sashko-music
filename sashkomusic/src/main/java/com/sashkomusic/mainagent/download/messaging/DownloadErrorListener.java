package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.DownloadErrorEvent;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadErrorDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DownloadErrorListener {

    private final TelegramChatBot chatBot;

    @EventListener
    @Async
    public void handleDownloadError(DownloadErrorEvent event) {
        var error = event.payload();
        log.error("Received download error for chatId={}: {}", error.chatId(), error.errorMessage());
        String message = "🤡 **не получилосі скачати:**\n" + error.errorMessage();
        chatBot.sendMessage(error.chatId(), message);
    }
}

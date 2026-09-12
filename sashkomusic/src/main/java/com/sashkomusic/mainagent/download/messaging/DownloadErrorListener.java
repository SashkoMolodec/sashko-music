package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.DownloadErrorEvent;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
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
    @Async("asyncExecutor")
    public void handleDownloadError(DownloadErrorEvent event) {
        log.error("Received download error for conversationId={}: {}", event.conversationId(), event.errorMessage());
        String message = "🤡 **не получилосі скачати:**\n" + event.errorMessage();
        chatBot.sendMessage(ConversationContext.from(event.conversationId()), message);
    }
}

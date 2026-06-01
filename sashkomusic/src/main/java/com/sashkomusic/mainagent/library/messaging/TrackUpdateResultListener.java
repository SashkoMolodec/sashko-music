package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.TrackUpdateResultEvent;
import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;
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
public class TrackUpdateResultListener {

    private final TelegramChatBot chatBot;

    @EventListener
    @Async
    public void handleTrackUpdateResult(TrackUpdateResultEvent event) {
        TrackUpdateResultDto result = event.payload();
        log.info("Received track update result: trackId={}, field={}, value={}, success={}",
                result.trackId(), result.fieldUpdated(), result.value(), result.success());

        String message = result.success() ? "✅ оновлено" : "❌ помилка: " + result.message();
        chatBot.sendMessage(ConversationContext.from(result.conversationId()), message);
    }
}

package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.TrackUpdateResultEvent;
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
    @Async("asyncExecutor")
    public void handleTrackUpdateResult(TrackUpdateResultEvent event) {
        log.info("Received track update result: trackId={}, field={}, value={}, success={}",
                event.trackId(), event.fieldUpdated(), event.value(), event.success());

        String message = event.success() ? "✅ оновлено" : "❌ помилка: " + event.message();
        chatBot.sendMessage(ConversationContext.from(event.conversationId()), message);
    }
}

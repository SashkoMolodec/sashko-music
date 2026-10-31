package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.AppleMusicSyncFinishedEvent;
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
public class AppleMusicSyncFinishedListener {

    private final TelegramChatBot chatBot;

    @EventListener
    @Async("asyncExecutor")
    public void handle(AppleMusicSyncFinishedEvent event) {
        chatBot.sendMessage(ConversationContext.from(event.conversationId()), format(event));
    }

    /** "Added" means accepted by iTunes; iCloud upload is Apple's own schedule and not observable from here. */
    static String format(AppleMusicSyncFinishedEvent event) {
        if (event.error() != null) {
            String partial = event.staged() > 0 ? " (додано %d/%d)".formatted(event.added(), event.staged()) : "";
            return "❌ apple music: %s%s\n%s".formatted(event.label(), partial, event.error());
        }
        if (event.success()) {
            return "🍎 %s — додано в iTunes: %d/%d треків, далі їх у iCloud вантажить Sync Library"
                    .formatted(event.label(), event.added(), event.staged());
        }
        return "⚠️ apple music: %s — iTunes прийняв лише %d/%d треків"
                .formatted(event.label(), event.added(), event.staged());
    }
}

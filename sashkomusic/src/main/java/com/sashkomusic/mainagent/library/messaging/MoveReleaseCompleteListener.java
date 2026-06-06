package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.MoveReleaseCompleteEvent;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
import com.sashkomusic.mainagent.library.LastReleaseContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MoveReleaseCompleteListener {

    private final TelegramChatBot chatBot;
    private final LastReleaseContextHolder lastReleaseContextHolder;

    @EventListener
    @Async
    public void handle(MoveReleaseCompleteEvent event) {
        log.info("Received move release result: conversationId={}, success={}, releaseId={}, target={}",
                event.conversationId(), event.success(), event.releaseId(), event.targetSublibrary());

        String title = event.releaseTitle() != null ? event.releaseTitle() : "(id=" + event.releaseId() + ")";
        String message;
        if (event.success()) {
            message = "✅ перенесено у " + event.targetSublibrary() + ": " + title;
            if (event.newPath() != null) {
                message += "\n📁 " + event.newPath();
            }
            lastReleaseContextHolder.set(event.conversationId(), event.releaseId(),
                    event.releaseTitle(), event.releaseArtist());
        } else {
            message = "❌ не вдалося перенести: " + title + "\n" + event.message();
        }

        chatBot.sendMessage(ConversationContext.from(event.conversationId()), message);
    }
}

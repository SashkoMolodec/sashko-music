package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.RemoveReleaseCompleteEvent;
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
public class RemoveReleaseCompleteListener {

    private final TelegramChatBot chatBot;

    @EventListener
    @Async
    public void handle(RemoveReleaseCompleteEvent event) {
        log.info("Received remove release result: conversationId={}, success={}, releaseId={}",
                event.conversationId(), event.success(), event.releaseId());

        String title = event.releaseTitle() != null ? event.releaseTitle() : "(id=" + event.releaseId() + ")";
        String message;
        if (event.success()) {
            message = "🗑️ перенесено у trash: " + title;
            if (event.trashPath() != null) {
                message += "\n📁 `" + event.trashPath() + "`";
            }
            if (event.message() != null
                    && !"moved to trash".equals(event.message())
                    && !"DB cleared (no files on disk)".equals(event.message())) {
                message += "\nℹ️ " + event.message();
            }
        } else {
            message = "❌ не вдалося видалити: " + title + "\n" + event.message();
        }

        chatBot.sendMessage(ConversationContext.from(event.conversationId()), message);
    }
}

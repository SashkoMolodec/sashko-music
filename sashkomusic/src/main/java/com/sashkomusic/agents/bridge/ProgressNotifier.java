package com.sashkomusic.agents.bridge;

import com.sashkomusic.mainagent.bot.ConversationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProgressNotifier {

    private final TelegramClient telegramClient;

    public void notify(ConversationContext ctx, String text) {
        try {
            SendMessage.SendMessageBuilder<?, ?> builder = SendMessage.builder()
                    .chatId(ctx.chatId())
                    .text(text);
            if (ctx.isGroupTopic()) {
                builder.messageThreadId(ctx.topicId());
            }
            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            log.warn("Failed to send progress notification to [{}]: {}", ctx.conversationId(), e.getMessage());
        }
    }
}

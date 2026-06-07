package com.sashkomusic.mainagent.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Routes messages to the dedicated Telegram "logs" topic.
 * If {@code telegram.logs-topic-id} is not configured the send is a silent no-op.
 */
@Slf4j
@Component
public class TelegramLogsChannel {

    private final TelegramChatBot chatBot;
    private final Long defaultChatId;
    private final Integer logsTopicId;

    public TelegramLogsChannel(@Lazy TelegramChatBot chatBot,
                                @Value("${telegram.default-chat-id}") Long defaultChatId,
                                @Value("${telegram.logs-topic-id:#{null}}") Integer logsTopicId) {
        this.chatBot = chatBot;
        this.defaultChatId = defaultChatId;
        this.logsTopicId = logsTopicId;
    }

    /** Returns {@code true} if the logs topic is configured and messages will actually be delivered. */
    public boolean isEnabled() {
        return logsTopicId != null;
    }

    /** Sends a plain-text message to the logs topic. No-op if not configured. */
    public void send(String message) {
        if (logsTopicId == null || message == null || message.isBlank()) return;
        ConversationContext ctx = ConversationContext.topic(defaultChatId, logsTopicId);
        chatBot.sendMessage(ctx, message);
    }
}

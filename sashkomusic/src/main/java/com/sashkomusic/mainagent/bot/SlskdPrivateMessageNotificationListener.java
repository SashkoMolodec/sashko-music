package com.sashkomusic.mainagent.bot;

import com.sashkomusic.events.SlskdPrivateMessageReceivedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SlskdPrivateMessageNotificationListener {

    private final TelegramChatBot chatBot;
    @Value("${telegram.default-chat-id}")
    private long defaultChatId;

    @EventListener
    @Async("asyncExecutor")
    public void onPrivateMessage(SlskdPrivateMessageReceivedEvent event) {
        log.info("Soulseek private message from {}: {}", event.username(), event.message());
        String text = "💬 Soulseek — повідомлення від *" + event.username() + "*:\n" + event.message();
        chatBot.sendMessage(ConversationContext.dm(defaultChatId), text);
    }
}

package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.DownloadCompleteEvent;
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
public class DownloadCompleteListener {

    private final TelegramChatBot chatBot;

    @EventListener
    @Async("asyncExecutor")
    public void handleDownloadComplete(DownloadCompleteEvent event) {
        log.info("Received download complete for conversationId={}: {} ({} MB)", event.conversationId(), event.filename(), event.sizeMB());

        String displayName = extractDisplayName(event.filename());
        String message = "✅ `%s` (%d MB)".formatted(displayName, event.sizeMB());
        chatBot.sendMessage(ConversationContext.from(event.conversationId()), message);
    }

    private String extractDisplayName(String filename) {
        if (filename == null) return "";
        int lastSlash = Math.max(filename.lastIndexOf('\\'), filename.lastIndexOf('/'));
        if (lastSlash >= 0 && lastSlash < filename.length() - 1) {
            return filename.substring(lastSlash + 1);
        }
        return filename;
    }
}

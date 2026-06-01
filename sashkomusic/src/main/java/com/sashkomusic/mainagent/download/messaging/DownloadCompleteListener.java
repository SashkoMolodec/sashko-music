package com.sashkomusic.mainagent.download.messaging;

import com.sashkomusic.events.DownloadCompleteEvent;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadCompleteDto;
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
    @Async
    public void handleDownloadComplete(DownloadCompleteEvent event) {
        DownloadCompleteDto complete = event.payload();
        log.info("Received download complete for conversationId={}: {} ({} MB)", complete.conversationId(), complete.filename(), complete.sizeMB());

        String displayName = extractDisplayName(complete.filename());
        String message = "✅ `%s` (%d MB)".formatted(displayName, complete.sizeMB());
        chatBot.sendMessage(ConversationContext.from(complete.conversationId()), message);
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

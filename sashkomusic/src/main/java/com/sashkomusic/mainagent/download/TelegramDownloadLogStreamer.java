package com.sashkomusic.mainagent.download;

import com.sashkomusic.events.DownloadBatchCompleteEvent;
import com.sashkomusic.events.DownloadCompleteEvent;
import com.sashkomusic.events.DownloadErrorEvent;
import com.sashkomusic.events.DownloadLogLineEvent;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Buffers per-conversation CLI output from download processes and flushes to a dedicated
 * Telegram "logs" topic every 10 seconds (or immediately on completion / error).
 *
 * Routing: messages go to defaultChatId at logsTopicId. If logs-topic-id is not configured,
 * the streamer is a no-op (events are dropped quietly).
 *
 * Each batch is capped to Telegram's 4096-char limit; longer batches are split.
 */
@Component
@Slf4j
public class TelegramDownloadLogStreamer {

    private static final int FLUSH_INTERVAL_MS = 10_000;
    private static final int MAX_BATCH_CHARS = 3_500;
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\[[;\\d]*[A-Za-z]");

    private final TelegramChatBot chatBot;
    private final Long defaultChatId;
    private final Integer logsTopicId;
    private final Map<String, StringBuilder> buffers = new ConcurrentHashMap<>();

    public TelegramDownloadLogStreamer(TelegramChatBot chatBot,
                                       @Value("${telegram.default-chat-id}") Long defaultChatId,
                                       @Value("${telegram.logs-topic-id:#{null}}") Integer logsTopicId) {
        this.chatBot = chatBot;
        this.defaultChatId = defaultChatId;
        this.logsTopicId = logsTopicId;
        if (logsTopicId == null) {
            log.info("telegram.logs-topic-id not configured — download log streaming disabled");
        }
    }

    @EventListener
    public void onLogLine(DownloadLogLineEvent event) {
        if (logsTopicId == null) return;
        String clean = ANSI_ESCAPE.matcher(event.line()).replaceAll("").stripTrailing();
        if (clean.isBlank()) return;
        buffers.computeIfAbsent(event.conversationId(), k -> new StringBuilder())
                .append("[").append(event.tag()).append("] ").append(clean).append('\n');
    }

    @EventListener
    public void onBatchComplete(DownloadBatchCompleteEvent event) {
        flushOne(event.payload().conversationId(), "✅ batch complete");
    }

    @EventListener
    public void onDownloadComplete(DownloadCompleteEvent event) {
        flushOne(event.payload().conversationId(), "✅ download complete");
    }

    @EventListener
    public void onDownloadError(DownloadErrorEvent event) {
        flushOne(event.payload().conversationId(), "❌ " + event.payload().errorMessage());
    }

    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flushAll() {
        if (logsTopicId == null || buffers.isEmpty()) return;
        for (String conversationId : buffers.keySet()) {
            flushOne(conversationId, null);
        }
    }

    private void flushOne(String conversationId, String footer) {
        if (logsTopicId == null) return;
        StringBuilder buf = buffers.remove(conversationId);
        if (buf == null && footer == null) return;

        String body = buf == null ? "" : buf.toString();
        if (footer != null) body = body + footer + '\n';
        if (body.isBlank()) return;

        ConversationContext ctx = ConversationContext.topic(defaultChatId, logsTopicId);
        for (String chunk : splitChunks(body, MAX_BATCH_CHARS)) {
            chatBot.sendMessage(ctx, "🎯 " + conversationId + "\n" + chunk);
        }
    }

    private static java.util.List<String> splitChunks(String text, int limit) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int to = Math.min(from + limit, text.length());
            if (to < text.length()) {
                int nl = text.lastIndexOf('\n', to);
                if (nl > from) to = nl + 1;
            }
            out.add(text.substring(from, to));
            from = to;
        }
        return out;
    }
}

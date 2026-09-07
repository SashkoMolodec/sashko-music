package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.ConversationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * All actual downloading (file search, option selection, soulseek preview, progress, and the final
 * "added to library" message) is funneled into one fixed topic when configured -- keeps the noisy
 * part of the flow out of whatever topic the release was discovered in. Every entry point that kicks
 * off a file search (the normal release-card flow, "копай <query>", and the custom-soulseek-query
 * follow-up) must go through this, or its messages silently stay in the discovery topic instead.
 */
@Component
@Slf4j
public class DownloadTopicResolver {

    private final Long defaultChatId;
    private final Integer downloadTopicId;

    public DownloadTopicResolver(@Value("${telegram.default-chat-id}") Long defaultChatId,
                                  @Value("${telegram.download-topic-id:#{null}}") Integer downloadTopicId) {
        this.defaultChatId = defaultChatId;
        this.downloadTopicId = downloadTopicId;
        if (downloadTopicId == null) {
            log.info("telegram.download-topic-id not configured — download flow stays in the originating chat/topic");
        }
    }

    public ConversationContext resolve(ConversationContext ctx) {
        return downloadTopicId == null ? ctx : ConversationContext.topic(defaultChatId, downloadTopicId);
    }
}

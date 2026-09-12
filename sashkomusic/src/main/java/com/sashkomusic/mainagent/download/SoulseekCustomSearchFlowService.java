package com.sashkomusic.mainagent.download;

import com.sashkomusic.shared.download.DownloadEngine;
import org.springframework.context.ApplicationEventPublisher;
import com.sashkomusic.events.FilesSearchTaskEvent;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.shared.task.SearchFilesTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SoulseekCustomSearchFlowService {

    private static final String FLOW_KEY = "slsk_custom";

    private final ApplicationEventPublisher eventPublisher;
    private final ChatStateStore stateStore;
    private final DownloadTopicResolver downloadTopicResolver;

    public List<BotResponse> handleCallback(ConversationContext ctx, String callbackData) {
        String releaseId = callbackData.substring("SLSK_CUSTOM:".length());
        stateStore.put(ctx.conversationId(), FLOW_KEY, releaseId);
        return List.of(BotResponse.text("🗿 напиши свій запит для soulseek:"));
    }

    public boolean isWaitingForQuery(ConversationContext ctx) {
        return stateStore.get(ctx.conversationId(), FLOW_KEY, String.class).isPresent();
    }

    public List<BotResponse> handleQueryInput(ConversationContext ctx, String query) {
        String releaseId = stateStore.get(ctx.conversationId(), FLOW_KEY, String.class).orElse(null);
        stateStore.remove(ctx.conversationId(), FLOW_KEY);

        if (releaseId == null) {
            return List.of(BotResponse.text("щось пішло не так, спробуй ще раз"));
        }

        String downloadConversationId = downloadTopicResolver.resolve(ctx).conversationId();
        eventPublisher.publishEvent(new FilesSearchTaskEvent(new SearchFilesTask(downloadConversationId, releaseId, query, "", DownloadEngine.SOULSEEK)));
        return List.of(BotResponse.text("🔎 шукаю опції завантаження (soulseek): " + query));
    }
}

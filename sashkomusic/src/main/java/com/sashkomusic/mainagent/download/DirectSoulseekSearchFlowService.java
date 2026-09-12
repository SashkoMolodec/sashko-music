package com.sashkomusic.mainagent.download;

import com.sashkomusic.shared.download.DownloadEngine;
import org.springframework.context.ApplicationEventPublisher;
import com.sashkomusic.events.FilesSearchTaskEvent;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.shared.task.SearchFilesTask;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DirectSoulseekSearchFlowService {

    private final ApplicationEventPublisher eventPublisher;
    private final SearchContextService searchContextService;
    private final DownloadTopicResolver downloadTopicResolver;

    public List<BotResponse> search(ConversationContext ctx, String query) {
        query = query.trim();
        if (query.isBlank()) {
            return List.of(BotResponse.text("вкажи запит: копай <артист - реліз>"));
        }

        String releaseId = UUID.randomUUID().toString();
        String artist = query;
        String title = "";
        int dash = query.indexOf(" - ");
        if (dash > 0) {
            artist = query.substring(0, dash).trim();
            title = query.substring(dash + 3).trim();
        }

        ReleaseMetadata synthetic = new ReleaseMetadata(
                releaseId, null, SearchEngine.DISCOGS,
                artist, title, 0,
                List.of(), List.of(), 0, 0, 1,
                List.of(), null, List.of(), ""
        );

        MetadataSearchRequest request = MetadataSearchRequest.create(
                artist, title, "", null, "", "", "", "", "", "", "", null
        );
        ConversationContext downloadCtx = downloadTopicResolver.resolve(ctx);
        searchContextService.saveSearchContext(downloadCtx.conversationId(), SearchEngine.DISCOGS, query, request, List.of(synthetic));

        eventPublisher.publishEvent(new FilesSearchTaskEvent(new SearchFilesTask(downloadCtx.conversationId(), releaseId, query, "", DownloadEngine.SOULSEEK)));
        log.info("Direct Soulseek search: query='{}', releaseId={}", query, releaseId);
        return List.of(BotResponse.text("🔎 шукаю на soulseek: " + query));
    }
}

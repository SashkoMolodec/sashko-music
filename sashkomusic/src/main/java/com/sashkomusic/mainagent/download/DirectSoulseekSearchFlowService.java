package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.download.messaging.SearchFilesTaskProducer;
import com.sashkomusic.mainagent.download.messaging.dto.SearchFilesTaskDto;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DirectSoulseekSearchFlowService {

    private final SearchFilesTaskProducer searchFilesProducer;
    private final SearchContextService searchContextService;

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
        searchContextService.saveSearchContext(ctx.conversationId(), SearchEngine.DISCOGS, query, request, List.of(synthetic));

        searchFilesProducer.send(new SearchFilesTaskDto(ctx.conversationId(), releaseId, query, "", DownloadEngine.SOULSEEK));
        log.info("Direct Soulseek search: query='{}', releaseId={}", query, releaseId);
        return List.of(BotResponse.text("🔎 шукаю на soulseek: " + query));
    }
}

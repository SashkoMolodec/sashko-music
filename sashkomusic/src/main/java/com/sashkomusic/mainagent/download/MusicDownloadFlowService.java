package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.downloadagent.messaging.producer.dto.SearchFilesResultDto;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadCancelTaskDto;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadFilesTaskDto;
import com.sashkomusic.mainagent.download.messaging.dto.SearchFilesTaskDto;
import com.sashkomusic.mainagent.download.messaging.DownloadCancelTaskProducer;
import com.sashkomusic.mainagent.download.messaging.DownloadTaskProducer;
import com.sashkomusic.mainagent.download.messaging.SearchFilesTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class MusicDownloadFlowService {

    private final SearchFilesTaskProducer searchFilesProducer;
    private final DownloadTaskProducer downloadTaskProducer;
    private final DownloadCancelTaskProducer downloadCancelTaskProducer;
    private final SearchContextService contextService;
    private final DownloadContextHolder downloadContextHolder;
    private final ReleaseSearchFlowService releaseSearchFlowService;
    private final Map<DownloadEngine, DownloadFlowHandler> downloadFlowHandlers;

    public List<BotResponse> handleDownload(ConversationContext ctx, String data) {
        if (data.startsWith("DL:")) {
            String releaseId = data.substring(3);
            log.info("User selected release ID: {}", releaseId);

            ReleaseMetadata metadata = contextService.getReleaseMetadata(releaseId, ctx.conversationId());
            if (metadata == null) {
                return List.of(BotResponse.text("❌ шось ся не получило...найди реліз ше раз"));
            }
            return initiateDefaultDownloadSearch(ctx, metadata);
        }

        return List.of(BotResponse.text("тєжко."));
    }

    private List<BotResponse> initiateDefaultDownloadSearch(ConversationContext ctx, ReleaseMetadata metadata) {
        return initiateDownloadSearch(ctx, metadata, DownloadEngine.QOBUZ);
    }

    private List<BotResponse> initiateDownloadSearch(ConversationContext ctx, ReleaseMetadata metadata, DownloadEngine source) {
        log.info("Initiating download search for: {} - {}", metadata.artist(), metadata.title());

        searchFilesProducer.send(SearchFilesTaskDto.of(ctx.conversationId(), metadata.id(), metadata.artist(), metadata.title(), source));

        return List.of(BotResponse.text(
                "🔎 шукаю опції завантаження (%s): _%s - %s_".formatted(
                        source.getName(),
                        metadata.artist(),
                        metadata.title())
        ));
    }

    public List<BotResponse> handleSearchResults(SearchFilesResultDto dto) {
        log.info("Processing search results for conversationId={}, releaseId={}, source={}, results count={}",
                dto.conversationId(), dto.releaseId(), dto.source(), dto.results().size());

        var flowHandler = downloadFlowHandlers.get(dto.source());
        var analysisResult = flowHandler.analyzeAll(dto.results(), dto.releaseId(), dto.conversationId());
        var reports = analysisResult.reports();
        downloadContextHolder.saveDownloadOptions(dto.conversationId(), dto.releaseId(), reports);

        reports.forEach(r -> log.info("{}", r));

        String text = DownloadOptionsCardFormatter.format(reports, analysisResult.aiSummary());
        BotResponse sourceCard = flowHandler.buildSearchResultsResponse(text, dto.releaseId(), dto.source());
        return List.of(mergeWithSelectionButtons(sourceCard, reports));
    }

    public List<BotResponse> handleDownloadOptionCallback(ConversationContext ctx, String data) {
        String payload = data.substring("DLOPT:".length());
        if ("cancel".equals(payload)) {
            downloadContextHolder.clearSession(ctx.conversationId());
            return List.of(BotResponse.text("❌ скасовано"));
        }

        var reports = downloadContextHolder.getDownloadOptions(ctx.conversationId());
        if (reports.isEmpty()) {
            return List.of(BotResponse.text("😔 варіанти пропали — знайди реліз ще раз"));
        }

        int index;
        try {
            index = Integer.parseInt(payload);
        } catch (NumberFormatException e) {
            return List.of(BotResponse.text("❌ невідома команда"));
        }

        if (index < 0 || index >= reports.size()) {
            return List.of(BotResponse.text("❌ невірний варіант"));
        }

        var option = reports.get(index).option();
        String releaseId = downloadContextHolder.getChosenRelease(ctx.conversationId());
        log.info("User chose option #{}: {} from {}", index, option.id(), option.displayName());

        downloadTaskProducer.send(DownloadFilesTaskDto.of(ctx.conversationId(), releaseId, option));
        downloadContextHolder.clearSession(ctx.conversationId());

        var flowHandler = downloadFlowHandlers.get(option.source());
        return List.of(BotResponse.text(flowHandler.formatDownloadConfirmation(option)));
    }

    private BotResponse mergeWithSelectionButtons(BotResponse sourceCard, List<DownloadFlowHandler.OptionReport> reports) {
        List<List<BotResponse.ButtonDto>> allRows = new ArrayList<>();

        // Convert flat source-switch buttons to a single row
        if (sourceCard.buttons() != null && !sourceCard.buttons().isEmpty()) {
            List<BotResponse.ButtonDto> sourceRow = sourceCard.buttons().entrySet().stream()
                    .map(e -> new BotResponse.ButtonDto(e.getKey(), e.getValue()))
                    .toList();
            allRows.add(sourceRow);
        }
        if (sourceCard.buttonRows() != null) {
            allRows.addAll(sourceCard.buttonRows());
        }

        // Add numbered selection buttons (up to 5 per row)
        List<BotResponse.ButtonDto> row = new ArrayList<>();
        for (int i = 0; i < reports.size(); i++) {
            row.add(new BotResponse.ButtonDto(indexIcon(i + 1), "DLOPT:" + i));
            if (row.size() == 5) {
                allRows.add(List.copyOf(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) allRows.add(List.copyOf(row));
        allRows.add(List.of(new BotResponse.ButtonDto("❌ скасувати", "DLOPT:cancel")));

        return new BotResponse(sourceCard.text(), sourceCard.imageUrl(), null, allRows, null, false);
    }

    private static String indexIcon(int i) {
        return switch (i) {
            case 1 -> "1️⃣"; case 2 -> "2️⃣"; case 3 -> "3️⃣";
            case 4 -> "4️⃣"; case 5 -> "5️⃣"; case 6 -> "6️⃣";
            case 7 -> "7️⃣"; case 8 -> "8️⃣"; case 9 -> "9️⃣";
            case 10 -> "🔟"; default -> i + ".";
        };
    }

    public List<BotResponse> getDownloadOptions(ConversationContext ctx, String query) {
        log.info("Direct download request for conversationId={}, query: {}", ctx.conversationId(), query);

        var searchResult = releaseSearchFlowService.searchWithFallback(query, SearchEngine.MUSICBRAINZ, SearchEngine.DISCOGS);

        if (searchResult.releases().isEmpty()) {
            return List.of(BotResponse.text("😔 **нич взагалі не знайшов у світі авдіо, спробуй по-іншому.**"));
        }

        ReleaseMetadata selectedRelease = searchResult.releases().getFirst();
        log.info("Auto-selected release: {} - {} from {}",
                selectedRelease.artist(), selectedRelease.title(), searchResult.engine());

        contextService.saveSearchContext(ctx.conversationId(), searchResult.engine(), query,
                searchResult.searchRequest(), searchResult.releases());
        contextService.saveReleaseMetadata(selectedRelease);

        List<BotResponse> responses = new ArrayList<>();
        responses.add(releaseSearchFlowService.buildReleaseDownloadCard(selectedRelease, searchResult.engine()));
        responses.addAll(initiateDefaultDownloadSearch(ctx, selectedRelease));

        return responses;
    }

    public List<BotResponse> handleDownloadCancel(ConversationContext ctx, String data) {
        String releaseId = data.substring("CANCEL_DL:".length());
        log.info("User requested cancel for releaseId={}", releaseId);

        downloadCancelTaskProducer.send(DownloadCancelTaskDto.of(ctx.conversationId(), releaseId));

        return List.of(BotResponse.text("❌ скасовано"));
    }


    public List<BotResponse> handleSearchAlternative(ConversationContext ctx, String data) {
        int lastColonIndex = data.lastIndexOf(':');
        if (lastColonIndex == -1 || lastColonIndex <= "SEARCH_ALT:".length()) {
            return List.of(BotResponse.text("❌ шось не то з командою"));
        }

        String releaseId = data.substring("SEARCH_ALT:".length(), lastColonIndex);
        String sourceName = data.substring(lastColonIndex + 1);

        log.info("Alternative search requested: releaseId={}, source={}", releaseId, sourceName);

        ReleaseMetadata metadata = contextService.getReleaseMetadata(releaseId, ctx.conversationId());
        if (metadata == null) {
            return List.of(BotResponse.text("❌ шось ся не получило...найди реліз ше раз"));
        }

        var source = DownloadEngine.valueOf(sourceName);
        return initiateDownloadSearch(ctx, metadata, source);
    }
}

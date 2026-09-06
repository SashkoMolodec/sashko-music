package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.downloadagent.messaging.producer.dto.SearchFilesResultDto;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadFilesTaskDto;
import com.sashkomusic.mainagent.download.messaging.dto.SearchFilesTaskDto;
import com.sashkomusic.mainagent.download.messaging.DownloadTaskProducer;
import com.sashkomusic.mainagent.download.messaging.SearchFilesTaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class MusicDownloadFlowService {

    private final SearchFilesTaskProducer searchFilesProducer;
    private final DownloadTaskProducer downloadTaskProducer;
    private final SearchContextService contextService;
    private final DownloadContextHolder downloadContextHolder;
    private final ReleaseSearchFlowService releaseSearchFlowService;
    private final Map<DownloadEngine, DownloadFlowHandler> downloadFlowHandlers;
    private final SoulseekDirectoryPreviewFlowService soulseekDirectoryPreview;
    private final Long defaultChatId;
    private final Integer downloadTopicId;

    public MusicDownloadFlowService(SearchFilesTaskProducer searchFilesProducer,
                                    DownloadTaskProducer downloadTaskProducer,
                                    SearchContextService contextService,
                                    DownloadContextHolder downloadContextHolder,
                                    ReleaseSearchFlowService releaseSearchFlowService,
                                    Map<DownloadEngine, DownloadFlowHandler> downloadFlowHandlers,
                                    SoulseekDirectoryPreviewFlowService soulseekDirectoryPreview,
                                    @Value("${telegram.default-chat-id}") Long defaultChatId,
                                    @Value("${telegram.download-topic-id:#{null}}") Integer downloadTopicId) {
        this.searchFilesProducer = searchFilesProducer;
        this.downloadTaskProducer = downloadTaskProducer;
        this.contextService = contextService;
        this.downloadContextHolder = downloadContextHolder;
        this.releaseSearchFlowService = releaseSearchFlowService;
        this.downloadFlowHandlers = downloadFlowHandlers;
        this.soulseekDirectoryPreview = soulseekDirectoryPreview;
        this.defaultChatId = defaultChatId;
        this.downloadTopicId = downloadTopicId;
        if (downloadTopicId == null) {
            log.info("telegram.download-topic-id not configured — download flow stays in the originating chat/topic");
        }
    }

    /**
     * All actual downloading (file search, option selection, soulseek preview, progress, and the final
     * "added to library" message) is funneled into one fixed topic when configured — keeps the noisy
     * part of the flow out of whatever topic the release was discovered in. Release discovery/search
     * itself is untouched and keeps using the original {@code ctx}. The very first ack ("🔎 шукаю
     * опції...") still goes back through the caller's own ctx (the click that triggered it), since
     * sending it directly here would require injecting TelegramChatBot, which would create a circular
     * bean dependency (MusicDownloadFlowService -> TelegramChatBot -> UserInteractionOrchestrator ->
     * CallbackDispatcher -> MusicDownloadFlowService).
     */
    private ConversationContext resolveDownloadCtx(ConversationContext ctx) {
        return downloadTopicId == null ? ctx : ConversationContext.topic(defaultChatId, downloadTopicId);
    }

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

        ConversationContext downloadCtx = resolveDownloadCtx(ctx);
        if (!downloadCtx.equals(ctx)) {
            contextService.mirrorReleaseForDownload(downloadCtx.conversationId(), metadata);
        }
        searchFilesProducer.send(SearchFilesTaskDto.of(downloadCtx.conversationId(), metadata.id(), metadata.artist(), metadata.title(), source));

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

        // All reports sorted; take first page for display
        var allReports = analysisResult.reports();
        var firstPage = DownloadOptionsCardFormatter.trimToFit(
                allReports.stream().limit(DownloadContextHolder.PAGE_SIZE).toList(),
                analysisResult.aiSummary());

        downloadContextHolder.saveDownloadOptions(dto.conversationId(), dto.releaseId(), allReports, dto.source());
        firstPage.forEach(r -> log.info("{}", r));

        String text = DownloadOptionsCardFormatter.format(firstPage, analysisResult.aiSummary(), 0);
        BotResponse sourceCard = flowHandler.buildSearchResultsResponse(text, dto.releaseId(), dto.source());
        BotResponse merged = mergeWithSelectionButtons(sourceCard, firstPage, flowHandler.appendDefaultCancelRow(), 0);

        if (downloadContextHolder.hasNextPage(dto.conversationId())) {
            merged = appendNextPageButton(merged, dto.releaseId(), 0, allReports.size());
        }
        return List.of(merged);
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

        if (option.source() == DownloadEngine.SOULSEEK) {
            return soulseekDirectoryPreview.fetchAndShowPreview(ctx, releaseId, option);
        }

        downloadContextHolder.clearSession(ctx.conversationId());
        downloadTaskProducer.send(DownloadFilesTaskDto.of(ctx.conversationId(), releaseId, option));
        var flowHandler = downloadFlowHandlers.get(option.source());
        return List.of(BotResponse.text(flowHandler.formatDownloadConfirmation(option)));
    }

    /**
     * @param offset global index of the first report in {@code reports} (page * PAGE_SIZE). Selection
     *               buttons encode the global index so a button rendered on an earlier page still
     *               resolves correctly against {@code allReports} after paging further (see
     *               DownloadContextHolder — indices are never reused/reset per page).
     */
    private BotResponse mergeWithSelectionButtons(BotResponse sourceCard, List<DownloadFlowHandler.OptionReport> reports, boolean appendCancelRow, int offset) {
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
            int globalIndex = offset + i;
            row.add(new BotResponse.ButtonDto(indexIcon(globalIndex + 1), "DLOPT:" + globalIndex));
            if (row.size() == 5) {
                allRows.add(List.copyOf(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) allRows.add(List.copyOf(row));
        if (appendCancelRow) allRows.add(List.of(new BotResponse.ButtonDto("❌", "DLOPT:cancel")));

        return new BotResponse(sourceCard.text(), sourceCard.imageUrl(), null, allRows, null, false);
    }

    public List<BotResponse> handleNextPage(ConversationContext ctx, String data) {
        String releaseId = data.substring("DLNEXT:".length());
        var nextReports = downloadContextHolder.advancePage(ctx.conversationId());
        if (nextReports.isEmpty()) {
            return List.of(BotResponse.text("більше варіантів нема"));
        }

        int page = downloadContextHolder.getCurrentPage(ctx.conversationId());
        int total = downloadContextHolder.getTotalCount(ctx.conversationId());
        DownloadEngine source = downloadContextHolder.getSource(ctx.conversationId());
        var flowHandler = downloadFlowHandlers.get(source != null ? source : DownloadEngine.SOULSEEK);

        int offset = page * DownloadContextHolder.PAGE_SIZE;
        String text = DownloadOptionsCardFormatter.format(nextReports, "", offset);
        BotResponse sourceCard = flowHandler.buildSearchResultsResponse(text, releaseId, source);
        BotResponse merged = mergeWithSelectionButtons(sourceCard, nextReports, flowHandler.appendDefaultCancelRow(), offset);

        if (downloadContextHolder.hasNextPage(ctx.conversationId())) {
            merged = appendNextPageButton(merged, releaseId, page, total);
        }
        return List.of(merged);
    }

    private BotResponse appendNextPageButton(BotResponse response, String releaseId, int currentPage, int total) {
        List<List<BotResponse.ButtonDto>> rows = new ArrayList<>();
        if (response.buttonRows() != null) rows.addAll(response.buttonRows());
        int shown = (currentPage + 1) * DownloadContextHolder.PAGE_SIZE;
        rows.add(List.of(new BotResponse.ButtonDto(
                "➡️ ще %d".formatted(Math.min(DownloadContextHolder.PAGE_SIZE, total - shown)),
                "DLNEXT:" + releaseId)));
        return new BotResponse(response.text(), response.imageUrl(), null, rows, null, false);
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

        List<BotResponse> responses = new ArrayList<>();
        responses.add(releaseSearchFlowService.buildReleaseDownloadCard(selectedRelease, searchResult.engine()));
        responses.addAll(initiateDefaultDownloadSearch(ctx, selectedRelease));

        return responses;
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

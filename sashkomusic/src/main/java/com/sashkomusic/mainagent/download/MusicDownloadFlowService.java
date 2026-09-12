package com.sashkomusic.mainagent.download;

import org.springframework.context.ApplicationEventPublisher;
import com.sashkomusic.events.FileSearchResultEvent;
import com.sashkomusic.events.FilesDownloadTaskEvent;
import com.sashkomusic.events.FilesSearchTaskEvent;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.shared.download.DownloadEngine;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.shared.task.DownloadFilesTask;
import com.sashkomusic.shared.task.SearchFilesTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class MusicDownloadFlowService {

    private final ApplicationEventPublisher eventPublisher;
    private final SearchContextService contextService;
    private final DownloadContextHolder downloadContextHolder;
    private final ReleaseSearchFlowService releaseSearchFlowService;
    private final Map<DownloadEngine, DownloadFlowHandler> downloadFlowHandlers;
    private final SoulseekDirectoryPreviewFlowService soulseekDirectoryPreview;
    private final DownloadTopicResolver downloadTopicResolver;

    public MusicDownloadFlowService(ApplicationEventPublisher eventPublisher,
                                    SearchContextService contextService,
                                    DownloadContextHolder downloadContextHolder,
                                    ReleaseSearchFlowService releaseSearchFlowService,
                                    Map<DownloadEngine, DownloadFlowHandler> downloadFlowHandlers,
                                    SoulseekDirectoryPreviewFlowService soulseekDirectoryPreview,
                                    DownloadTopicResolver downloadTopicResolver) {
        this.eventPublisher = eventPublisher;
        this.contextService = contextService;
        this.downloadContextHolder = downloadContextHolder;
        this.releaseSearchFlowService = releaseSearchFlowService;
        this.downloadFlowHandlers = downloadFlowHandlers;
        this.soulseekDirectoryPreview = soulseekDirectoryPreview;
        this.downloadTopicResolver = downloadTopicResolver;
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

        // The very first ack ("🔎 шукаю опції...") below still goes back through the caller's own
        // ctx (the click that triggered it), since sending it directly here would require injecting
        // TelegramChatBot, which would create a circular bean dependency (MusicDownloadFlowService ->
        // TelegramChatBot -> UserInteractionOrchestrator -> CallbackDispatcher -> MusicDownloadFlowService).
        ConversationContext downloadCtx = downloadTopicResolver.resolve(ctx);
        if (!downloadCtx.equals(ctx)) {
            contextService.mirrorReleaseForDownload(downloadCtx.conversationId(), metadata);
        }
        eventPublisher.publishEvent(new FilesSearchTaskEvent(new SearchFilesTask(downloadCtx.conversationId(), metadata.id(), metadata.artist(), metadata.title(), source)));

        return List.of(BotResponse.text(
                "🔎 шукаю опції завантаження (%s): _%s - %s_".formatted(
                        source.getName(),
                        metadata.artist(),
                        metadata.title())
        ));
    }

    public List<BotResponse> handleSearchResults(FileSearchResultEvent event) {
        log.info("Processing search results for conversationId={}, releaseId={}, source={}, results count={}",
                event.conversationId(), event.releaseId(), event.source(), event.results().size());

        var flowHandler = downloadFlowHandlers.get(event.source());
        var analysisResult = flowHandler.analyzeAll(event.results(), event.releaseId(), event.conversationId());

        // All reports sorted; take first page for display
        var allReports = analysisResult.reports();
        var firstPage = DownloadOptionsCardFormatter.trimToFit(
                allReports.stream().limit(DownloadContextHolder.PAGE_SIZE).toList(),
                analysisResult.aiSummary());

        downloadContextHolder.saveDownloadOptions(event.conversationId(), event.releaseId(), allReports, event.source());
        firstPage.forEach(r -> log.info("{}", r));

        String text = DownloadOptionsCardFormatter.format(firstPage, analysisResult.aiSummary(), 0);
        BotResponse sourceCard = flowHandler.buildSearchResultsResponse(text, event.releaseId(), event.source());
        BotResponse merged = mergeWithSelectionButtons(sourceCard, firstPage, flowHandler.appendDefaultCancelRow(), 0);

        if (downloadContextHolder.hasNextPage(event.conversationId())) {
            merged = appendNextPageButton(merged, event.releaseId(), 0, allReports.size());
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
        eventPublisher.publishEvent(new FilesDownloadTaskEvent(new DownloadFilesTask(ctx.conversationId(), releaseId, option)));
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

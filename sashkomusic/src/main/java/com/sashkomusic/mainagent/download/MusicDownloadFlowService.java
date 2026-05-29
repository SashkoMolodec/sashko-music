package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
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

    public List<BotResponse> handleDownload(long chatId, String data) {
        if (data.startsWith("DL:")) {
            String releaseId = data.substring(3);
            log.info("User selected release ID: {}", releaseId);

            ReleaseMetadata metadata = contextService.getReleaseMetadata(releaseId);
            if (metadata == null) {
                return List.of(BotResponse.text("❌ шось ся не получило...найди реліз ше раз"));
            }
            return initiateDefaultDownloadSearch(chatId, metadata);
        }

        return List.of(BotResponse.text("тєжко."));
    }

    private List<BotResponse> initiateDefaultDownloadSearch(long chatId, ReleaseMetadata metadata) {
        return initiateDownloadSearch(chatId, metadata, DownloadEngine.QOBUZ);
    }

    private List<BotResponse> initiateDownloadSearch(long chatId, ReleaseMetadata metadata, DownloadEngine source) {
        log.info("Initiating download search for: {} - {}", metadata.artist(), metadata.title());

        searchFilesProducer.send(SearchFilesTaskDto.of(chatId, metadata.id(), metadata.artist(), metadata.title(), source));

        return List.of(BotResponse.text(
                "🔎 шукаю опції завантаження (%s): _%s - %s_".formatted(
                        source.getName().toLowerCase(),
                        metadata.artist(),
                        metadata.title()).toLowerCase()
        ));
    }

    public List<BotResponse> handleSearchResults(SearchFilesResultDto dto) {
        log.info("Processing search results for chatId={}, releaseId={}, source={}, results count={}",
                dto.chatId(), dto.releaseId(), dto.source(), dto.results().size());

        var flowHandler = downloadFlowHandlers.get(dto.source());

        var analysisResult = flowHandler.analyzeAll(dto.results(), dto.releaseId(), dto.chatId());
        var reports = analysisResult.reports();
        downloadContextHolder.saveDownloadOptions(dto.chatId(), dto.releaseId(), reports);

        reports.forEach(r -> log.info("{}", r));

        if (dto.autoDownload()) {
            return autoDownload(dto, reports);
        }

        String text = DownloadOptionsCardFormatter.format(reports, analysisResult.aiSummary());
        return List.of(flowHandler.buildSearchResultsResponse(text, dto.releaseId(), dto.source()));
    }

    private List<BotResponse> autoDownload(SearchFilesResultDto dto, List<DownloadFlowHandler.OptionReport> reports) {
        var chosenReport = reports.getFirst();
        var option = chosenReport.option();

        log.info("Auto-downloading from {}: {}", option.source(), option.displayName());
        downloadTaskProducer.send(DownloadFilesTaskDto.of(dto.chatId(), dto.releaseId(), option));

        var flowHandler = downloadFlowHandlers.get(option.source());
        return List.of(flowHandler.buildAutoDownloadResponse(option, dto.releaseId()));
    }

    public List<BotResponse> handleDownloadOption(long chatId, String rawInput) {
        var reports = downloadContextHolder.getDownloadOptions(chatId);
        if (reports.isEmpty()) {
            return List.of(BotResponse.text("😔 **варіанти пропали, нич нема, давай ше раз.**"));
        }

        Integer optionNumber = parseNumberFromInput(rawInput);
        if (optionNumber == null || optionNumber < 1 || optionNumber > reports.size()) {
            return List.of(BotResponse.text("🤔 **незрозумілий зроз.**"));
        }

        var chosenReport = reports.get(optionNumber - 1);
        var option = chosenReport.option();
        String releaseId = downloadContextHolder.getChosenRelease(chatId);

        log.info("User chose option #{}: {} from {}", optionNumber, option.id(), option.displayName());

        downloadTaskProducer.send(DownloadFilesTaskDto.of(chatId, releaseId, option));

        var flowHandler = downloadFlowHandlers.get(option.source());
        String message = flowHandler.formatDownloadConfirmation(option);
        return List.of(BotResponse.text(message));
    }

    private Integer parseNumberFromInput(String input) {
        try {
            return Integer.parseInt(input.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public List<BotResponse> getDownloadOptions(long chatId, String query) {
        log.info("Direct download request for chatId={}, query: {}", chatId, query);

        var searchResult = releaseSearchFlowService.searchWithFallback(query, SearchEngine.MUSICBRAINZ, SearchEngine.DISCOGS);

        if (searchResult.releases().isEmpty()) {
            return List.of(BotResponse.text("😔 **нич взагалі не знайшов у світі авдіо, спробуй по-іншому.**"));
        }

        ReleaseMetadata selectedRelease = searchResult.releases().getFirst();
        log.info("Auto-selected release: {} - {} from {}",
                selectedRelease.artist(), selectedRelease.title(), searchResult.engine());

        contextService.saveSearchContext(chatId, searchResult.engine(), query,
                searchResult.searchRequest(), searchResult.releases());
        contextService.saveReleaseMetadata(selectedRelease);

        List<BotResponse> responses = new ArrayList<>();
        responses.add(releaseSearchFlowService.buildReleaseDownloadCard(selectedRelease, searchResult.engine()));
        responses.addAll(initiateDefaultDownloadSearch(chatId, selectedRelease));

        return responses;
    }

    public List<BotResponse> handleDownloadCancel(long chatId, String data) {
        String releaseId = data.substring("CANCEL_DL:".length());
        log.info("User requested cancel for releaseId={}", releaseId);

        downloadCancelTaskProducer.send(DownloadCancelTaskDto.of(chatId, releaseId));

        return List.of(BotResponse.text("⏳ **скасовую...**"));
    }


    public List<BotResponse> handleSearchAlternative(long chatId, String data) {
        int lastColonIndex = data.lastIndexOf(':');
        if (lastColonIndex == -1 || lastColonIndex <= "SEARCH_ALT:".length()) {
            return List.of(BotResponse.text("❌ шось не то з командою"));
        }

        String releaseId = data.substring("SEARCH_ALT:".length(), lastColonIndex);
        String sourceName = data.substring(lastColonIndex + 1);

        log.info("Alternative search requested: releaseId={}, source={}", releaseId, sourceName);

        ReleaseMetadata metadata = contextService.getReleaseMetadata(releaseId);
        if (metadata == null) {
            return List.of(BotResponse.text("❌ шось ся не получило...найди реліз ше раз"));
        }

        var source = DownloadEngine.valueOf(sourceName);
        return initiateDownloadSearch(chatId, metadata, source);
    }
}

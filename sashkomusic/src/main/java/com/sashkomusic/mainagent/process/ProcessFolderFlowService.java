package com.sashkomusic.mainagent.process;

import com.sashkomusic.libraryagent.domain.service.processFolder.ReleaseIdentifierService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.download.DownloadContextHolder;
import com.sashkomusic.mainagent.process.messaging.ProcessLibraryTaskProducer;
import com.sashkomusic.mainagent.process.messaging.dto.ProcessLibraryTaskDto;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.Language;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.sashkomusic.mainagent.search.SearchEngine.BANDCAMP;
import static com.sashkomusic.mainagent.search.SearchEngine.DISCOGS;
import static com.sashkomusic.mainagent.search.SearchEngine.MUSICBRAINZ;

/**
 * Orchestrates the {@code /process <folder>} Telegram dialogue:
 * scan folder → identify release → search 3 sources → present options →
 * on user pick, dispatch a {@code ProcessLibraryTaskEvent} to libraryagent.
 * <p>
 * Heavy lifting is delegated:
 *   - {@link FolderAudioScanner}     — filesystem + folder name cleaning
 *   - {@link ProcessFolderSearcher}  — multi-source metadata search
 *   - {@link ProcessOptionsFormatter}— Telegram options card
 *   - {@link ReleaseIdentifierService} (libraryagent) — folder/tags → search request
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessFolderFlowService {

    private final FolderAudioScanner audioScanner;
    private final ProcessFolderSearcher searcher;
    private final ProcessOptionsFormatter optionsFormatter;
    private final ReleaseIdentifierService identifierService;
    private final SearchContextService searchContextService;
    private final ProcessFolderContextHolder contextHolder;
    private final ProcessLibraryTaskProducer libraryTaskProducer;
    private final PathMappingService pathMappingService;
    private final DownloadContextHolder downloadContextHolder;

    public List<BotResponse> handleProcessCommand(long chatId, String rawInput) {
        return process(chatId, audioScanner.stripProcessPrefix(rawInput), "");
    }

    public List<BotResponse> process(long chatId, String folderName) {
        return process(chatId, folderName, "");
    }

    public List<BotResponse> process(long chatId, String folderName, String additionalContext) {
        try {
            String processPath = pathMappingService.mapProcessPath(folderName);
            FolderAudioScanner.ResolvedFolder folder = audioScanner.resolve(processPath);

            if (!folder.isDirectory()) {
                return List.of(BotResponse.text("❌ папка не знайдена: `" + folder.path() + "`"));
            }

            List<String> audioFiles = audioScanner.listAudioFiles(folder.path());
            if (audioFiles.isEmpty()) {
                return List.of(BotResponse.text("❌ В папці немає аудіо-файлів"));
            }

            log.info("Processing folder: {}, found {} audio files", folder.name(), audioFiles.size());

            MetadataSearchRequest searchRequest = buildSearchRequest(folder.name(), audioFiles, additionalContext);
            List<BotResponse> invalid = validateSearchRequest(searchRequest, folder.name());
            if (invalid != null) return invalid;

            ProcessFolderSearcher.SearchResults results = searcher.searchAll(searchRequest);
            BotResponse header = BotResponse.text("📄 %d файлів, знайдено метадані:".formatted(audioFiles.size()));

            if (results.isEmpty()) {
                return List.of(header, BotResponse.text("❌ нема шось метаданих"));
            }

            saveSearchContext(chatId, folder.name(), searchRequest, results, folder.path(), audioFiles);
            return List.of(header, optionsFormatter.format(results));

        } catch (Exception e) {
            log.error("Error processing folder: {}", e.getMessage(), e);
            return List.of(BotResponse.text("❌ помилка обробки папки: " + e.getMessage()));
        }
    }

    public List<BotResponse> handleMetadataSelection(long chatId, String rawInput) {
        String trimmed = rawInput.trim();

        if (trimmed.startsWith("+")) {
            return handleAdditionalContext(chatId, trimmed.substring(1).trim());
        }

        Integer optionNumber = parseInt(trimmed);
        if (optionNumber == null) {
            return List.of(BotResponse.text("❌ невірний номер. спробуй ще раз"));
        }

        String contextKey = contextHolder.getChatContextKey(chatId);
        if (contextKey == null) {
            return List.of(BotResponse.text("❌ сесія закінчилась. спробуй /process ще раз"));
        }

        String releaseId = contextHolder.getReleaseIdByOption(chatId, optionNumber);
        if (releaseId == null) {
            return List.of(BotResponse.text("❌ невірний номер. спробуй ще раз"));
        }

        ProcessFolderContextHolder.ProcessFolderContext folderContext = contextHolder.get(contextKey);
        if (folderContext == null) {
            return List.of(BotResponse.text("❌ контекст втрачено. спробуй /process ще раз"));
        }

        ReleaseMetadata metadata = searchContextService.getMetadataWithTracks(releaseId, chatId);
        if (metadata == null) {
            return List.of(BotResponse.text("❌ метадані не знайдено"));
        }

        libraryTaskProducer.send(ProcessLibraryTaskDto.of(
                chatId, folderContext.directoryPath(), folderContext.audioFiles(), metadata));

        contextHolder.remove(contextKey);
        contextHolder.clearChatSelection(chatId);

        log.info("Sent library processing task: chatId={}, directory={}",
                chatId, folderContext.directoryPath());
        return List.of(BotResponse.text("🚀 опрацьовую..."));
    }

    public boolean hasActiveContext(long chatId) {
        return contextHolder.getChatContextKey(chatId) != null;
    }

    private List<BotResponse> handleAdditionalContext(long chatId, String additionalContext) {
        String contextKey = contextHolder.getChatContextKey(chatId);
        if (contextKey == null) {
            return List.of(BotResponse.text("❌ сесія закінчилась. спробуй /process ще раз"));
        }
        ProcessFolderContextHolder.ProcessFolderContext folderContext = contextHolder.get(contextKey);
        if (folderContext == null) {
            return List.of(BotResponse.text("❌ контекст втрачено. спробуй /process ще раз"));
        }
        downloadContextHolder.clearSession(chatId);
        return process(chatId, folderContext.directoryPath(), additionalContext);
    }

    private MetadataSearchRequest buildSearchRequest(String folderName, List<String> audioFiles, String additionalContext) {
        var fromTags = identifierService.identifyFromAudioFile(audioFiles.getFirst());

        if (fromTags != null) {
            String artist = extractMostCommonArtist(audioFiles);
            log.info("Using release info from audio file tags (majority artist='{}')", artist);
            return MetadataSearchRequest.create(
                    artist, withContext(fromTags.album(), additionalContext),
                    null, null, null, null, null, null, null, null, null, Language.EN);
        }

        log.info("No tags in audio file, parsing folder name");
        return identifierService.identifyFromFolderName(withContext(folderName, additionalContext));
    }

    private String extractMostCommonArtist(List<String> audioFiles) {
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (String file : audioFiles) {
            var info = identifierService.identifyFromAudioFile(file);
            if (info != null && info.artist() != null && !info.artist().isBlank()) {
                counts.merge(info.artist().trim(), 1, Integer::sum);
                total++;
            }
        }
        if (counts.isEmpty()) return null;

        var top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        double percentage = (double) top.getValue() / total * 100;
        log.info("Most common artist: '{}' ({} of {} tracks, {})",
                top.getKey(), top.getValue(), total, "%.1f%%".formatted(percentage));
        return percentage >= 50.0 ? top.getKey() : null;
    }

    private List<BotResponse> validateSearchRequest(MetadataSearchRequest searchRequest, String folderName) {
        if (searchRequest == null || searchRequest.release().isEmpty()) {
            return List.of(BotResponse.text("""
                    ❌ не вдалося розпізнати назву релізу з папки: `%s`

                    допиши контексту:
                    • /process Artist - Album
                    • /process Artist - Album касета 1990 україна
                    """.formatted(folderName)));
        }
        return null;
    }

    private void saveSearchContext(long chatId, String folderName, MetadataSearchRequest searchRequest,
                                   ProcessFolderSearcher.SearchResults results, Path folderPath, List<String> audioFiles) {
        List<ReleaseMetadata> all = results.allResults();

        SearchEngine primarySource = !results.mbResults().isEmpty() ? MUSICBRAINZ
                : !results.discogsResults().isEmpty() ? DISCOGS : BANDCAMP;

        searchContextService.saveSearchContext(chatId, primarySource, folderName, searchRequest, all);

        List<String> releaseIds = all.stream()
                .peek(searchContextService::saveReleaseMetadata)
                .map(ReleaseMetadata::id)
                .toList();
        contextHolder.storeReleaseIds(chatId, releaseIds);

        String contextKey = contextHolder.generateShortKey();
        contextHolder.store(contextKey, folderPath.toString(), audioFiles);
        contextHolder.storeChatContext(chatId, contextKey);
    }

    private static String withContext(String value, String additionalContext) {
        return value + " " + additionalContext;
    }

    private static Integer parseInt(String input) {
        try {
            return Integer.parseInt(input.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
